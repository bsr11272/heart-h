#!/usr/bin/env python3
"""pozyx_bridge.py — Pozyx tag position → MQTT pendant/events.

Bypasses Pozyx's internal doPositioning() (which fails with error 0x0D on
our Anchor v1.4 SKU when geometry/timing isn't perfect) and does the
trilateration ourselves in numpy. We use Pozyx ONLY for direct ranging
from tag → each anchor, which is reliable.

Algorithm: 2D trilateration via linear least squares. Given 3+ anchors at
known (x, y) positions and 3+ measured distances, solve the over-determined
linear system that comes from subtracting pairs of range equations.

Reads anchor positions from /home/mendel/hearth/data/pozyx_anchors.json
(produced by pozyx_save_positions.py). Publishes 2D pendant XYZ at 1-2 Hz.

The Android app's SensorState.apply() already understands the
"pendant"/"position" op shape we publish here.

Run on Coral:
  /home/mendel/hearth/.dep/bin/python services/pozyx_bridge.py
"""
from __future__ import annotations

import argparse
import json
import logging
import signal
import statistics
import time
import math
from collections import deque
from pathlib import Path
from typing import Optional

import numpy as np
import paho.mqtt.client as mqtt
from pypozyx import (PozyxSerial, DeviceRange, POZYX_SUCCESS,
                     POZYX_RANGE_PROTOCOL_FAST)

LOG = logging.getLogger("pozyx_bridge")

TAG_PORT           = "/dev/ttyACM0"
DEFAULT_CFG        = Path("/home/mendel/hearth/data/pozyx_anchors.json")
DEFAULT_BROKER     = "localhost"
DEFAULT_RATE_HZ    = 1.5
POSITION_TOPIC     = "pendant/events"
AVAIL_TOPIC        = "pendant/availability"

# Smoothing: keep last N positions, publish median to suppress outliers.
SMOOTH_WINDOW      = 5
MAX_DISTANCE_MM    = 12000   # reject obviously-bad ranges


def load_anchors(path: Path) -> dict:
    with open(path) as f:
        cfg = json.load(f)
    if "anchors" not in cfg:
        raise ValueError("missing 'anchors' key in {0}".format(path))
    return {int(k, 16): tuple(v) for k, v in cfg["anchors"].items()}


def trilaterate_2d(anchor_pos: list, distances_3d: list, tag_z_mm: float) -> "Optional[tuple]":
    """3+ anchor 2D trilateration via linear least squares.

    anchor_pos    : list of (x, y, z) anchor positions in mm
    distances_3d  : list of measured 3D Euclidean tag-to-anchor distances in mm
                    (Pozyx UWB ranges antenna-to-antenna, which is 3D)
    tag_z_mm      : known tag Z (we trust this since tag is on the Coral whose
                    height we measured)

    The function projects each 3D range to a 2D range in the XY plane:
       r_2d² = r_3d² − (anchor.z − tag.z)²
    then solves 2D trilateration from those. Drops anchors where the
    projection is invalid (r_3d² < dz² means the measured range is shorter
    than the vertical separation, which is impossible — likely a bad sample).

    Returns (x_mm, y_mm) or None if degenerate / too few valid anchors.
    """
    if len(anchor_pos) < 3 or len(distances_3d) != len(anchor_pos):
        return None
    # Project to 2D
    projected = []  # (anchor_xy, r_2d)
    for (ax, ay, az), r3d in zip(anchor_pos, distances_3d):
        dz = float(az - tag_z_mm)
        r3d = float(r3d)
        if r3d * r3d < dz * dz:
            continue   # invalid — drop
        r2d = math.sqrt(r3d * r3d - dz * dz)
        projected.append(((ax, ay), r2d))
    if len(projected) < 3:
        return None

    A_xy = np.array([p[0] for p in projected], dtype=float)
    d = np.array([p[1] for p in projected], dtype=float)
    x0, y0 = A_xy[0]; d0 = d[0]
    rows, rhs = [], []
    # Subtract Eq0 from Eq_i:
    #   2*(xi - x0)*x + 2*(yi - y0)*y = d0² - di² + (xi² - x0²) + (yi² - y0²)
    for i in range(1, len(A_xy)):
        xi, yi = A_xy[i]
        di = d[i]
        rows.append([2 * (xi - x0), 2 * (yi - y0)])
        rhs.append(d0 ** 2 - di ** 2 + xi ** 2 - x0 ** 2 + yi ** 2 - y0 ** 2)
    A_lin = np.array(rows)
    b_lin = np.array(rhs)
    try:
        sol, _, rank, _ = np.linalg.lstsq(A_lin, b_lin, rcond=None)
        if rank < 2:
            return None
        return float(sol[0]), float(sol[1])
    except np.linalg.LinAlgError:
        return None


class PozyxBridge:
    def __init__(self, port: str, cfg_path: Path, broker: str, rate_hz: float):
        self.port = port
        self.cfg_path = cfg_path
        self.broker = broker
        self.period = 1.0 / max(0.1, rate_hz)
        self.running = True
        self.client = None
        self.p = None
        self.anchors = {}      # {id: (x, y, z) mm}
        self.anchor_ids = []
        self.tag_height_mm = 0
        self.recent_positions = deque(maxlen=SMOOTH_WINDOW)

    def setup(self):
        cfg = json.load(open(self.cfg_path))
        self.anchors = {int(k, 16): tuple(v) for k, v in cfg["anchors"].items()}
        self.anchor_ids = list(self.anchors.keys())
        tag_pos = cfg.get("tag_position_mm", [0, 0, 0])
        self.tag_height_mm = int(tag_pos[2])
        # Per-anchor calibration offsets: subtract from each measurement
        # to cancel systematic Pozyx antenna-delay bias.
        raw_cal = cfg.get("calibration_mm", {})
        self.calibration_mm = {int(k, 16): int(v) for k, v in raw_cal.items()}
        LOG.info("loaded %d anchors; tag known Z=%dmm", len(self.anchors), self.tag_height_mm)
        for aid, pos in self.anchors.items():
            cal = self.calibration_mm.get(aid, 0)
            LOG.info("  %s: (%d, %d, %d) mm  cal_offset=%+dmm",
                     hex(aid), pos[0], pos[1], pos[2], cal)

        self.p = PozyxSerial(self.port)
        self.p.setRangingProtocol(POZYX_RANGE_PROTOCOL_FAST)
        time.sleep(0.5)

        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id="pozyx-bridge")
        self.client.will_set(AVAIL_TOPIC, "offline", qos=1, retain=True)
        self.client.connect(self.broker, 1883, keepalive=30)
        self.client.publish(AVAIL_TOPIC, "online", qos=1, retain=True)
        self.client.loop_start()
        LOG.info("connected mqtt %s; publishing to %s", self.broker, POSITION_TOPIC)

    def teardown(self):
        if self.client:
            try:
                self.client.publish(AVAIL_TOPIC, "offline", qos=1, retain=True)
                self.client.loop_stop()
                self.client.disconnect()
            except Exception:
                pass

    def range_all(self) -> "Optional[dict]":
        """Range from tag to each anchor; return {id: distance_mm} with the
        per-anchor calibration offset subtracted. Each anchor gets up to 4
        retries because some anchors (0x605F in our hardware) hover around
        25% per-attempt success. With 4 tries each, joint success approaches
        100%."""
        out = {}
        for aid in self.anchor_ids:
            for attempt in range(4):
                try:
                    r = DeviceRange()
                    s = self.p.doRanging(aid, r)
                    if s == POZYX_SUCCESS and 0 < r.distance < MAX_DISTANCE_MM:
                        cal = self.calibration_mm.get(aid, 0)
                        out[aid] = max(0, r.distance - cal)
                        break
                except Exception:
                    pass
                time.sleep(0.05)
        return out if len(out) >= 3 else None

    def smoothed(self, x: float, y: float) -> "tuple":
        """Median over recent SMOOTH_WINDOW positions to reject outliers."""
        self.recent_positions.append((x, y))
        if len(self.recent_positions) < 3:
            return x, y
        xs = sorted(p[0] for p in self.recent_positions)
        ys = sorted(p[1] for p in self.recent_positions)
        return statistics.median(xs), statistics.median(ys)

    def run(self):
        self.setup()
        LOG.info("position loop @ %.1f Hz (custom 2D trilateration)", 1.0 / self.period)
        next_t = time.monotonic()
        n_published = 0
        n_failed = 0
        try:
            while self.running:
                now = time.monotonic()
                if now < next_t:
                    time.sleep(min(0.05, next_t - now))
                    continue
                next_t = now + self.period

                distances_by_id = self.range_all()
                if not distances_by_id:
                    n_failed += 1
                    if n_failed % 10 == 1:
                        LOG.warning("range_all returned <3 anchors (#%d)", n_failed)
                    continue

                # Build aligned lists in anchor_ids order
                anchor_pos = [self.anchors[aid] for aid in distances_by_id.keys()]
                distances  = [distances_by_id[aid] for aid in distances_by_id.keys()]
                result = trilaterate_2d(anchor_pos, distances, self.tag_height_mm)
                if result is None:
                    n_failed += 1
                    continue

                x_mm, y_mm = result
                sx, sy = self.smoothed(x_mm, y_mm)

                payload = {
                    "src": "pendant",
                    "op": "position",
                    "t": time.time(),
                    "xyz_m": [sx / 1000.0, sy / 1000.0, self.tag_height_mm / 1000.0],
                    "raw_xy_m": [x_mm / 1000.0, y_mm / 1000.0],
                    "ranges_mm": {hex(k): v for k, v in distances_by_id.items()},
                }
                self.client.publish(
                    POSITION_TOPIC,
                    json.dumps(payload, separators=(",", ":")),
                    qos=0,
                )
                n_published += 1
                if n_published % 20 == 1:
                    LOG.info("pos #%d: (%.2f, %.2f) m  (fails so far: %d)",
                             n_published, sx / 1000.0, sy / 1000.0, n_failed)
        finally:
            self.teardown()

    def stop(self, *_a):
        self.running = False


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--port",   default=TAG_PORT)
    ap.add_argument("--config", default=str(DEFAULT_CFG))
    ap.add_argument("--broker", default=DEFAULT_BROKER)
    ap.add_argument("--rate",   type=float, default=DEFAULT_RATE_HZ)
    ap.add_argument("--log-level", default="INFO")
    args = ap.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    b = PozyxBridge(args.port, Path(args.config), args.broker, args.rate)
    signal.signal(signal.SIGINT,  b.stop)
    signal.signal(signal.SIGTERM, b.stop)
    b.run()


if __name__ == "__main__":
    main()
