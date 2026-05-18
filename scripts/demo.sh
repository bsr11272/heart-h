#!/bin/bash
# scripts/demo.sh -- one-command demo helper.
#
# Usage from anywhere:
#   bash scripts/demo.sh status      # health-check Coral + count fresh events
#   bash scripts/demo.sh preheat     # seed gait sparkline + ambient audio (60s)
#   bash scripts/demo.sh fall        # THE MONEY SHOT: trigger fall-event sim
#   bash scripts/demo.sh cluster     # 3 audio alerts in 90s, vision confirming
#   bash scripts/demo.sh day         # full normal-day cycle, runs ~5 min real
#   bash scripts/demo.sh stop        # kill any running sim
#   bash scripts/demo.sh tail        # tail audio events on broker (Ctrl-C to exit)
set -euo pipefail

REPO="/home/sai/Desktop/Work/Gemma_Kaggle"
PY="${REPO}/.dep/bin/python"
SIM="${REPO}/services/sensor_sim.py"
CORAL="100.86.78.49"
USER="mendel"

cmd="${1:-status}"

case "$cmd" in
status)
    echo "=== Coral services ==="
    ssh -o ConnectTimeout=5 ${USER}@${CORAL} 'for s in mosquitto hearth-camera hearth-audio hearth-graph; do printf "  %-16s %s\n" "$s" "$(sudo systemctl is-active $s)"; done'
    echo
    echo "=== Local running sim ==="
    pgrep -af sensor_sim.py | grep -v grep || echo "  (none)"
    echo
    echo "=== Phone connected ==="
    adb devices | tail -n +2 | grep -v '^$' || echo "  (adb not seeing the phone — fine if it's only on Tailscale)"
    ;;

preheat)
    echo "Seeding 60s of baseline (20x speed = 20 sim-min of activity)..."
    timeout 65 ${PY} ${SIM} --scenario normal-day --speed 20 \
        --duration 1200 --broker ${CORAL} --seed 100 --log-level WARNING \
        2>&1 | tail -3
    echo "Pre-warm complete. Open the Health tab on the phone -- sparkline + audio events are ready."
    ;;

fall)
    echo "Firing fall-event scenario (4x speed, ~50 sec). Watch the phone:"
    echo "  - amber 'HEARD SOMETHING UNUSUAL' first (audio Screaming)"
    echo "  - red 'FALL DETECTED' a few seconds later (vision + pendant confirm)"
    echo "  - tap 'Check in' to fire Gemma's on-device response"
    echo
    ${PY} ${SIM} --scenario fall-event --speed 4 \
        --duration 200 --broker ${CORAL} --seed 42 --log-level INFO
    ;;

cluster)
    echo "Firing alert-cluster (3 audio alerts in 90 sim-sec, 6x speed)..."
    ${PY} ${SIM} --scenario alert-cluster --speed 6 \
        --duration 180 --broker ${CORAL} --seed 7 --log-level INFO
    ;;

day)
    echo "Firing normal-day (14 sim-hr, 12x speed, ~70 min real). Ctrl-C to stop."
    ${PY} ${SIM} --scenario normal-day --speed 12 \
        --duration 50400 --broker ${CORAL} --seed 2026 --log-level INFO
    ;;

stop)
    echo "Killing any running sim..."
    pkill -f sensor_sim.py 2>/dev/null && echo "killed" || echo "(no sim was running)"
    ;;

tail)
    echo "Tailing audio/+ + camera/+/gait + pendant/events on broker (Ctrl-C to exit)..."
    ssh -o ConnectTimeout=5 ${USER}@${CORAL} \
        'mosquitto_sub -t "audio/+/event" -t "camera/+/gait" -t "camera/+/fall_event" -t "pendant/events" -v 2>&1' \
        | grep -v '"op":"position"'
    ;;

*)
    echo "Unknown command: $cmd"
    echo "Try: status | preheat | fall | cluster | day | stop | tail"
    exit 1
    ;;
esac
