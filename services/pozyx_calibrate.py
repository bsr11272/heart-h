#!/usr/bin/env python3
"""pozyx_calibrate.py — walking calibration with LED feedback on the probe.

Touch-detection runs entirely on DIRECT ranging from the Coral tag (which is
the only reliable path on this hardware):
  - tag → probe        (direct)
  - tag → each anchor  (direct)
A "touch" is detected when |range(tag,probe) - range(tag,anchorX)| < TOUCH_MM
for several consecutive samples — meaning probe is at the same distance from
tag as anchor X, which only happens when probe is on top of anchor X.

LED feedback on the PROBE is sent via UWB remote LED commands from the tag:
  RED  (LED 1) blinking  → searching, no touch
  GREEN (LED 3) solid    → touch confirmed, holding (collecting data)

During the "touching" hold, we also fire remote anchor→probe ranging attempts
(unreliable on this hardware but we capture what gets through). After all 3
anchors are touched, we solve for 2D positions via cosine-rule trilateration.

Run on Coral:
  /home/mendel/hearth/.dep/bin/python services/pozyx_calibrate.py
"""
from __future__ import annotations

import json
import math
import os
import time
from collections import defaultdict

from pypozyx import (PozyxSerial, DeviceRange, POZYX_SUCCESS,
                     POZYX_RANGE_PROTOCOL_PRECISION)

TAG_PORT     = "/dev/ttyACM0"
PROBE_ID     = 0x6165
ANCHOR_IDS   = [0x6000, 0x6167, 0x605F]
CONFIG_PATH  = "/home/mendel/hearth/data/pozyx_anchors.json"

TOUCH_MM         = 300   # |Δrange| ≤ 30cm = "probe is on top of anchor"
TOUCH_HOLD_SEC   = 3.0   # must persist this long to confirm
LOOP_PERIOD_SEC  = 0.4   # ~2.5 Hz polling — gentle on the radio
MAX_RUNTIME_SEC  = 180

# Pozyx Creator shield LEDs: 1=red (top), 2=red, 3=green, 4=yellow.
# We use LED1 = "searching" indicator, LED3 = "touch confirmed".
LED_RED   = 1
LED_GREEN = 3


def safe_range(p, target_id, remote_id=None):
    """Best-effort ranging; swallows pypozyx struct.error etc."""
    try:
        r = DeviceRange()
        s = p.doRanging(target_id, r, remote_id=remote_id)
        if s == POZYX_SUCCESS and 0 < r.distance < 30000:
            return r.distance
    except Exception:
        return None
    return None


def safe_set_led(p, led_num, on, remote_id=None):
    """Best-effort LED control. Remote LED commands can fail silently
    over weak UWB links — we just don't crash."""
    try:
        p.setLed(led_num, on, remote_id=remote_id)
    except Exception:
        pass


def update_probe_led(p, touching, red_phase):
    """Drive probe's LEDs to indicate state."""
    if touching:
        # GREEN solid, red off
        safe_set_led(p, LED_RED, False, remote_id=PROBE_ID)
        safe_set_led(p, LED_GREEN, True, remote_id=PROBE_ID)
    else:
        # RED blinking, green off
        safe_set_led(p, LED_GREEN, False, remote_id=PROBE_ID)
        safe_set_led(p, LED_RED, red_phase, remote_id=PROBE_ID)


def main():
    p = PozyxSerial(TAG_PORT)
    p.setRangingProtocol(POZYX_RANGE_PROTOCOL_PRECISION)

    print("=" * 60)
    print(" Hearth UWB walking calibration (LED-feedback variant)")
    print("=" * 60)
    print(f" Tag:    0x6e38 (Coral)")
    print(f" Probe:  {hex(PROBE_ID)} — watch its LEDs:")
    print(f"         RED  blinking = searching")
    print(f"         GREEN solid   = touch confirmed, hold for 3s")
    print(f" Anchors: {[hex(a) for a in ANCHOR_IDS]}")
    print(f" Touch threshold: |Δrange| ≤ {TOUCH_MM}mm")
    print()
    print("Walk the probe to each anchor, hold against it until GREEN.")
    print("Auto-stops once all 3 anchors confirmed.\n")
    for n in range(5, 0, -1):
        print(f"  Starting in {n}s...   ", end="\r", flush=True)
        time.sleep(1)
    print("  *** GO ***                                ")
    print()

    # State
    captured = {a: [] for a in ANCHOR_IDS}      # {anchor: [(self_range, {other: range})]}
    confirmed = {a: False for a in ANCHOR_IDS}  # has this anchor been touched-and-confirmed?
    current_touch = None
    touch_start_t = 0.0
    red_phase = True
    last_log_t = 0.0
    last_led_state = None

    deadline = time.monotonic() + MAX_RUNTIME_SEC

    while time.monotonic() < deadline:
        loop_start = time.monotonic()

        # --- Direct ranges from tag (reliable path) ---
        r_probe = safe_range(p, PROBE_ID)
        r_anchors = {a: safe_range(p, a) for a in ANCHOR_IDS}

        # --- Detect touch ---
        touched_now = None
        if r_probe is not None:
            deltas = {a: abs(r_probe - r_anchors[a])
                      for a in ANCHOR_IDS if r_anchors[a] is not None}
            if deltas:
                closest, min_delta = min(deltas.items(), key=lambda kv: kv[1])
                if min_delta < TOUCH_MM:
                    touched_now = closest

        # --- Touch state machine ---
        if touched_now is not None:
            if current_touch == touched_now:
                hold = time.monotonic() - touch_start_t
                if hold >= TOUCH_HOLD_SEC and not confirmed[touched_now]:
                    confirmed[touched_now] = True
                    print(f"\n  ✓ CONFIRMED touch on {hex(touched_now)} "
                          f"({hold:.1f}s hold)")
            else:
                current_touch = touched_now
                touch_start_t = time.monotonic()
                print(f"\n  ~ contact with {hex(current_touch)}; hold steady...")
        else:
            if current_touch is not None:
                hold = time.monotonic() - touch_start_t
                if hold > 0.5 and not confirmed[current_touch]:
                    print(f"\n  ✗ released {hex(current_touch)} too soon ({hold:.1f}s)")
            current_touch = None

        # --- LED feedback (toggle red phase each iter for blink effect) ---
        is_touching_for_led = (current_touch is not None
                               and (time.monotonic() - touch_start_t) >= 0.3)
        led_state = "GREEN" if is_touching_for_led else f"RED-{int(red_phase)}"
        if led_state != last_led_state:
            update_probe_led(p, is_touching_for_led, red_phase)
            last_led_state = led_state
        red_phase = not red_phase

        # --- During confirmed touch, opportunistically collect remote ranges ---
        if current_touch is not None and \
                (time.monotonic() - touch_start_t) >= TOUCH_HOLD_SEC:
            others = {}
            for measurer in ANCHOR_IDS:
                if measurer == current_touch:
                    continue
                # Try a few times — remote ranging is flaky.
                for _ in range(2):
                    d = safe_range(p, PROBE_ID, remote_id=measurer)
                    if d is not None:
                        others[measurer] = d
                        break
            if others and r_anchors[current_touch] is not None:
                captured[current_touch].append((r_anchors[current_touch], others))

        # --- Status log ---
        now = time.monotonic()
        if now - last_log_t > 1.5:
            cstr = ", ".join(
                f"{hex(a)}={'OK' if confirmed[a] else ('hold' if current_touch == a else '..')}"
                for a in ANCHOR_IDS
            )
            r_str = f"r_probe={r_probe}mm" if r_probe else "r_probe=?"
            print(f"   [{int(now - (deadline - MAX_RUNTIME_SEC))}s] {cstr} | {r_str}")
            last_log_t = now

        # Stop when all 3 confirmed
        if all(confirmed.values()):
            print("\n*** ALL 3 ANCHORS CONFIRMED ***")
            break

        # Pace the loop
        elapsed = time.monotonic() - loop_start
        if elapsed < LOOP_PERIOD_SEC:
            time.sleep(LOOP_PERIOD_SEC - elapsed)

    # Turn off LEDs
    safe_set_led(p, LED_RED, False, remote_id=PROBE_ID)
    safe_set_led(p, LED_GREEN, False, remote_id=PROBE_ID)

    # --- Compute positions ---
    print()
    pair_samples = defaultdict(list)
    for touched, snaps in captured.items():
        for (_, others) in snaps:
            for measurer, d in others.items():
                key = tuple(sorted([touched, measurer]))
                pair_samples[key].append(d)
    pair_avg = {k: sum(v) / len(v) for k, v in pair_samples.items() if v}

    print("Pairwise anchor distances (from remote ranging captured during touches):")
    for (a, b), d in pair_avg.items():
        print(f"  {hex(a)} <-> {hex(b)}: {d:.0f}mm  (n={len(pair_samples[(a, b)])})")

    if len(pair_avg) < 3:
        print(f"\nERROR: only {len(pair_avg)} of 3 pairwise distances captured.")
        print("Remote ranging is unreliable on this hardware. Falling back: ")
        print("you'll need to manually measure the 3 anchor positions.")
        print()
        print("Summary of what WE DO know (direct ranges from Coral tag at last snapshot):")
        for a in ANCHOR_IDS:
            if r_anchors[a] is not None:
                print(f"  tag → {hex(a)}: {r_anchors[a]}mm")
        return 1

    # Solve: A=(0,0), B=(d_AB,0), C=(x,y) y>=0
    A, B, C = ANCHOR_IDS[0], ANCHOR_IDS[1], ANCHOR_IDS[2]
    d_AB = pair_avg[tuple(sorted([A, B]))]
    d_AC = pair_avg[tuple(sorted([A, C]))]
    d_BC = pair_avg[tuple(sorted([B, C]))]

    x = (d_AB ** 2 + d_AC ** 2 - d_BC ** 2) / (2.0 * d_AB)
    y_sq = d_AC ** 2 - x ** 2
    y = math.sqrt(y_sq) if y_sq > 0 else 0.0

    positions = {
        hex(A): [0, 0, 0],
        hex(B): [int(round(d_AB)), 0, 0],
        hex(C): [int(round(x)), int(round(y)), 0],
    }
    print("\nComputed 2D positions (mm):")
    for aid, pos in positions.items():
        print(f"  {aid}: ({pos[0]}, {pos[1]}, {pos[2]})  "
              f"= ({pos[0]/1000:.2f}, {pos[1]/1000:.2f}) m")

    os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
    payload = {
        "anchors": positions,
        "pairwise_mm": {f"{hex(k[0])}-{hex(k[1])}": v for k, v in pair_avg.items()},
        "tag_id": "0x6e38",
        "probe_id": hex(PROBE_ID),
        "frame": "A at origin, B on +X, C in +Y half-plane",
        "method": "led-feedback walking calibration",
        "captured_at": time.time(),
    }
    with open(CONFIG_PATH, "w") as f:
        json.dump(payload, f, indent=2)
    print(f"\nSaved: {CONFIG_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
