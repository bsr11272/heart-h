# HEART.H

**Home-based Elder Activity & Risk Tracking for Health.**
A privacy-first, multi-sensor aging-in-place companion. Five sensor families
flow into a small graph memory on a Coral Dev Board; an on-device
Gemma 4 E2B running on the resident's phone reasons over the snapshot
and the graph and never calls a cloud LLM.

Built for the **Gemma 4 Good Hackathon** (Kaggle × Google DeepMind, 2026),
Health & Economic Empowerment track. Full submission writeup:
[`docs/submission/WRITEUP.md`](docs/submission/WRITEUP.md).

## Hardware ($200 BOM)

| Device | Role |
|---|---|
| 1× Coral Dev Board 4 GB | Edge TPU perception (YOLO + MoveNet + DeepLab + gait), Mosquitto broker, KuzuDB graph |
| 2× HLK-LD2450 + ESP32-WROOM-32 | 24 GHz mmWave radar, per-target `(x,y,vx,vy)` at 10 Hz via ESPHome |
| 1× Logitech HD + 1× Brio 505 USB cam | RGB perception + microphone for YamNet audio events |
| 1× Pozyx Creator UWB tag | Pendant position via custom 2-D trilateration (~3 cm accuracy) |
| 1× Samsung Galaxy S23 Ultra | Compose UI + Gemma 4 E2B via LiteRT-LM |

## Repo layout

```
services/        Python services running on the Coral
  coral_camera_agent.py   YOLO + MoveNet + DeepLab + gait, MJPEG server on :8080
  audio_agent.py          YamNet on CPU, severity-tagged event stream
  pozyx_bridge.py         2-D trilateration, MQTT publisher
  graph_daemon.py         single-process logger + query + consolidator
  graph_logger.py         MQTT → KuzuDB writer
  graph_query.py          memory/query MQTT handler
  graph_consolidator.py   3-tier (5min / hour / day) summarization
  sensor_sim.py           deterministic synthetic sensor stream for demos
  install_audio_agent.sh  one-shot reproducible Coral audio setup

firmware/        ESPHome configs we flash to the radar ESP32s
  esp32_ld2450_bedroom.yaml
  esp32_ld2450_bathroom.yaml

hearth-android/  Jetpack Compose app (Kotlin)
  app/src/main/java/com/example/hearth/
    HearthViewModel.kt           connection + chat + sensor-state orchestration
    state/SensorState.kt         MQTT → typed snapshot + alert-fusion logic
    llm/GemmaEngine.kt           on-device Gemma 4 E2B via LiteRT-LM
    ui/                          7-tab UI (Home / Live / Pendant / Health / Ask / Memory / Setup)

scripts/
  demo.sh        one-command demo helper (status / preheat / fall / cluster / day / tail)

docs/submission/ Kaggle submission writeup (MD + LaTeX)
```

## Quickstart

Bring the Coral up:
```bash
# one-shot install + systemd enablement for the audio agent (idempotent)
bash services/install_audio_agent.sh

# camera + graph services were installed similarly during bring-up
sudo systemctl enable --now hearth-camera hearth-graph
```

Build + install the Android app:
```bash
cd hearth-android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# adb push the Gemma 4 E2B .litertlm into the app's external-files dir;
# see GemmaEngine.kt:14 for the exact path.
```

Run a deterministic demo without touching any physical sensor:
```bash
bash scripts/demo.sh fall      # amber-to-red banner sequence
bash scripts/demo.sh cluster   # 3 audio alerts in 90s
bash scripts/demo.sh day       # 14 sim hours at 12x speed
```

## License

Apache 2.0. See [LICENSE](LICENSE).

## Acknowledgments

Lifted: ESPHome `ld2450` component (Till Fleisch), LiteRT-LM + Gemma 4 E2B,
YamNet CPU TFLite from `tensorflow/examples`, pycoral + MoveNet + DeepLab,
Mosquitto, KuzuDB, HiveMQ MQTT 3 client, GraphRAG consolidation pattern.

Research prototype, not a medical device. Wellness framing only.
