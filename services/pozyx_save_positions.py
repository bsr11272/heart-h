#!/usr/bin/env python3
"""pozyx_save_positions.py — validate user-provided anchor positions and save.

Takes the 4 positions the user measured (inches), converts to mm, measures
LIVE tag→anchor ranges, compares measured vs predicted (Euclidean from the
priors), reports residuals + a verdict, and saves the final config that
pozyx_bridge.py consumes.

Run on Coral:
  /home/mendel/hearth/.dep/bin/python services/pozyx_save_positions.py
"""
from __future__ import annotations

import json
import math
import os
import statistics
import time

from pypozyx import (PozyxSerial, DeviceRange, POZYX_SUCCESS,
                     POZYX_RANGE_PROTOCOL_FAST)

TAG_PORT     = "/dev/ttyACM0"
INCH_TO_MM   = 25.4
CONFIG_PATH  = "/home/mendel/hearth/data/pozyx_anchors.json"
TAG_ID       = 0x6e38
ANCHOR_IDS   = [0x6000, 0x6167, 0x605F]
N_SAMPLES    = 12

# User-measured positions, INCHES.
POSITIONS_IN = {
    0x6167: (0.0,   26.9,   84.3),
    0x605F: (66.6,  125.7,  93.3),
    0x6000: (92.3,  26.1,   99.8),   # "the last one on the wall"
    0x6e38: (82.3,  72.85,  15.0),   # tag, on Coral via USB hub
}


def measure_one(p, target_id, n=N_SAMPLES):
    """Range to one anchor n times; return (mean_mm, std_mm, success_n)."""
    samples = []
    for _ in range(n):
        try:
            r = DeviceRange()
            if p.doRanging(target_id, r) == POZYX_SUCCESS and 0 < r.distance < 30000:
                samples.append(r.distance)
        except Exception:
            pass
        time.sleep(0.12)
    if not samples:
        return None
    mean = statistics.mean(samples)
    stdv = statistics.stdev(samples) if len(samples) > 1 else 0.0
    return mean, stdv, len(samples)


def euclidean(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def main():
    p = PozyxSerial(TAG_PORT)
    # Defensive: tag was set to PRECISION earlier this session; reset to FAST.
    p.setRangingProtocol(POZYX_RANGE_PROTOCOL_FAST)
    time.sleep(0.5)

    # Convert inches → mm
    pos_mm = {aid: tuple(v * INCH_TO_MM for v in xyz)
              for aid, xyz in POSITIONS_IN.items()}
    tag_mm = pos_mm[TAG_ID]

    print("=" * 70)
    print("  Hearth Pozyx anchor save + validate")
    print("=" * 70)
    print(f"  Units in: inches  →  saved as: mm (Pozyx native)")
    print(f"  Tag (0x6e38) at: "
          f"({tag_mm[0]:.0f}, {tag_mm[1]:.0f}, {tag_mm[2]:.0f}) mm")
    print()
    print(f"  Anchors (user-measured priors, mm):")
    for aid in ANCHOR_IDS:
        x, y, z = pos_mm[aid]
        print(f"    {hex(aid)}: ({x:>7.0f}, {y:>7.0f}, {z:>7.0f})  "
              f"= ({x/1000:.2f}, {y/1000:.2f}, {z/1000:.2f}) m")
    print()
    print("Measuring live ranges (this takes ~10 sec)...")
    print()

    # Collect live ranges
    results = {}
    for aid in ANCHOR_IDS:
        m = measure_one(p, aid)
        results[aid] = m
        if m is None:
            print(f"  {hex(aid)}: NO RANGE — anchor offline?")
        else:
            print(f"  {hex(aid)}: measured {m[0]:>6.0f}±{m[1]:>4.0f}mm ({m[2]}/{N_SAMPLES} ok)")
    print()

    if any(r is None for r in results.values()):
        print("ERROR: not all anchors responded. Aborting.")
        return 1

    # Predicted vs measured table
    print(f"{'Anchor':<10} {'predicted':>10} {'measured':>10} {'residual':>10} {'verdict'}")
    print("-" * 70)
    residuals = {}
    for aid in ANCHOR_IDS:
        predicted = euclidean(tag_mm, pos_mm[aid])
        measured = results[aid][0]
        delta = measured - predicted
        if abs(delta) < 300:
            v = "✓ within Pozyx noise (~30cm)"
        elif abs(delta) < 600:
            v = "△ marginal (~30-60cm)"
        else:
            v = "✗ large (>60cm)"
        print(f"{hex(aid):<10} {predicted:>9.0f}mm {measured:>9.0f}mm "
              f"{delta:>+8.0f}mm  {v}")
        residuals[aid] = (predicted, measured, delta)
    print()

    max_err = max(abs(r[2]) for r in residuals.values())
    mean_err = sum(r[2] for r in residuals.values()) / len(residuals)

    print(f"  max |residual| = {max_err:>4.0f}mm")
    print(f"  mean residual  = {mean_err:>+5.0f}mm  (positive = measured longer than predicted; "
          f"~typical Pozyx antenna delay 100-300mm)")
    print()

    if max_err < 300:
        verdict = "GOOD — positions consistent with measurements. Saving."
    elif max_err < 600:
        verdict = ("MARGINAL — saving anyway; expect ~30-60cm pendant position error in app. "
                   "Re-measure with a tape if you want better.")
    else:
        verdict = ("POOR — large errors. Saving but pendant XYZ may be unreliable. "
                   "Consider re-measuring the anchor(s) with the largest residual.")
    print(verdict)
    print()

    # Per-anchor calibration offset = (measured - predicted). The bridge
    # subtracts this from each measurement before trilateration to cancel
    # the systematic per-anchor antenna delay.
    calibration_mm = {hex(aid): round(residuals[aid][2]) for aid in ANCHOR_IDS}
    print()
    print("Per-anchor calibration offsets (mm to SUBTRACT from each measurement):")
    for aid_hex, off in calibration_mm.items():
        print(f"  {aid_hex}: {off:+d} mm")
    print()

    # Save final config
    os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
    payload = {
        "anchors": {hex(aid): [int(round(v)) for v in pos_mm[aid]]
                    for aid in ANCHOR_IDS},
        "calibration_mm": calibration_mm,
        "tag_id": hex(TAG_ID),
        "tag_position_mm": [int(round(v)) for v in tag_mm],
        "input_units": "inches",
        "measurements": {
            hex(k): {"avg_mm": round(v[0]), "std_mm": round(v[1]), "n_samples": v[2]}
            for k, v in results.items()
        },
        "residuals_mm": {hex(k): round(v[2]) for k, v in residuals.items()},
        "max_error_mm": round(max_err),
        "mean_error_mm": round(mean_err),
        "verdict": verdict,
        "captured_at": time.time(),
    }
    with open(CONFIG_PATH, "w") as f:
        json.dump(payload, f, indent=2)
    print(f"Saved: {CONFIG_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
