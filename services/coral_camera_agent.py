#!/usr/bin/env python3
"""coral_camera_agent.py — Coral Edge TPU multi-camera + pose pipeline.

Single process, ONE Edge TPU, N USB cameras. The Coral Dev Board has one
TPU and libedgetpu.so.1 is single-tenant per process — so two python
processes can't both claim it. This agent opens all cameras in one process
and round-robins frames across them, sharing the TPU detector + pose
interpreters.

Two TPU models running per camera frame:
  * EfficientDet-Lite0 (320x320 PTQ, ~5 MB) — object detection (90 COCO classes)
  * MoveNet Lightning (192x192 PTQ, ~3 MB) — single-person 17-keypoint pose

Per-camera per-frame loop:
  1. Object detection on full frame
  2. If a person is detected, crop to their bbox + run MoveNet for pose
  3. Track entity IDs across frames (simple IoU + nearest centroid)
  4. Emit MQTT deltas for add/move/remove events
  5. Pose-based fall detection: sustained downward head motion + horizontal
     pose aspect ratio + brief pose-confidence drop -> publish fall_event
  6. Keyframe trigger every 5 min OR on motion-spike OR on new-entity event;
     JPEG saved locally, ready-event MQTT-published with file path
  7. Low-rate base64 thumbnail for phone-UI preview (per-camera throttle)

Each camera publishes to its own topic prefix camera/{cam_name}/... — the
MQTT schema is unchanged from the single-camera version.

Run (multi-camera):
  python coral_camera_agent.py \\
    --cameras /dev/video1:kitchen,/dev/video2:livingroom \\
    --broker localhost --keyframe-dir ~/hearth/keyframes

Run (legacy single-camera, still supported):
  python coral_camera_agent.py --camera /dev/video1 --cam-name kitchen \\
    --broker localhost --keyframe-dir ~/hearth/keyframes
"""
from __future__ import annotations

import argparse
import base64
import collections
import json
import logging
import math
import os
import signal
import threading
import time
import urllib.request
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from socketserver import ThreadingMixIn
from typing import Any, Dict, List, Optional, Tuple

import cv2
import numpy as np
import paho.mqtt.client as mqtt

# pycoral 1.0.1 (Mendel default)
from pycoral.utils.edgetpu import make_interpreter
from pycoral.adapters import common, detect

LOG = logging.getLogger("coral_camera")

# --- tunables -----------------------------------------------------------------
TARGET_FPS              = 15
DET_THRESHOLD           = 0.30  # lowered from 0.40 — pick up small/occluded objects like phones-in-hand
KEYFRAME_TIME_S         = 300
KEYFRAME_MOTION_PIX     = 50_000
KEYFRAME_COOLDOWN_S     = 10
MOVE_THRESHOLD_PX       = 40
REMOVE_AFTER_MISS_S     = 3.0
JPEG_QUALITY            = 75

# Fall detection: track head/shoulder vertical motion over a 1.5 s window.
POSE_HISTORY_LEN        = 22
FALL_HEAD_DROP_FRAC     = 0.25
FALL_ASPECT_THRESHOLD   = 0.85
FALL_MIN_CONFIDENCE     = 0.30
FALL_COOLDOWN_S         = 30

# Gait analysis: rolling per-person ankle trajectory -> stride / cadence / asymmetry.
# Heel-strike = local minimum of ankle vertical position in image coords (lower
# y == higher in frame == foot lifted; strike is the peak of the lift->plant
# cycle, presenting as a min of smoothed y). Shorter stride, slower cadence,
# and L/R swing asymmetry predict falls weeks ahead in older adults.
GAIT_WINDOW_S            = 5.0
GAIT_MIN_KP_CONF         = 0.20
GAIT_MIN_STRIDES         = 4
GAIT_PUBLISH_INTERVAL_S  = 5.0
GAIT_STATIONARY_PX       = 25       # in 640x480 frame coords
GAIT_STRIKE_PROMINENCE   = 0.005    # min y-dip (normalised pose coords) to count
GAIT_MIN_STRIKE_GAP_S    = 0.25     # debounce same-foot strikes
GAIT_HIP_ANKLE_M_ASSUMED = 0.85     # avg adult hip->ankle vertical span (metres)
GAIT_MAX_BUFFER          = 256      # ~5 s @ <=50 Hz; bounds per-person memory

# Thumbnail stream: low-rate base64 JPEG for phone UI preview.
THUMB_W                 = 320
THUMB_H                 = 240
THUMB_JPEG_QUALITY      = 70
THUMB_MIN_INTERVAL_S    = 0.1  # 10 FPS per camera

# Person-segmentation overlay (DeepLab v3 MobileNet quant). PASCAL VOC class 15 = person.
SEG_MIN_INTERVAL_S      = 0.2   # ~5 FPS per camera (only fires if person detected)
SEG_PERSON_CLASS        = 15
SEG_OVERLAY_ALPHA       = 110   # 0-255; how opaque the green silhouette is

# MJPEG-over-HTTP server (the "live feed"). Bypasses MQTT — phone WebView
# pulls multipart/x-mixed-replace directly from this server.
MJPEG_PORT              = 8080
MJPEG_W                 = 640
MJPEG_H                 = 480
MJPEG_JPEG_QUALITY      = 80
MJPEG_FPS_CAP           = 15    # send-side rate cap; clients can sub-sample

# COCO classes we publish
PUBLISH_CLASSES = {
    0:  "person",
    24: "backpack", 25: "umbrella", 26: "handbag", 28: "suitcase",
    39: "bottle", 41: "cup", 44: "spoon", 45: "bowl",
    56: "chair", 57: "couch", 60: "bed", 62: "tv",
    63: "laptop", 64: "mouse", 65: "remote", 66: "keyboard", 67: "cell_phone",
    73: "book", 76: "scissors", 77: "teddy_bear",
}

CORAL_MODEL_BASE = "https://github.com/google-coral/test_data/raw/master"
MODELS = {
    "detect": "efficientdet_lite0_320_ptq_edgetpu.tflite",
    "pose":   "movenet_single_pose_lightning_ptq_edgetpu.tflite",
    "seg":    "deeplabv3_mnv2_pascal_quant_edgetpu.tflite",
    "labels": "coco_labels.txt",
}
DEFAULT_MODEL_DIR = Path("~/hearth/models").expanduser()


def iso_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def ensure_models(dir_path: Path) -> dict:
    dir_path.mkdir(parents=True, exist_ok=True)
    paths = {}
    for key, fname in MODELS.items():
        p = dir_path / fname
        if not p.exists() or p.stat().st_size == 0:
            url = "{0}/{1}".format(CORAL_MODEL_BASE, fname)
            LOG.info("downloading %s -> %s", url, p)
            urllib.request.urlretrieve(url, p)
        paths[key] = p
    return paths


KP_NOSE, KP_LSHO, KP_RSHO = 0, 5, 6
KP_LHIP, KP_RHIP = 11, 12
KP_LANKLE, KP_RANKLE = 15, 16


def parse_movenet(interpreter) -> np.ndarray:
    out = common.output_tensor(interpreter, 0)
    return np.asarray(out).reshape(17, 3)


def head_y_score(kps: np.ndarray) -> Tuple[float, float]:
    pts = [kps[KP_NOSE], kps[KP_LSHO], kps[KP_RSHO]]
    pts = [p for p in pts if p[2] > 0.2]
    if not pts:
        return float("nan"), 0.0
    y = float(np.mean([p[0] for p in pts]))
    c = float(np.mean([p[2] for p in pts]))
    return y, c


def pose_aspect(kps: np.ndarray) -> float:
    valid = kps[kps[:, 2] > 0.2]
    if len(valid) < 4:
        return float("nan")
    h = float(valid[:, 0].max() - valid[:, 0].min())
    w = float(valid[:, 1].max() - valid[:, 1].min())
    return h / max(w, 1e-6)


class GaitAnalyzer:
    """Per-person rolling buffer of ankle/hip positions -> gait metrics.

    Consumes (t, kps_normalised, person_bbox) per frame and detects heel
    strikes as local minima in ankle y. Emits stride length, cadence, and
    swing-time asymmetry on demand. Ankle coords are in MoveNet's normalised
    [0..1] pose-input frame; we map back to original-frame pixels via the
    person bbox passed in each frame.
    """

    __slots__ = ("samples", "last_publish_t",
                 "last_l_strike_t", "last_r_strike_t")

    def __init__(self):
        self.samples = collections.deque(maxlen=GAIT_MAX_BUFFER)
        self.last_publish_t = 0.0
        self.last_l_strike_t = 0.0
        self.last_r_strike_t = 0.0

    def add(self, t, kps, person_bbox):
        """Record one sample; coords converted to original-frame pixels."""
        l = kps[KP_LANKLE]; r = kps[KP_RANKLE]
        lh = kps[KP_LHIP];  rh = kps[KP_RHIP]
        if (l[2] < GAIT_MIN_KP_CONF or r[2] < GAIT_MIN_KP_CONF
                or lh[2] < GAIT_MIN_KP_CONF or rh[2] < GAIT_MIN_KP_CONF):
            return
        x1, y1, x2, y2 = person_bbox
        bw = max(1.0, float(x2 - x1)); bh = max(1.0, float(y2 - y1))
        # Normalised pose-frame (y, x) -> original-frame pixels.
        ly_px = y1 + l[0] * bh; lx_px = x1 + l[1] * bw
        ry_px = y1 + r[0] * bh; rx_px = x1 + r[1] * bw
        hip_y_px = y1 + 0.5 * (lh[0] + rh[0]) * bh
        hip_x_px = x1 + 0.5 * (lh[1] + rh[1]) * bw
        conf = float(min(l[2], r[2], lh[2], rh[2]))
        self.samples.append(
            (t, ly_px, lx_px, ry_px, rx_px, hip_y_px, hip_x_px, conf)
        )
        cutoff = t - GAIT_WINDOW_S
        while self.samples and self.samples[0][0] < cutoff:
            self.samples.popleft()

    @staticmethod
    def _find_strikes(times, ys, last_strike_t):
        """Local minima in ys with min prominence + min temporal gap."""
        strikes = []
        n = len(ys)
        if n < 5:
            return strikes
        for i in range(2, n - 2):
            y = ys[i]
            if y > ys[i-1] or y > ys[i+1] or y > ys[i-2] or y > ys[i+2]:
                continue
            local_max = max(ys[i-2], ys[i+2])
            if (local_max - y) < GAIT_STRIKE_PROMINENCE:
                continue
            t = times[i]
            if strikes and (t - strikes[-1]) < GAIT_MIN_STRIKE_GAP_S:
                continue
            if (t - last_strike_t) < GAIT_MIN_STRIKE_GAP_S:
                continue
            strikes.append(t)
        return strikes

    def compute(self, now):
        """Return dict of gait metrics or None if not enough data / stationary."""
        if (now - self.last_publish_t) < GAIT_PUBLISH_INTERVAL_S:
            return None
        if len(self.samples) < 8:
            return None
        s = list(self.samples)
        times = [row[0] for row in s]
        ly = [row[1] for row in s]; lx = [row[2] for row in s]
        ry = [row[3] for row in s]; rx = [row[4] for row in s]
        hip_y = [row[5] for row in s]; hip_x = [row[6] for row in s]

        if (max(hip_x) - min(hip_x)) < GAIT_STATIONARY_PX:
            return None

        bbox_h = max(1.0, max(hip_y) - min(hip_y) + 1.0)
        ly_n = [(v - hip_y[i]) / bbox_h for i, v in enumerate(ly)]
        ry_n = [(v - hip_y[i]) / bbox_h for i, v in enumerate(ry)]

        l_strikes = self._find_strikes(times, ly_n, self.last_l_strike_t)
        r_strikes = self._find_strikes(times, ry_n, self.last_r_strike_t)
        n_strides = len(l_strikes) + len(r_strikes)
        if n_strides < GAIT_MIN_STRIDES:
            return None

        window_sec = times[-1] - times[0]
        if window_sec <= 0.1:
            return None
        cadence_spm = (n_strides / window_sec) * 60.0

        def _avg_stride_px(strike_times, xs):
            if len(strike_times) < 2:
                return None
            ds = []
            for a, b in zip(strike_times[:-1], strike_times[1:]):
                ia = min(range(len(times)), key=lambda k: abs(times[k] - a))
                ib = min(range(len(times)), key=lambda k: abs(times[k] - b))
                ds.append(abs(xs[ib] - xs[ia]))
            return sum(ds) / len(ds) if ds else None
        sl_l = _avg_stride_px(l_strikes, lx)
        sl_r = _avg_stride_px(r_strikes, rx)
        sl_candidates = [v for v in (sl_l, sl_r) if v is not None]
        if not sl_candidates:
            return None
        stride_px = sum(sl_candidates) / len(sl_candidates)

        # px->m calibration: hip-to-ankle vertical span ~ 0.85 m for adults.
        ankle_y_mean = 0.5 * (sum(ly) / len(ly) + sum(ry) / len(ry))
        hip_to_ankle_px = max(1.0, ankle_y_mean - (sum(hip_y) / len(hip_y)))
        px_per_m = hip_to_ankle_px / GAIT_HIP_ANKLE_M_ASSUMED
        stride_m_rough = stride_px / max(px_per_m, 1.0)

        def _mean_interval(ts):
            if len(ts) < 2:
                return None
            ivs = [b - a for a, b in zip(ts[:-1], ts[1:])]
            return sum(ivs) / len(ivs)
        sw_l = _mean_interval(l_strikes)
        sw_r = _mean_interval(r_strikes)
        if sw_l and sw_r:
            asym = abs(sw_l - sw_r) / ((sw_l + sw_r) / 2.0) * 100.0
        else:
            asym = 0.0

        if n_strides >= 8:
            conf = 0.9
        elif n_strides >= 6:
            conf = 0.75
        else:
            conf = 0.5

        if l_strikes:
            self.last_l_strike_t = l_strikes[-1]
        if r_strikes:
            self.last_r_strike_t = r_strikes[-1]
        self.last_publish_t = now

        return {
            "stride_length_px": int(round(stride_px)),
            "stride_length_m_rough": round(stride_m_rough, 2),
            "cadence_spm": int(round(cadence_spm)),
            "swing_asymmetry_pct": round(asym, 1),
            "n_strides": int(n_strides),
            "window_sec": round(window_sec, 1),
            "confidence": conf,
        }


class CameraStream:
    """Per-camera state owned by CoralCameraAgent.

    Capture is decoupled from TPU processing: a dedicated capture thread
    pulls frames at the camera's native rate (~30 FPS), updates
    ``latest_frame`` under a lock, and pushes the raw frame to the MJPEG
    cache so the live feed runs at full speed regardless of how slow the
    TPU loop is. The TPU main loop then reads ``latest_frame`` at its own
    pace (~5 FPS per camera with 2 cameras + 3 models).
    """

    def __init__(self, video_dev: str, cam_name: str):
        self.video_dev = video_dev
        self.cam_name  = cam_name

        self.cap = cv2.VideoCapture(video_dev)
        if not self.cap.isOpened():
            raise RuntimeError("cannot open {0}".format(video_dev))
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH,  640)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        # Ask for camera-native ~30 FPS — feeds the live MJPEG smoothness.
        # TPU loop still runs at its own slower rate via latest_frame.
        self.cap.set(cv2.CAP_PROP_FPS, 30)

        self.bg = cv2.createBackgroundSubtractorMOG2(history=300,
                                                     detectShadows=False)
        self.tracked = {}
        self.next_track_id = 1
        self.pose_history = collections.deque(maxlen=POSE_HISTORY_LEN)
        self.last_keyframe_t = 0.0
        self.last_fall_t     = 0.0
        self.last_thumb_t    = 0.0
        self.last_seg_t      = 0.0
        self.had_person_last_frame = False
        # Per-track-id gait analyzers (created on first sighting; cleaned up
        # when the track is removed from self.tracked).
        self.gait: Dict[int, "GaitAnalyzer"] = {}

        # Capture-decoupling state
        self.frame_lock = threading.Lock()
        self.latest_frame: Optional[np.ndarray] = None
        self.capture_running = True
        self.capture_thread: Optional[threading.Thread] = None
        self.captured_count = 0
        self.read_fail_count = 0

    def start_capture(self) -> None:
        """Spawn the dedicated capture loop for this camera."""
        if self.capture_thread is not None:
            return
        self.capture_thread = threading.Thread(
            target=self._capture_loop,
            name="cap-{0}".format(self.cam_name),
            daemon=True,
        )
        self.capture_thread.start()

    def _capture_loop(self) -> None:
        LOG.info("%s: capture thread started", self.cam_name)
        while self.capture_running:
            try:
                ok, frame = self.cap.read()
            except Exception as e:
                LOG.warning("%s: cap.read exception: %s", self.cam_name, e)
                time.sleep(0.05)
                continue
            if not ok:
                self.read_fail_count += 1
                if self.read_fail_count % 50 == 1:
                    LOG.warning("%s: cap.read failed (#%d)",
                                self.cam_name, self.read_fail_count)
                time.sleep(0.05)
                continue
            self.captured_count += 1
            with self.frame_lock:
                self.latest_frame = frame
            # Push to MJPEG cache at capture rate (the whole point).
            update_mjpeg_frame(self.cam_name, frame)
        LOG.info("%s: capture thread stopped (frames=%d, fails=%d)",
                 self.cam_name, self.captured_count, self.read_fail_count)

    def get_latest_frame(self) -> Optional[np.ndarray]:
        with self.frame_lock:
            return self.latest_frame

    def stop_capture(self) -> None:
        self.capture_running = False
        t = self.capture_thread
        if t is not None and t.is_alive():
            t.join(timeout=2.0)
        self.capture_thread = None

    def release(self):
        self.stop_capture()
        if self.cap is not None:
            try:
                self.cap.release()
            except Exception as e:
                LOG.warning("%s: cap.release failed: %s", self.cam_name, e)


# --- MJPEG HTTP server ------------------------------------------------------

# Process-wide latest-JPEG cache, indexed by cam_name. Updated by the agent's
# main loop each frame; read by the MJPEG handler in HTTP worker threads.
LATEST_JPEG: Dict[str, bytes] = {}
LATEST_JPEG_LOCK = threading.Lock()

# Process-wide latest binary person-mask per cam_name (uint8, MJPEG_H × MJPEG_W,
# 0 = bg / 255 = person). Populated by the TPU loop via emit_seg, read by
# update_mjpeg_frame to composite the seg overlay directly into the JPEG so
# the live feed shows video + silhouette as a single perfectly-aligned stream.
LATEST_SEG_MASK: Dict[str, np.ndarray] = {}
LATEST_SEG_MASK_LOCK = threading.Lock()


class _ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class _MJPEGHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        # /cam/<name>  → MJPEG stream for that camera
        # /            → simple text index
        if self.path == "/" or self.path == "/index":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            with LATEST_JPEG_LOCK:
                names = sorted(LATEST_JPEG.keys())
            self.wfile.write(("Hearth MJPEG server\nCameras:\n  " +
                              "\n  ".join("/cam/{0}".format(n) for n in names)).encode())
            return
        if not self.path.startswith("/cam/"):
            self.send_error(404, "use /cam/<name>")
            return
        cam_name = self.path[len("/cam/"):].strip("/").split("?", 1)[0]
        with LATEST_JPEG_LOCK:
            present = cam_name in LATEST_JPEG
        if not present:
            self.send_error(404, "no such camera")
            return

        boundary = b"--hearthframe"
        self.send_response(200)
        self.send_header("Content-Type",
                         "multipart/x-mixed-replace; boundary=hearthframe")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        period = 1.0 / float(MJPEG_FPS_CAP)
        last_sent_id = None
        try:
            while True:
                with LATEST_JPEG_LOCK:
                    frame = LATEST_JPEG.get(cam_name)
                    fid = id(frame)
                if frame is not None and fid != last_sent_id:
                    self.wfile.write(boundary + b"\r\n")
                    self.wfile.write(
                        ("Content-Type: image/jpeg\r\n"
                         "Content-Length: {0}\r\n\r\n").format(len(frame)).encode()
                    )
                    self.wfile.write(frame)
                    self.wfile.write(b"\r\n")
                    last_sent_id = fid
                time.sleep(period)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

    def log_message(self, format, *args):
        # Silence stdlib's per-request logging; LOG.info below is enough.
        return


def start_mjpeg_server(port: int = MJPEG_PORT) -> threading.Thread:
    server = _ThreadingHTTPServer(("0.0.0.0", port), _MJPEGHandler)
    t = threading.Thread(
        target=server.serve_forever, name="mjpeg-http", daemon=True
    )
    t.start()
    LOG.info("MJPEG server on http://0.0.0.0:%d/cam/<name>", port)
    return t


def update_mjpeg_frame(cam_name: str, frame: np.ndarray) -> None:
    """Encode the frame as a JPEG and stash it as the latest for cam_name.

    If a person-segmentation mask is available for this camera, blend it in
    BEFORE encoding so the live MJPEG stream serves video + silhouette as one
    perfectly-aligned image. Eliminates the seg-vs-video desync the user saw
    when the overlay was published as a separate MQTT topic.
    """
    try:
        out = frame
        if (frame.shape[1] != MJPEG_W) or (frame.shape[0] != MJPEG_H):
            out = cv2.resize(frame, (MJPEG_W, MJPEG_H),
                             interpolation=cv2.INTER_AREA)
        # Composite seg mask if present
        with LATEST_SEG_MASK_LOCK:
            mask = LATEST_SEG_MASK.get(cam_name)
        if mask is not None and mask.shape == (MJPEG_H, MJPEG_W):
            person_px = mask > 0
            if person_px.any():
                alpha = SEG_OVERLAY_ALPHA / 255.0
                out = out.copy()  # don't mutate caller frame
                # OpenCV BGR — tint G up, others down where person.
                out[person_px, 0] = (out[person_px, 0] * (1 - alpha)).astype(np.uint8)
                out[person_px, 1] = (out[person_px, 1] * (1 - alpha) + 255 * alpha).astype(np.uint8)
                out[person_px, 2] = (out[person_px, 2] * (1 - alpha)).astype(np.uint8)
        ok, buf = cv2.imencode(".jpg", out,
                               [int(cv2.IMWRITE_JPEG_QUALITY), MJPEG_JPEG_QUALITY])
        if not ok:
            return
        data = buf.tobytes()
        with LATEST_JPEG_LOCK:
            LATEST_JPEG[cam_name] = data
    except Exception as e:
        LOG.warning("%s: update_mjpeg_frame failed: %s", cam_name, e)


class CoralCameraAgent:
    """Orchestrator: ONE TPU detector + pose, ONE MQTT client, N CameraStreams.
    The TPU is shared via single-process round-robin (libedgetpu is
    single-tenant per process; no thread-level sharing is safer)."""

    def __init__(self, streams: List[CameraStream], broker: str,
                 keyframe_dir: Path, model_dir: Path, mqtt_port: int = 1883):
        if not streams:
            raise ValueError("CoralCameraAgent requires at least one CameraStream")
        self.streams      = streams
        self.broker       = broker
        self.mqtt_port    = mqtt_port
        self.keyframe_dir = keyframe_dir

        models = ensure_models(model_dir)
        self.det = make_interpreter(str(models["detect"]))
        self.pose = make_interpreter(str(models["pose"]))
        self.seg = make_interpreter(str(models["seg"]))
        self.det.allocate_tensors()
        self.pose.allocate_tensors()
        self.seg.allocate_tensors()
        self.det_w, self.det_h = common.input_size(self.det)
        self.pose_w, self.pose_h = common.input_size(self.pose)
        self.seg_w, self.seg_h = common.input_size(self.seg)
        LOG.info("EfficientDet %dx%d  MoveNet %dx%d  DeepLab %dx%d",
                 self.det_w, self.det_h, self.pose_w, self.pose_h,
                 self.seg_w, self.seg_h)
        LOG.info("cameras: %s",
                 ", ".join("{0}->{1}".format(s.cam_name, s.video_dev)
                           for s in self.streams))

        self.client = None
        self.running = True

    def setup_mqtt(self):
        self.keyframe_dir.mkdir(parents=True, exist_ok=True)
        cam_ids = "-".join(s.cam_name for s in self.streams)
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id="camera-{0}".format(cam_ids))
        primary = self.streams[0]
        primary_avail = "camera/{0}/availability".format(primary.cam_name)
        self.client.will_set(primary_avail, "offline", qos=1, retain=True)
        self.client.connect(self.broker, self.mqtt_port, keepalive=30)
        for s in self.streams:
            self.client.publish("camera/{0}/availability".format(s.cam_name),
                                "online", qos=1, retain=True)
        self.client.loop_start()
        LOG.info("mqtt: %s:%d  cams=%s",
                 self.broker, self.mqtt_port,
                 [s.cam_name for s in self.streams])

    def teardown(self):
        LOG.info("shutdown")
        if self.client:
            for s in self.streams:
                try:
                    self.client.publish(
                        "camera/{0}/availability".format(s.cam_name),
                        "offline", qos=1, retain=True)
                except Exception as e:
                    LOG.warning("%s: offline publish failed: %s",
                                s.cam_name, e)
            self.client.loop_stop()
            self.client.disconnect()
        for s in self.streams:
            s.release()

    def emit(self, stream: CameraStream, op: str, **payload):
        if not self.client:
            return
        msg = {"t": iso_now(), "src": "camera", "cam": stream.cam_name,
               "op": op}
        msg.update(payload)
        self.client.publish("camera/{0}/delta".format(stream.cam_name),
                            json.dumps(msg, separators=(",", ":")), qos=0)

    def emit_keyframe(self, stream: CameraStream, jpeg_path: Path,
                      motion_pix: int, summary: dict):
        if not self.client:
            return
        msg = {"t": iso_now(), "src": "camera", "cam": stream.cam_name,
               "op": "keyframe",
               "jpeg_path": str(jpeg_path), "motion_pix": int(motion_pix),
               "summary": summary}
        self.client.publish("camera/{0}/keyframe".format(stream.cam_name),
                            json.dumps(msg, separators=(",", ":")), qos=1)

    def emit_fall(self, stream: CameraStream, head_y_drop: float,
                  aspect: float, conf: float, bbox):
        msg_topic = "camera/{0}/fall_event".format(stream.cam_name)
        x1, y1, x2, y2 = bbox
        payload = {"t": iso_now(), "src": "camera", "cam": stream.cam_name,
                   "op": "fall_event",
                   "head_y_drop_frac": round(head_y_drop, 3),
                   "pose_aspect": round(aspect, 3),
                   "pose_confidence": round(conf, 3),
                   "bbox": [int(x1), int(y1), int(x2), int(y2)]}
        if self.client:
            self.client.publish(msg_topic,
                                json.dumps(payload, separators=(",", ":")),
                                qos=1)
        LOG.warning("FALL EVENT %s", payload)

    def emit_gait(self, stream: CameraStream, person_id: str, metrics: dict):
        """Publish gait metrics for one person on camera/{cam}/gait."""
        if not self.client:
            return
        payload = {"t": iso_now(), "src": "camera", "cam": stream.cam_name,
                   "op": "gait", "person_id": person_id}
        payload.update(metrics)
        try:
            self.client.publish(
                "camera/{0}/gait".format(stream.cam_name),
                json.dumps(payload, separators=(",", ":")),
                qos=0, retain=False)
            LOG.info("%s: gait %s cadence=%d stride=%dpx asym=%.1f%% n=%d",
                     stream.cam_name, person_id,
                     metrics["cadence_spm"], metrics["stride_length_px"],
                     metrics["swing_asymmetry_pct"], metrics["n_strides"])
        except Exception as e:
            LOG.warning("%s: emit_gait failed: %s", stream.cam_name, e)

    def clear_seg(self, stream: CameraStream):
        """Drop the latest seg mask for this camera so update_mjpeg_frame stops
        compositing it on (no ghost overlay). Also tell MQTT subscribers via
        an empty payload, for any clients still listening to the seg topic."""
        with LATEST_SEG_MASK_LOCK:
            LATEST_SEG_MASK.pop(stream.cam_name, None)
        if not self.client:
            return
        self.client.publish("camera/{0}/seg_mask".format(stream.cam_name),
                            "", qos=0, retain=False)

    def emit_seg(self, stream: CameraStream, frame: np.ndarray):
        """Run DeepLab v3 person-segmentation and publish an RGBA PNG mask
        (green silhouette where person pixels are, transparent elsewhere)
        for the phone to overlay on the live thumbnail.

        Throttled per-stream via SEG_MIN_INTERVAL_S. Only called when a
        person is already detected in the frame, so we don't waste TPU
        cycles on empty scenes.
        """
        if not self.client:
            return
        now = time.monotonic()
        if (now - stream.last_seg_t) < SEG_MIN_INTERVAL_S:
            return
        try:
            seg_in = cv2.resize(frame, (self.seg_w, self.seg_h),
                                interpolation=cv2.INTER_AREA)
            seg_in_rgb = cv2.cvtColor(seg_in, cv2.COLOR_BGR2RGB)
            common.set_input(self.seg, seg_in_rgb)
            self.seg.invoke()
            out = common.output_tensor(self.seg, 0)
            arr = np.asarray(out).squeeze()
            if arr.ndim == 3:
                mask = np.argmax(arr, axis=-1).astype(np.uint8)
            else:
                mask = arr.astype(np.uint8)
            person = (mask == SEG_PERSON_CLASS).astype(np.uint8)
            if person.sum() < 20:  # essentially empty mask — don't publish
                stream.last_seg_t = now
                return
            # Update the COMPOSITE cache so update_mjpeg_frame blends it into
            # the live stream (no MQTT round-trip, no desync with the video).
            person_mjpeg = cv2.resize(
                person * 255, (MJPEG_W, MJPEG_H), interpolation=cv2.INTER_NEAREST,
            )
            with LATEST_SEG_MASK_LOCK:
                LATEST_SEG_MASK[stream.cam_name] = person_mjpeg
            # ALSO publish via MQTT for backward compat (app no longer overlays
            # this — composite is in the JPEG itself).
            person_small = cv2.resize(person, (THUMB_W, THUMB_H),
                                      interpolation=cv2.INTER_NEAREST)
            rgba = np.zeros((THUMB_H, THUMB_W, 4), dtype=np.uint8)
            rgba[..., 1] = person_small * 255            # G
            rgba[..., 3] = person_small * SEG_OVERLAY_ALPHA  # A
            ok, png_buf = cv2.imencode(".png", rgba)
            if not ok:
                return
            b64 = base64.b64encode(png_buf.tobytes()).decode("utf-8")
            self.client.publish(
                "camera/{0}/seg_mask".format(stream.cam_name),
                b64, qos=0, retain=False)
            stream.last_seg_t = now
        except Exception as e:
            LOG.warning("%s: emit_seg failed: %s", stream.cam_name, e)

    def emit_thumb(self, stream: CameraStream, frame: np.ndarray):
        if not self.client:
            return
        now = time.monotonic()
        if (now - stream.last_thumb_t) < THUMB_MIN_INTERVAL_S:
            return
        try:
            small = cv2.resize(frame, (THUMB_W, THUMB_H),
                               interpolation=cv2.INTER_AREA)
            ok, buf = cv2.imencode(".jpg", small,
                                   [int(cv2.IMWRITE_JPEG_QUALITY),
                                    THUMB_JPEG_QUALITY])
            if not ok:
                return
            b64 = base64.b64encode(buf.tobytes()).decode("utf-8")
            self.client.publish("camera/{0}/thumb".format(stream.cam_name),
                                b64, qos=0, retain=False)
            stream.last_thumb_t = now
        except Exception as e:
            LOG.warning("%s: emit_thumb failed: %s", stream.cam_name, e)

    def process_frame(self, stream: CameraStream, frame: np.ndarray):
        h, w = frame.shape[:2]

        det_input = cv2.resize(frame, (self.det_w, self.det_h))
        det_input_rgb = cv2.cvtColor(det_input, cv2.COLOR_BGR2RGB)
        common.set_input(self.det, det_input_rgb)
        self.det.invoke()
        objects = detect.get_objects(self.det, DET_THRESHOLD)

        sx, sy = w / self.det_w, h / self.det_h

        fg_mask = stream.bg.apply(frame)
        motion_pix = int(cv2.countNonZero(fg_mask))

        now = time.monotonic()
        seen_track_ids = set()
        scene_summary = {}
        new_entities = []
        person_bbox = None
        person_score = 0.0
        person_area_cx = 0
        person_area_cy = 0

        for obj in objects:
            cls = PUBLISH_CLASSES.get(obj.id)
            if cls is None:
                continue
            scene_summary[cls] = scene_summary.get(cls, 0) + 1
            bx = obj.bbox
            x1, y1, x2, y2 = (int(bx.xmin * sx), int(bx.ymin * sy),
                              int(bx.xmax * sx), int(bx.ymax * sy))
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2

            if cls == "person":
                area = (x2 - x1) * (y2 - y1)
                if area > person_score:
                    person_score = area
                    person_bbox = (x1, y1, x2, y2)
                    person_area_cx, person_area_cy = cx, cy

            best_tid = None
            best_d = 80
            for tid, tr in stream.tracked.items():
                if tr["cls"] != cls:
                    continue
                d = math.hypot(cx - tr["cx"], cy - tr["cy"])
                if d < best_d:
                    best_d, best_tid = d, tid
            if best_tid is None:
                tid = stream.next_track_id
                stream.next_track_id += 1
                stream.tracked[tid] = {"cls": cls, "cx": cx, "cy": cy,
                                       "last_seen": now}
                entity_name = "{0}_{1}".format(cls, tid)
                self.emit(stream, "add", entity=entity_name, cls=cls,
                          at=[cx, cy], conf=round(obj.score, 3))
                new_entities.append({"id": entity_name, "cls": cls})
            else:
                tr = stream.tracked[best_tid]
                dx, dy = cx - tr["cx"], cy - tr["cy"]
                if math.hypot(dx, dy) > MOVE_THRESHOLD_PX:
                    entity_name = "{0}_{1}".format(cls, best_tid)
                    self.emit(stream, "move", entity=entity_name, cls=cls,
                              at=[cx, cy], delta=[dx, dy],
                              conf=round(obj.score, 3))
                    tr["cx"], tr["cy"] = cx, cy
                tr["last_seen"] = now
                seen_track_ids.add(best_tid)

        # Resolve which track id corresponds to the largest-person bbox we
        # chose for pose. (We picked by area above without yet knowing tid.)
        person_tid = None
        if person_bbox is not None:
            best_d = 1e9
            for tid, tr in stream.tracked.items():
                if tr["cls"] != "person":
                    continue
                d = math.hypot(person_area_cx - tr["cx"],
                               person_area_cy - tr["cy"])
                if d < best_d:
                    best_d, person_tid = d, tid

        gone = [tid for tid, tr in stream.tracked.items()
                if tid not in seen_track_ids
                and (now - tr["last_seen"]) > REMOVE_AFTER_MISS_S]
        for tid in gone:
            tr = stream.tracked.pop(tid)
            stream.gait.pop(tid, None)
            entity_name = "{0}_{1}".format(tr["cls"], tid)
            self.emit(stream, "remove", entity=entity_name, cls=tr["cls"])

        if person_bbox is not None:
            x1, y1, x2, y2 = person_bbox
            pad = 20
            x1c = max(0, x1 - pad); y1c = max(0, y1 - pad)
            x2c = min(w, x2 + pad); y2c = min(h, y2 + pad)
            crop = frame[y1c:y2c, x1c:x2c]
            if crop.size > 0:
                pose_in = cv2.resize(crop, (self.pose_w, self.pose_h))
                pose_in_rgb = cv2.cvtColor(pose_in, cv2.COLOR_BGR2RGB)
                common.set_input(self.pose, pose_in_rgb)
                self.pose.invoke()
                kps = parse_movenet(self.pose)

                # --- Gait: per-person rolling ankle/hip trajectory ----------
                if person_tid is not None:
                    ga = stream.gait.get(person_tid)
                    if ga is None:
                        ga = GaitAnalyzer()
                        stream.gait[person_tid] = ga
                    ga.add(now, kps, person_bbox)
                    metrics = ga.compute(now)
                    if metrics is not None:
                        self.emit_gait(stream,
                                       "person_{0}".format(person_tid),
                                       metrics)

                head_y, head_conf = head_y_score(kps)
                aspect = pose_aspect(kps)
                stream.pose_history.append(
                    (now, head_y, head_conf, aspect, person_bbox))

                if (len(stream.pose_history) == POSE_HISTORY_LEN
                        and (now - stream.last_fall_t) > FALL_COOLDOWN_S):
                    old_head = stream.pose_history[0][1]
                    cur_head = stream.pose_history[-1][1]
                    if (not math.isnan(cur_head)
                            and not math.isnan(old_head)):
                        drop = cur_head - old_head
                    else:
                        drop = 0.0
                    cur_aspect = aspect
                    cur_conf = head_conf
                    if (drop > FALL_HEAD_DROP_FRAC
                            and not math.isnan(cur_aspect)
                            and cur_aspect < FALL_ASPECT_THRESHOLD
                            and cur_conf > FALL_MIN_CONFIDENCE):
                        self.emit_fall(stream, drop, cur_aspect, cur_conf,
                                       person_bbox)
                        stream.last_fall_t = now

        time_trigger     = (now - stream.last_keyframe_t) >= KEYFRAME_TIME_S
        motion_trigger   = motion_pix > KEYFRAME_MOTION_PIX
        semantic_trigger = bool(new_entities)
        if motion_trigger and not (time_trigger or semantic_trigger):
            if (now - stream.last_keyframe_t) < KEYFRAME_COOLDOWN_S:
                self.emit_thumb(stream, frame)
                return
        if ((time_trigger or motion_trigger or semantic_trigger)
                and stream.last_keyframe_t > 0):
            stream.last_keyframe_t = now
            self._write_keyframe(stream, frame, motion_pix, scene_summary)
        elif stream.last_keyframe_t == 0:
            stream.last_keyframe_t = now

        # Person-segmentation overlay (DeepLab) — only when a person is in frame.
        # On person→absent transition, publish empty mask so the silhouette
        # doesn't linger as a ghost on subscribers.
        had_person_now = person_bbox is not None
        if had_person_now:
            self.emit_seg(stream, frame)
        elif stream.had_person_last_frame:
            self.clear_seg(stream)
        stream.had_person_last_frame = had_person_now
        self.emit_thumb(stream, frame)
        # NOTE: MJPEG cache is updated in CameraStream._capture_loop (the
        # capture thread), NOT here — so the live feed serves the camera's
        # native frame rate instead of being throttled by the TPU loop.

    def _write_keyframe(self, stream: CameraStream, frame, motion_pix: int,
                        summary: dict):
        fname = "{0}_{1}_{2}.jpg".format(stream.cam_name, int(time.time()),
                                         uuid.uuid4().hex[:8])
        path = self.keyframe_dir / fname
        cv2.imwrite(str(path), frame,
                    [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY])
        LOG.info("%s: keyframe %s motion=%d summary=%s",
                 stream.cam_name, path.name, motion_pix, summary)
        self.emit_keyframe(stream, path, motion_pix, summary)

    def run(self):
        self.setup_mqtt()
        n = len(self.streams)
        # Start per-camera capture threads (they feed MJPEG at native rate)
        for s in self.streams:
            s.start_capture()
        # Brief warmup so latest_frame is populated before TPU loop runs
        time.sleep(0.5)
        LOG.info("run loop: %d cameras, capture threads up, TPU loop at "
                 "shared rate (expected ~%d FPS per cam after split)",
                 n, max(1, TARGET_FPS // max(1, n)))
        try:
            while self.running:
                for stream in self.streams:
                    if not self.running:
                        break
                    frame = stream.get_latest_frame()
                    if frame is None:
                        time.sleep(0.02)
                        continue
                    t0 = time.monotonic()
                    self.process_frame(stream, frame)
                    if (time.monotonic() - t0) > 0.15:
                        LOG.warning("%s: slow frame %.0f ms",
                                    stream.cam_name,
                                    (time.monotonic() - t0) * 1000)
        finally:
            self.teardown()

    def stop(self, *_a):
        self.running = False


def _parse_camera_specs(args) -> List[Tuple[str, str]]:
    specs = []

    if args.cameras:
        for raw in args.cameras.split(","):
            raw = raw.strip()
            if not raw:
                continue
            if ":" in raw:
                dev, name = raw.split(":", 1)
                specs.append((dev.strip(), name.strip()))
            else:
                specs.append((raw, os.path.basename(raw)))
        return specs

    cam_args = args.camera if isinstance(args.camera, list) else [args.camera]
    cam_args = [c for c in cam_args if c]
    if len(cam_args) > 1 or (len(cam_args) == 1 and ":" in cam_args[0]):
        for raw in cam_args:
            if ":" in raw:
                dev, name = raw.split(":", 1)
                specs.append((dev.strip(), name.strip()))
            else:
                specs.append((raw, os.path.basename(raw)))
        return specs

    if cam_args:
        specs.append((cam_args[0], args.cam_name))
    return specs


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--camera", action="append", default=None,
                    help="Camera device, optionally with :name suffix "
                         "(e.g. /dev/video1:kitchen). Repeatable.")
    ap.add_argument("--cameras", default=None,
                    help="Comma-separated list of dev:name pairs, "
                         "e.g. /dev/video1:kitchen,/dev/video2:livingroom")
    ap.add_argument("--cam-name",    default="kitchen",
                    help="Name for legacy single --camera form")
    ap.add_argument("--broker",      default="localhost")
    ap.add_argument("--mqtt-port",   type=int, default=1883)
    ap.add_argument("--keyframe-dir", default="~/hearth/keyframes")
    ap.add_argument("--model-dir",   default=str(DEFAULT_MODEL_DIR))
    ap.add_argument("--log-level",   default="INFO")
    args = ap.parse_args()

    logging.basicConfig(level=getattr(logging, args.log_level),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    if not args.camera and not args.cameras:
        args.camera = ["/dev/video1"]

    specs = _parse_camera_specs(args)
    if not specs:
        ap.error("no cameras specified")

    streams = []
    for dev, name in specs:
        try:
            streams.append(CameraStream(dev, name))
        except Exception as e:
            for s in streams:
                s.release()
            raise SystemExit("failed to open {0} ({1}): {2}".format(
                dev, name, e))

    agent = CoralCameraAgent(
        streams      = streams,
        broker       = args.broker,
        keyframe_dir = Path(args.keyframe_dir).expanduser(),
        model_dir    = Path(args.model_dir).expanduser(),
        mqtt_port    = args.mqtt_port,
    )
    signal.signal(signal.SIGINT,  agent.stop)
    signal.signal(signal.SIGTERM, agent.stop)
    start_mjpeg_server(MJPEG_PORT)
    agent.run()


if __name__ == "__main__":
    main()
