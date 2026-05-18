#!/bin/bash
# install_audio_agent.sh -- one-shot reproducible setup for hearth-audio
# on the Coral Dev Board.
#
# Why this script exists:
#   * The Coral runs Debian Buster (glibc 2.28, Python 3.7 default).
#   * Modern YamNet TFLite needs tflite_runtime >= 2.10 (op codes up to
#     ~140), which has NO wheel for cp37 and most recent versions need
#     glibc >= 2.34.
#   * The escape hatch is uv-managed CPython 3.10 + tflite-runtime 2.13
#     (manylinux2014 wheel needs only glibc 2.17). numpy<2 to dodge the
#     2.x ABI break.
#
# Run on the Coral as `mendel`:
#   bash install_audio_agent.sh
#
# Idempotent: re-running is safe.
set -euo pipefail

HEARTH_DIR="/home/mendel/hearth"
VENV_DIR="${HEARTH_DIR}/.dep-audio"
MODELS_DIR="${HEARTH_DIR}/models"
UNIT_PATH="/etc/systemd/system/hearth-audio.service"
ALSA_DEVICE="${ALSA_DEVICE:-plughw:CARD=Camera,DEV=0}"

# YamNet (CPU, with-frontend, 521-class AudioSet) from the TF examples
# Coral RPi audio-classification project. Mirrored on Google Cloud
# Storage at this stable URL.
YAMNET_URL="https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/rpi/lite-model_yamnet_classification_tflite_1.tflite"

echo "[1/5] ensure uv is installed"
if ! command -v uv >/dev/null 2>&1; then
    if [ ! -x "$HOME/.local/bin/uv" ]; then
        curl -LsSf https://astral.sh/uv/install.sh | sh
    fi
fi
export PATH="$HOME/.local/bin:$PATH"

echo "[2/5] create / reuse Python 3.10 venv at ${VENV_DIR}"
if [ ! -d "${VENV_DIR}" ]; then
    uv venv --python 3.10 "${VENV_DIR}"
fi
uv pip install --python "${VENV_DIR}/bin/python" \
    'tflite-runtime==2.13.0' 'numpy<2' paho-mqtt

echo "[3/5] download YamNet model + label list (idempotent)"
mkdir -p "${MODELS_DIR}"
if [ ! -s "${MODELS_DIR}/yamnet_cpu.tflite" ]; then
    curl -sLo "${MODELS_DIR}/yamnet_cpu.tflite" "${YAMNET_URL}"
fi
# Label list ships inside the TFLite zip-metadata; extract it once.
if [ ! -s "${MODELS_DIR}/yamnet_label_list.txt" ]; then
    cd "${MODELS_DIR}"
    unzip -p yamnet_cpu.tflite yamnet_label_list.txt > yamnet_label_list.txt \
        || echo "WARN: could not extract label list — will need manual install"
    cd -
fi

echo "[4/5] install systemd unit ${UNIT_PATH}"
sudo tee "${UNIT_PATH}" > /dev/null <<EOF
[Unit]
Description=Hearth audio-event agent (YamNet CPU on Coral) -- cam1 mic
After=network-online.target mosquitto.service
Wants=network-online.target

[Service]
Type=simple
User=mendel
Group=audio
WorkingDirectory=${HEARTH_DIR}
ExecStart=${VENV_DIR}/bin/python ${HEARTH_DIR}/services/audio_agent.py --source ${ALSA_DEVICE} --source-name cam1 --broker localhost
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
sudo usermod -aG audio mendel || true
sudo systemctl daemon-reload
sudo systemctl enable --now hearth-audio.service

echo "[5/5] verify"
sleep 3
sudo systemctl is-active hearth-audio.service
sudo journalctl -u hearth-audio.service -n 8 --no-pager
echo
echo "Done. Listen for events with:"
echo "  mosquitto_sub -t 'audio/#' -v"
