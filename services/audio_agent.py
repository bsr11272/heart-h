#!/usr/bin/env python3
"""audio_agent.py -- Coral Edge TPU YamNet audio-event detector for Hearth.

Captures mono 16 kHz PCM from a USB camera's built-in microphone (via
``arecord`` piping to stdin) and runs the quantised YamNet classifier on
the Edge TPU. Filters the 521-class AudioSet output down to a curated
aging-in-place watchlist (glass breaking, screaming, smoke alarm,
doorbell, water running, ...) and publishes events to MQTT for the
graph daemon / phone app to consume.

Design notes
------------
* The Coral Dev Board has ONE Edge TPU and libedgetpu is single-tenant
  per process. ``coral_camera_agent.py`` already owns its own
  interpreters in its own process; this agent runs in a separate
  process and opens its own YamNet interpreter. pycoral / libedgetpu
  serialises TPU access across processes via the kernel driver, so the
  two share the TPU fine but each inference is mutually exclusive --
  expect a few-millisecond stall on the camera side when YamNet runs.
  YamNet inference is cheap (~6 ms quantised) and we only run it
  ~2 Hz, so contention is negligible.
* No new pip deps. ``arecord`` (alsa-utils) is already on Mendel. We
  spawn it as a subprocess, read raw int16 PCM from stdout in fixed
  31200-byte chunks (15600 samples * 2 bytes), feed each chunk to
  YamNet, dequantise the uint8 output, pick the top class, threshold,
  deduplicate, and publish.
* Hop = 500 ms (half-window overlap) so a transient event near a
  window boundary still gets caught by the next window.

Run::

    python audio_agent.py --source plughw:1,0 --source-name cam1 \\
        --broker localhost

Topics::

    audio/{source}/availability   QoS=1 retain=true  "online" / "offline"
    audio/{source}/event          QoS=0 retain=false JSON (see README)
"""
from __future__ import annotations

import argparse
import csv
import json
import logging
import os
import signal
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np
import paho.mqtt.client as mqtt

# CPU YamNet via plain tflite_runtime. The Coral's pre-installed
# tflite_runtime is 2.5 (op range up to ~code 120) but the current
# YamNet uses op 131, so we run inside a dedicated venv with
# tflite-runtime 2.13 + numpy 1.26 (built fresh in /home/mendel/hearth/.dep-audio).
# CPU inference is ~180 ms; at 2 Hz hop that's 36% of one core, fine.
import tflite_runtime.interpreter as tflite

LOG = logging.getLogger("audio_agent")

SAMPLE_RATE         = 16000
WINDOW_SAMPLES      = 15600
WINDOW_BYTES        = WINDOW_SAMPLES * 2
HOP_SAMPLES         = 8000
HOP_BYTES           = HOP_SAMPLES * 2
WINDOW_MS           = int(round(WINDOW_SAMPLES * 1000.0 / SAMPLE_RATE))

# CPU YamNet from TF examples / Coral RPi audio-classification example.
# Stable Google Storage URLs (verified working) — pre-staged on Coral by
# the install step; we just check that the files exist.
YAMNET_MODEL_FILE  = "yamnet_cpu.tflite"
YAMNET_LABELS_FILE = "yamnet_label_list.txt"
DEFAULT_MODEL_DIR = Path("~/hearth/models").expanduser()

# Watchlist: lowercased substrings matched against YamNet display names.
WATCHLIST: List[Tuple[str, str]] = [
    # ALERT
    ("glass",            "alert"),
    ("shatter",          "alert"),
    ("smoke detector",   "alert"),
    ("smoke alarm",      "alert"),
    ("fire alarm",       "alert"),
    ("carbon monoxide",  "alert"),
    ("screaming",        "alert"),
    ("scream",           "alert"),
    ("yell",             "alert"),
    ("shout",            "alert"),
    ("gunshot",          "alert"),
    ("explosion",        "alert"),
    # WARN
    ("crying",           "warn"),
    ("sobbing",          "warn"),
    ("groan",            "warn"),
    ("wail",             "warn"),
    ("baby cry",         "warn"),
    ("infant cry",       "warn"),
    ("thump",            "warn"),
    ("thud",             "warn"),
    ("slam",             "warn"),
    # INFO
    ("doorbell",         "info"),
    ("ding-dong",        "info"),
    ("knock",            "info"),
    ("telephone",        "info"),
    ("ringtone",         "info"),
    ("alarm clock",      "info"),
    ("microwave",        "info"),
    ("blender",          "info"),
    ("vacuum cleaner",   "info"),
    ("water tap",        "info"),
    ("faucet",           "info"),
    ("sink (filling",    "info"),
    ("running water",    "info"),
    ("toilet flush",     "info"),
    ("television",       "info"),
    ("radio",            "info"),
    ("dog",              "info"),
    ("cat",              "info"),
]

NOISY_LABELS = {
    "speech", "male speech, man speaking", "female speech, woman speaking",
    "conversation", "narration, monologue", "music", "musical instrument",
    "silence", "inside, small room", "inside, large room or hall",
    "inside, public space", "outside, urban or manmade",
    "outside, rural or natural", "background noise", "white noise",
    "pink noise", "noise", "sound effect",
}


def iso_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def ensure_models(dir_path: Path) -> Dict[str, Path]:
    """Verify pre-staged YamNet files exist. We don't auto-download because
    the Coral's network can be flaky and the model URL set has shifted over
    time; install_audio_agent.sh handles the one-time download."""
    model = dir_path / YAMNET_MODEL_FILE
    labels = dir_path / YAMNET_LABELS_FILE
    missing = [str(p) for p in (model, labels) if not p.exists() or p.stat().st_size == 0]
    if missing:
        raise FileNotFoundError(
            "missing YamNet files: {}. Pre-stage by running install_audio_agent.sh.".format(missing)
        )
    return {"yamnet": model, "labels": labels}


def load_labels(path: Path) -> List[str]:
    """Load one-label-per-line text format (YamNet's bundled metadata)."""
    with open(str(path), "r") as f:
        labels = [line.strip() for line in f if line.strip()]
    LOG.info("loaded %d YamNet labels from %s", len(labels), path)
    return labels


def classify_severity(label: str) -> Optional[str]:
    low = label.lower()
    for needle, sev in WATCHLIST:
        if needle in low:
            return sev
    return None


class AudioCapture:
    def __init__(self, alsa_device: str, sample_rate: int = SAMPLE_RATE):
        self.alsa_device = alsa_device
        self.sample_rate = sample_rate
        self.proc: Optional[subprocess.Popen] = None

    def start(self) -> None:
        cmd = [
            "arecord", "-q",
            "-D", self.alsa_device,
            "-f", "S16_LE",
            "-c", "1",
            "-r", str(self.sample_rate),
            "-t", "raw",
        ]
        LOG.info("arecord: %s", " ".join(cmd))
        self.proc = subprocess.Popen(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0,
        )

    def read_exact(self, nbytes: int) -> Optional[bytes]:
        if self.proc is None or self.proc.stdout is None:
            return None
        buf = bytearray()
        remaining = nbytes
        while remaining > 0:
            chunk = self.proc.stdout.read(remaining)
            if not chunk:
                stderr_tail = b""
                if self.proc.stderr is not None:
                    try:
                        stderr_tail = self.proc.stderr.read() or b""
                    except Exception:
                        pass
                LOG.error("arecord ended early (rc=%s) stderr=%r",
                          self.proc.poll(), stderr_tail[:400])
                return None
            buf.extend(chunk)
            remaining -= len(chunk)
        return bytes(buf)

    def stop(self) -> None:
        if self.proc is not None:
            try:
                self.proc.terminate()
                try:
                    self.proc.wait(timeout=2.0)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
            except Exception as e:
                LOG.warning("arecord stop failed: %s", e)
            self.proc = None


class YamNetCpu:
    """CPU-only YamNet via tflite_runtime. The model takes a 15600-sample
    float32 waveform in [-1, 1] and returns 521 class probabilities directly
    (no softmax / dequant needed). ~180 ms / call with 4 threads on the
    Coral A53 — plenty of headroom at our 2 Hz hop."""

    def __init__(self, model_path: Path, num_threads: int = 4):
        self.interpreter = tflite.Interpreter(
            model_path=str(model_path), num_threads=num_threads,
        )
        self.interpreter.allocate_tensors()
        self.input_details = self.interpreter.get_input_details()[0]
        self.output_details = self.interpreter.get_output_details()[0]
        in_shape = self.input_details["shape"]
        LOG.info("YamNet CPU input shape=%s dtype=%s  output shape=%s dtype=%s",
                 list(in_shape), self.input_details["dtype"].__name__,
                 list(self.output_details["shape"]),
                 self.output_details["dtype"].__name__)
        self.in_len = int(in_shape[-1])
        if self.in_len != WINDOW_SAMPLES:
            LOG.warning("model expects %d samples per window; agent feeds %d",
                        self.in_len, WINDOW_SAMPLES)

    def infer(self, pcm_int16: np.ndarray) -> np.ndarray:
        if pcm_int16.shape[0] != self.in_len:
            if pcm_int16.shape[0] < self.in_len:
                pad = np.zeros(self.in_len - pcm_int16.shape[0], dtype=np.int16)
                pcm_int16 = np.concatenate([pcm_int16, pad])
            else:
                pcm_int16 = pcm_int16[:self.in_len]
        x = (pcm_int16.astype(np.float32) / 32768.0).reshape(
            self.input_details["shape"]
        )
        self.interpreter.set_tensor(self.input_details["index"], x)
        self.interpreter.invoke()
        raw = self.interpreter.get_tensor(self.output_details["index"])
        return np.asarray(raw).squeeze().astype(np.float32)


class AudioAgent:
    def __init__(self, source, source_name, broker, mqtt_port, model_dir,
                 threshold, cooldown_sec):
        self.source       = source
        self.source_name  = source_name
        self.broker       = broker
        self.mqtt_port    = mqtt_port
        self.threshold    = float(threshold)
        self.cooldown_sec = float(cooldown_sec)
        models = ensure_models(model_dir)
        self.labels = load_labels(models["labels"])
        self.yamnet = YamNetCpu(models["yamnet"])
        self.capture = AudioCapture(source)
        self.client: Optional[mqtt.Client] = None
        self.running = True
        self.last_emit: Dict[str, float] = {}

    def _avail_topic(self) -> str:
        return "audio/{0}/availability".format(self.source_name)

    def _event_topic(self) -> str:
        return "audio/{0}/event".format(self.source_name)

    def setup_mqtt(self) -> None:
        self.client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2,
            client_id="audio-{0}".format(self.source_name),
        )
        self.client.will_set(self._avail_topic(), "offline", qos=1, retain=True)
        self.client.connect(self.broker, self.mqtt_port, keepalive=30)
        self.client.publish(self._avail_topic(), "online", qos=1, retain=True)
        self.client.loop_start()
        LOG.info("mqtt: %s:%d topic=%s",
                 self.broker, self.mqtt_port, self._event_topic())

    def teardown(self) -> None:
        LOG.info("shutdown")
        if self.client is not None:
            try:
                self.client.publish(self._avail_topic(), "offline",
                                    qos=1, retain=True)
            except Exception as e:
                LOG.warning("offline publish failed: %s", e)
            self.client.loop_stop()
            self.client.disconnect()
            self.client = None
        self.capture.stop()

    def emit_event(self, label, class_id, confidence, severity, top3):
        if self.client is None:
            return
        payload = {
            "t": iso_now(),
            "src": "audio",
            "source": self.source_name,
            "op": "event",
            "label": label,
            "class_id": int(class_id),
            "confidence": round(float(confidence), 3),
            "severity": severity,
            "top_3": [[n, round(float(p), 3)] for n, p in top3],
            "window_ms": WINDOW_MS,
        }
        self.client.publish(self._event_topic(),
                            json.dumps(payload, separators=(",", ":")),
                            qos=0, retain=False)
        log_fn = LOG.warning if severity == "alert" else LOG.info
        log_fn("EVENT %s severity=%s conf=%.2f top3=%s",
               label, severity, confidence,
               [(n, round(p, 2)) for n, p in top3])

    def _consider(self, probs: np.ndarray) -> None:
        order = np.argsort(probs)[::-1]
        top_ids = order[:3].tolist()
        top3 = []
        for cid in top_ids:
            name = self.labels[cid] if cid < len(self.labels) else "class_{0}".format(cid)
            top3.append((name, float(probs[cid])))
        top_id = int(top_ids[0])
        top_name = top3[0][0]
        top_conf = float(top3[0][1])
        # Heartbeat: log top-1 every ~10s so operators can verify YamNet
        # is actually classifying even when nothing crosses the watchlist.
        now_mono = time.monotonic()
        if now_mono - getattr(self, "_last_hb_t", 0.0) > 10.0:
            LOG.info("heartbeat top1=%s conf=%.2f (top3=%s)", top_name, top_conf,
                     [(n, round(p, 2)) for n, p in top3])
            self._last_hb_t = now_mono
        chosen_id, chosen_name, chosen_conf = top_id, top_name, top_conf
        sev = classify_severity(chosen_name)
        if (chosen_name.lower() in NOISY_LABELS) or sev is None:
            for cid in top_ids[1:]:
                name = self.labels[cid] if cid < len(self.labels) else ""
                conf = float(probs[cid])
                cand_sev = classify_severity(name)
                if cand_sev is not None and conf >= self.threshold:
                    chosen_id, chosen_name, chosen_conf = int(cid), name, conf
                    sev = cand_sev
                    break
        if sev is None:
            return
        if chosen_conf < self.threshold:
            return
        now = time.monotonic()
        last = self.last_emit.get(chosen_name, 0.0)
        if (now - last) < self.cooldown_sec:
            return
        self.last_emit[chosen_name] = now
        self.emit_event(chosen_name, chosen_id, chosen_conf, sev, top3)

    def run(self) -> None:
        self.setup_mqtt()
        self.capture.start()
        raw = self.capture.read_exact(WINDOW_BYTES)
        if raw is None:
            LOG.error("could not read initial window from %s", self.source)
            self.teardown()
            return
        buf = np.frombuffer(raw, dtype=np.int16).copy()
        try:
            probs = self.yamnet.infer(buf)
            self._consider(probs)
        except Exception as e:
            LOG.exception("initial inference failed: %s", e)
        while self.running:
            chunk = self.capture.read_exact(HOP_BYTES)
            if chunk is None:
                LOG.warning("audio stream ended; exiting")
                break
            hop = np.frombuffer(chunk, dtype=np.int16)
            buf = np.concatenate([buf[HOP_SAMPLES:], hop])
            if buf.shape[0] != WINDOW_SAMPLES:
                continue
            try:
                probs = self.yamnet.infer(buf)
            except Exception as e:
                LOG.warning("inference failed: %s", e)
                continue
            self._consider(probs)
        self.teardown()

    def stop(self, *_a) -> None:
        self.running = False


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--source", default="plughw:1,0",
                    help="ALSA capture device (see `arecord -L`)")
    ap.add_argument("--source-name", default="cam1",
                    help="Logical name used in MQTT topic + payloads")
    ap.add_argument("--broker", default="localhost")
    ap.add_argument("--mqtt-port", type=int, default=1883)
    ap.add_argument("--model-dir", default=str(DEFAULT_MODEL_DIR))
    ap.add_argument("--threshold", type=float, default=0.40)
    ap.add_argument("--cooldown-sec", type=float, default=10.0)
    ap.add_argument("--log-level", default="INFO")
    args = ap.parse_args()
    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    agent = AudioAgent(
        source       = args.source,
        source_name  = args.source_name,
        broker       = args.broker,
        mqtt_port    = args.mqtt_port,
        model_dir    = Path(args.model_dir).expanduser(),
        threshold    = args.threshold,
        cooldown_sec = args.cooldown_sec,
    )
    signal.signal(signal.SIGINT,  agent.stop)
    signal.signal(signal.SIGTERM, agent.stop)
    try:
        agent.run()
    except Exception as e:
        LOG.exception("fatal: %s", e)
        sys.exit(1)


if __name__ == "__main__":
    main()
