#!/usr/bin/env python3
"""sensor_sim.py -- Hearth synthetic sensor stream.

Drives the Android app + Gemma + graph memory with plausible
"Margaret going about her day" data when no physical sensors are
firing. Publishes to the same MQTT topics the real Coral agents use,
so the app cannot tell sim vs. real apart.

Scenarios (--scenario):
  normal-day      Calm morning -> kitchen breakfast -> living room TV
                  -> bathroom -> bedroom nap -> back to living room.
                  Mostly INFO audio (microwave, water, doorbell), gait
                  ~110 spm, no falls.
  fall-event      30 s normal, then a stumble: scream (alert), pose
                  fall on camera, pendant fall_event, immobile for 60 s.
                  Tests the trigger-then-verify fusion banner.
  gait-decline    Cadence walks DOWN from 115 to 88 spm over the run,
                  asymmetry climbs to ~14 percent. Models a tired day.
  alert-cluster   3 audio alerts inside 90 s (glass break, scream,
                  smoke alarm) with vision confirming each. Stresses
                  the Health-tab feed.
  idle            Minimal activity -- just availability heartbeats and
                  the occasional radar wobble. Default if nothing is
                  specified.

Speed:
  --speed 1.0  realtime
  --speed 12.0 1 sim hour per 5 real minutes (good for live demo)

The script is deliberately self-contained -- only deps are stdlib +
paho-mqtt. Runs anywhere with MQTT reachability to the broker.

Usage::

    python sensor_sim.py --broker 100.86.78.49 --scenario normal-day --speed 12

Topics published (mirroring real agents)::

    radar/{node}/delta              {"src":"ld2450","presence":...,"targets":[...]}
    ld2450/{node}/availability      "online" (retained)
    camera/{cam}/delta              {"src":"camera","op":"add|move|remove",...}
    camera/{cam}/availability       "online" (retained)
    camera/{cam}/fall_event         pose fall
    camera/{cam}/gait               periodic gait snapshot
    pendant/availability            "online" (retained)
    pendant/events                  position + fall_event
    audio/{src}/availability        "online" (retained)
    audio/{src}/event               YamNet-style event
"""
from __future__ import annotations

import argparse
import json
import logging
import math
import random
import signal
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable, Dict, List, Optional, Tuple

import paho.mqtt.client as mqtt

LOG = logging.getLogger("sensor_sim")

# Mirror the real agents' device IDs so the app's RoomLabels map works
# without further config.
CAM_ID    = "cam1"          # Living Room
RADAR_A   = "ld2450-a"      # Bedroom
RADAR_B   = "ld2450-b"      # Kitchen
AUDIO_SRC = "cam1"          # USB-cam mic on cam1

# Floorplan in metres (origin: bedroom NW corner, +x east, +y south).
# Each "room" is an axis-aligned rectangle; the simulator picks a random
# waypoint inside it and walks Margaret there over a few seconds.
ROOMS: Dict[str, Tuple[float, float, float, float]] = {
    "Bedroom":     (0.0,  0.0,  3.5,  4.0),
    "Bathroom":    (3.5,  0.0,  5.5,  2.5),
    "Hallway":     (3.5,  2.5,  5.5,  4.5),
    "Kitchen":     (5.5,  0.0,  9.5,  4.5),
    "LivingRoom":  (0.0,  4.5,  9.5, 10.0),
}
RADAR_COVERAGE = {RADAR_A: {"Bedroom", "Bathroom"},
                  RADAR_B: {"Kitchen"}}
CAMERA_COVERAGE = {CAM_ID: {"LivingRoom"}}
# Pretty names for log messages (the app uses its own RoomLabels map).
PRETTY_ROOM = {"Bedroom": "Bedroom", "Bathroom": "Bathroom",
               "Hallway": "Hallway", "Kitchen": "Kitchen",
               "LivingRoom": "Living Room"}


# ---------------------------------------------------------------------------
# small utilities
# ---------------------------------------------------------------------------

def iso_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def now_ms() -> int:
    return int(time.time() * 1000)


def jitter(value: float, sigma: float) -> float:
    return value + random.gauss(0.0, sigma)


def waypoint_in(room: str) -> Tuple[float, float]:
    x1, y1, x2, y2 = ROOMS[room]
    return (random.uniform(x1 + 0.3, x2 - 0.3),
            random.uniform(y1 + 0.3, y2 - 0.3))


def room_for(pos: Tuple[float, float]) -> Optional[str]:
    x, y = pos
    for name, (x1, y1, x2, y2) in ROOMS.items():
        if x1 <= x <= x2 and y1 <= y <= y2:
            return name
    return None


# ---------------------------------------------------------------------------
# MQTT helpers
# ---------------------------------------------------------------------------

class SimBroker:
    def __init__(self, host: str, port: int = 1883, retain_avail: bool = True):
        self.host = host
        self.port = port
        self.retain_avail = retain_avail
        # paho-mqtt 1.x and 2.x both need to work here.
        try:
            self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                      client_id="hearth-sim")
        except AttributeError:
            self.client = mqtt.Client(client_id="hearth-sim")
        # Drop the simulator's own retained availability messages on stop
        # so the app doesn't see a "ghost online" sim cam after we exit.
        self._availability_topics: List[str] = []

    def connect(self) -> None:
        self.client.connect(self.host, self.port, keepalive=30)
        self.client.loop_start()
        LOG.info("connected to mqtt://%s:%d", self.host, self.port)

    def disconnect(self) -> None:
        # Deliberately do NOT publish "offline" on shutdown -- those topics
        # are owned by real agents (audio_agent, coral_camera_agent, ...).
        # If the real agent is running it'll re-assert "online" on its
        # next heartbeat; if not, leaving the last "online" retained
        # keeps the app happy during a sim-only demo. (Earlier versions
        # flipped these to offline on exit and broke the real agents.)
        time.sleep(0.2)
        self.client.loop_stop()
        self.client.disconnect()

    def online(self, topic: str) -> None:
        self._availability_topics.append(topic)
        self.client.publish(topic, "online", qos=1, retain=self.retain_avail)

    def pub(self, topic: str, payload: dict, qos: int = 0,
            retain: bool = False) -> None:
        self.client.publish(topic, json.dumps(payload, separators=(",", ":")),
                            qos=qos, retain=retain)


# ---------------------------------------------------------------------------
# Margaret -- shared state for the active scenario
# ---------------------------------------------------------------------------

@dataclass
class Margaret:
    pos: Tuple[float, float]
    room: str
    track_id: int = 7         # stable in-frame track for camera_id "person_7"
    visible_to_cam: bool = False
    pendant_z_m: float = 1.05  # waist height while standing
    pendant_online: bool = True
    fall_active: bool = False
    # Gait drift -- updated each gait_emit
    cadence_spm: float = 112.0
    stride_m: float = 1.18
    asymmetry_pct: float = 3.5
    # Health / day-cycle state
    hour_of_day: float = 9.0  # used by scenarios that schedule by sim time

    def update_visible(self) -> None:
        self.visible_to_cam = (self.room == "LivingRoom")


@dataclass
class SimContext:
    broker: SimBroker
    margaret: Margaret
    sim_t: float = 0.0          # seconds since scenario start (scaled)
    speed: float = 1.0
    last_pendant_pos: Tuple[float, float] = (0.0, 0.0)
    last_pendant_t: float = 0.0
    last_gait_t: float = -999.0
    last_radar_pub: Dict[str, float] = field(default_factory=dict)
    last_cam_emit_t: float = 0.0
    last_emitted_room: Optional[str] = None
    cam_entity_active: bool = False


# ---------------------------------------------------------------------------
# Emitter primitives -- each maps Margaret's state to MQTT publishes that
# look indistinguishable from the real agents.
# ---------------------------------------------------------------------------

def emit_pendant_position(ctx: SimContext) -> None:
    m = ctx.margaret
    if not m.pendant_online:
        return
    x, y = m.pos
    payload = {
        "src": "pendant",
        "op": "position",
        "t": time.time(),
        "xyz_m": [round(x, 2), round(y, 2), round(m.pendant_z_m, 2)],
        "raw_xy_m": [round(x, 2), round(y, 2)],
        "ranges_mm": {"sim": -1},
        "room": PRETTY_ROOM.get(m.room, m.room),
    }
    ctx.broker.pub("pendant/events", payload, qos=0)
    ctx.last_pendant_pos = m.pos
    ctx.last_pendant_t = ctx.sim_t


def emit_pendant_fall(ctx: SimContext, phase: str, alt_drop_cm: float = 0.0) -> None:
    payload = {
        "src": "pendant",
        "op": "fall_event",
        "t": time.time(),
        "phase": phase,           # "detected" | "confirmed" | "cancelled"
        "alt_drop_cm": round(alt_drop_cm, 1),
        "alt_now_cm": round(ctx.margaret.pendant_z_m * 100.0, 1),
    }
    ctx.broker.pub("pendant/events", payload, qos=1)
    LOG.warning("[sim] PENDANT FALL phase=%s drop=%scm", phase, alt_drop_cm)


def emit_radar(ctx: SimContext, node: str, presence: bool,
               targets: Optional[List[Tuple[float, float]]] = None,
               moving: bool = False) -> None:
    # Match ESPHome's consolidated bridge format the app already parses.
    t_list = targets or []
    payload = {
        "src": "ld2450",
        "node": node,
        "presence": presence,
        "moving": moving,
        "target_count": len(t_list),
        "targets": [{"x": round(jitter(x, 0.05), 2),
                     "y": round(jitter(y, 0.05), 2)} for (x, y) in t_list],
        "t": time.time(),
    }
    ctx.broker.pub("radar/{0}/delta".format(node), payload, qos=0)


def emit_camera_delta(ctx: SimContext, op: str) -> None:
    """op in {add, move, remove}."""
    m = ctx.margaret
    entity_name = "person_{0}".format(m.track_id)
    # Project Margaret's living-room position into a fake 640x480 pixel
    # frame. Living room spans (0..9.5, 4.5..10) m -> bottom-left origin
    # of the cam's FOV (cam mounted in corner).
    x_norm = max(0.0, min(1.0, (m.pos[0] - 0.0) / 9.5))
    y_norm = max(0.0, min(1.0, (m.pos[1] - 4.5) / 5.5))
    cx = int(60 + x_norm * 520)
    cy = int(80 + y_norm * 320)
    payload = {
        "src": "camera",
        "cam": CAM_ID,
        "op": op,
        "entity": entity_name,
        "cls": "person",
        "at": [cx, cy],
        "conf": round(0.83 + random.uniform(-0.05, 0.10), 2),
        "t": time.time(),
    }
    ctx.broker.pub("camera/{0}/delta".format(CAM_ID), payload, qos=0)


def emit_camera_fall(ctx: SimContext) -> None:
    m = ctx.margaret
    payload = {
        "src": "camera",
        "cam": CAM_ID,
        "op": "fall_event",
        "t": iso_now(),
        "head_y_drop_frac": round(random.uniform(0.32, 0.55), 3),
        "pose_aspect": round(random.uniform(1.4, 2.1), 3),
        "pose_confidence": round(random.uniform(0.55, 0.85), 3),
        "bbox": [180, 240, 460, 460],
    }
    ctx.broker.pub("camera/{0}/fall_event".format(CAM_ID), payload, qos=1)
    LOG.warning("[sim] CAMERA FALL emitted")


def emit_gait(ctx: SimContext) -> None:
    m = ctx.margaret
    payload = {
        "t": iso_now(),
        "src": "camera",
        "cam": CAM_ID,
        "op": "gait",
        "person_id": "person_{0}".format(m.track_id),
        "stride_length_px": int(round(140 + (m.stride_m - 1.0) * 80)),
        "stride_length_m_rough": round(m.stride_m, 2),
        "cadence_spm": int(round(m.cadence_spm)),
        "swing_asymmetry_pct": round(m.asymmetry_pct, 1),
        "n_strides": random.randint(5, 10),
        "window_sec": 5.0,
        "confidence": round(random.uniform(0.7, 0.9), 2),
    }
    ctx.broker.pub("camera/{0}/gait".format(CAM_ID), payload, qos=0)
    LOG.info("[sim] gait cad=%d spm stride=%.2fm asym=%.1f%%",
             int(m.cadence_spm), m.stride_m, m.asymmetry_pct)


# YamNet-style audio events. Curated to match the real audio_agent's
# watchlist so the app and graph treat them identically.
AUDIO_CATALOG: List[Tuple[str, str, List[Tuple[str, float]]]] = [
    # (label, severity, top3 distractors with probabilities <= top1)
    ("Glass",         "alert", [("Glass", 0.71), ("Shatter", 0.18), ("Smash, crash", 0.06)]),
    ("Screaming",     "alert", [("Screaming", 0.62), ("Shout", 0.21), ("Speech", 0.08)]),
    ("Smoke detector, smoke alarm", "alert",
        [("Smoke detector, smoke alarm", 0.81), ("Beep, bleep", 0.09), ("Alarm", 0.05)]),
    ("Crying, sobbing", "warn",
        [("Crying, sobbing", 0.55), ("Wail, moan", 0.18), ("Whimper", 0.09)]),
    ("Thump, thud",   "warn", [("Thump, thud", 0.49), ("Slam", 0.21), ("Bang", 0.12)]),
    ("Doorbell",      "info", [("Doorbell", 0.78), ("Ding-dong", 0.13), ("Bell", 0.05)]),
    ("Microwave oven","info", [("Microwave oven", 0.66), ("Beep, bleep", 0.18), ("Hum", 0.06)]),
    ("Water tap, faucet", "info",
        [("Water tap, faucet", 0.58), ("Running water", 0.27), ("Stream", 0.08)]),
    ("Television",    "info", [("Television", 0.51), ("Speech", 0.31), ("Music", 0.10)]),
    ("Knock",         "info", [("Knock", 0.62), ("Tap", 0.18), ("Thump, thud", 0.08)]),
]


def emit_audio_event(ctx: SimContext, label: Optional[str] = None,
                     severity: Optional[str] = None) -> None:
    if label is None:
        # Weight INFO heavily so the feed feels lived-in not alarming.
        pool = AUDIO_CATALOG.copy()
        weights = [4 if e[1] == "info" else 1 for e in pool]
        evt = random.choices(pool, weights=weights, k=1)[0]
    else:
        match = [e for e in AUDIO_CATALOG if e[0] == label]
        evt = match[0] if match else random.choice(AUDIO_CATALOG)
    lbl, sev, top3 = evt
    sev = severity or sev
    conf = top3[0][1] + random.uniform(-0.05, 0.05)
    payload = {
        "t": iso_now(),
        "src": "audio",
        "source": AUDIO_SRC,
        "op": "event",
        "label": lbl,
        "class_id": -1,
        "confidence": round(conf, 3),
        "severity": sev,
        "top_3": [[n, round(p, 3)] for n, p in top3],
        "window_ms": 975,
    }
    ctx.broker.pub("audio/{0}/event".format(AUDIO_SRC), payload, qos=0)
    LOG.info("[sim] audio %s sev=%s conf=%.2f", lbl, sev, conf)


# ---------------------------------------------------------------------------
# Motion model -- Margaret walks from current pos toward a target waypoint
# at a slow human pace, emitting pendant + camera every ~500 ms.
# ---------------------------------------------------------------------------

WALK_MPS = 0.6            # easy elderly walking pace
TURN_DWELL_S = 1.2


def step_motion(ctx: SimContext, dt_sim: float,
                target: Optional[Tuple[float, float]]) -> bool:
    """Advance Margaret toward target by dt_sim seconds of motion.
    Returns True if target reached (within 0.25 m)."""
    if target is None:
        return True
    m = ctx.margaret
    dx = target[0] - m.pos[0]
    dy = target[1] - m.pos[1]
    dist = math.hypot(dx, dy)
    if dist < 0.25:
        m.pos = target
        return True
    step = min(dist, WALK_MPS * dt_sim)
    m.pos = (m.pos[0] + dx / dist * step,
             m.pos[1] + dy / dist * step)
    return False


# ---------------------------------------------------------------------------
# Periodic emitters -- called from the main loop each tick.
# ---------------------------------------------------------------------------

def tick_radar(ctx: SimContext, tick_dt: float, rate_hz: float = 2.0) -> None:
    """Each radar publishes its current view of Margaret every ~0.5 s."""
    period = 1.0 / rate_hz
    for node, covered in RADAR_COVERAGE.items():
        last = ctx.last_radar_pub.get(node, -999.0)
        if ctx.sim_t - last < period:
            continue
        ctx.last_radar_pub[node] = ctx.sim_t
        present = ctx.margaret.room in covered
        targets = []
        if present:
            # synthesize 1 target in node-local (x_lateral, y_forward) m
            x_local = jitter(random.uniform(-1.0, 1.0), 0.05)
            y_local = jitter(random.uniform(0.5, 3.0), 0.10)
            targets = [(x_local, y_local)]
        emit_radar(ctx, node, presence=present, targets=targets,
                   moving=present and abs(ctx.margaret.pos[0]
                                          - ctx.last_pendant_pos[0]) > 0.05)


def tick_camera(ctx: SimContext, tick_dt: float) -> None:
    m = ctx.margaret
    m.update_visible()
    # add/remove transitions
    if m.visible_to_cam and not ctx.cam_entity_active:
        emit_camera_delta(ctx, "add")
        ctx.cam_entity_active = True
        ctx.last_cam_emit_t = ctx.sim_t
    elif not m.visible_to_cam and ctx.cam_entity_active:
        emit_camera_delta(ctx, "remove")
        ctx.cam_entity_active = False
    elif m.visible_to_cam:
        # send move every 0.5 s
        if ctx.sim_t - ctx.last_cam_emit_t > 0.5:
            emit_camera_delta(ctx, "move")
            ctx.last_cam_emit_t = ctx.sim_t


def tick_pendant(ctx: SimContext, tick_dt: float) -> None:
    if not ctx.margaret.pendant_online:
        return
    if ctx.sim_t - ctx.last_pendant_t > 0.8:
        emit_pendant_position(ctx)


def tick_gait(ctx: SimContext, tick_dt: float, decline_per_min: float = 0.0) -> None:
    """Gait snapshot every ~30 sim-seconds, but only while Margaret is
    visible to the cam (mirrors the real agent's gating)."""
    m = ctx.margaret
    if not m.visible_to_cam:
        return
    if ctx.sim_t - ctx.last_gait_t < 30.0:
        return
    # Drift cadence + asymmetry slightly each emit.
    # Without explicit decline_per_min, clamp the jitter to a clinically
    # plausible envelope around a per-person baseline so a 14-sim-hour run
    # doesn't drift into "Margaret can barely walk" territory by dinner.
    if decline_per_min != 0.0:
        m.cadence_spm = max(70.0, m.cadence_spm - decline_per_min * 0.5)
        m.stride_m = max(0.55, m.stride_m - decline_per_min * 0.005)
        m.asymmetry_pct = min(25.0, m.asymmetry_pct + decline_per_min * 0.10)
    else:
        # Stationary OU-style noise: pull toward baseline, then jitter.
        cad_baseline   = 112.0
        stride_baseline = 1.20
        asym_baseline   = 3.5
        m.cadence_spm  = jitter(0.7 * m.cadence_spm + 0.3 * cad_baseline, 1.5)
        m.stride_m     = jitter(0.7 * m.stride_m   + 0.3 * stride_baseline, 0.02)
        m.asymmetry_pct = max(0.5,
            jitter(0.7 * m.asymmetry_pct + 0.3 * asym_baseline, 0.4))
        # Hard safety clamps -- never drift outside healthy adult envelope.
        m.cadence_spm   = max(95.0, min(125.0, m.cadence_spm))
        m.stride_m      = max(1.05, min(1.40,  m.stride_m))
        m.asymmetry_pct = max(0.5,  min(8.0,   m.asymmetry_pct))
    emit_gait(ctx)
    ctx.last_gait_t = ctx.sim_t


# ---------------------------------------------------------------------------
# Scenarios -- each is a generator of (sim_time, action_callable) pairs.
# Action is invoked when sim_t passes that timestamp.
# ---------------------------------------------------------------------------

Action = Callable[[SimContext], None]


def goto(room: str) -> Action:
    def _go(ctx: SimContext) -> None:
        wp = waypoint_in(room)
        ctx.margaret.room = room
        ctx.margaret.pos = wp
        LOG.info("[sim] -> %s", PRETTY_ROOM.get(room, room))
    return _go


def audio_event(label: str, severity: Optional[str] = None) -> Action:
    return lambda ctx: emit_audio_event(ctx, label=label, severity=severity)


def stand_still(_ctx: SimContext) -> None:
    pass


def scenario_normal_day() -> List[Tuple[float, Action]]:
    return [
        (   0.0, goto("Bedroom")),
        (  20.0, goto("Bathroom")),
        (  35.0, audio_event("Water tap, faucet")),
        (  60.0, goto("Kitchen")),
        (  75.0, audio_event("Microwave oven")),
        (  95.0, audio_event("Water tap, faucet")),
        ( 130.0, goto("LivingRoom")),
        ( 150.0, audio_event("Television")),
        ( 240.0, audio_event("Doorbell", "info")),
        ( 260.0, audio_event("Knock")),
        ( 340.0, goto("Kitchen")),
        ( 400.0, audio_event("Microwave oven")),
        ( 440.0, goto("LivingRoom")),
        ( 600.0, audio_event("Television")),
        ( 780.0, goto("Bedroom")),     # afternoon nap
        ( 900.0, stand_still),
        (1080.0, goto("LivingRoom")),
        (1200.0, audio_event("Television")),
    ]


def scenario_fall_event() -> List[Tuple[float, Action]]:
    return [
        (  0.0, goto("LivingRoom")),
        ( 15.0, audio_event("Television")),
        ( 40.0, lambda ctx: setattr(ctx.margaret, "pos",
                                    (4.5, 6.0))),   # walking
        ( 55.0, audio_event("Screaming", "alert")),
        ( 56.0, lambda ctx: emit_camera_fall(ctx)),
        ( 57.0, audio_event("Thump, thud", "warn")),
        ( 58.0, lambda ctx: emit_pendant_fall(ctx, "confirmed",
                                              alt_drop_cm=85.0)),
        ( 60.0, lambda ctx: setattr(ctx.margaret, "pendant_z_m", 0.18)),
        # Margaret stays on the floor for a minute
        (120.0, audio_event("Crying, sobbing", "warn")),
        (180.0, lambda ctx: setattr(ctx.margaret, "pendant_z_m", 1.05)),
        (185.0, lambda ctx: emit_pendant_fall(ctx, "cancelled")),
    ]


def scenario_gait_decline() -> List[Tuple[float, Action]]:
    """Cadence drops markedly over the run; emitted via tick_gait's
    decline_per_min parameter (handled in main loop, not as discrete actions)."""
    return [
        (   0.0, goto("LivingRoom")),
        (  60.0, audio_event("Television")),
        ( 180.0, audio_event("Water tap, faucet")),
        ( 300.0, audio_event("Microwave oven")),
        ( 480.0, audio_event("Doorbell")),
        ( 600.0, stand_still),
    ]


def scenario_alert_cluster() -> List[Tuple[float, Action]]:
    return [
        (  0.0, goto("Kitchen")),
        ( 15.0, audio_event("Glass", "alert")),
        ( 17.0, audio_event("Thump, thud", "warn")),
        ( 45.0, audio_event("Screaming", "alert")),
        ( 90.0, audio_event("Smoke detector, smoke alarm", "alert")),
        (120.0, audio_event("Crying, sobbing", "warn")),
        (180.0, audio_event("Television")),
    ]


def scenario_idle() -> List[Tuple[float, Action]]:
    return [(0.0, goto("LivingRoom"))]


SCENARIOS = {
    "normal-day":    (scenario_normal_day,    0.0),
    "fall-event":    (scenario_fall_event,    0.0),
    "gait-decline":  (scenario_gait_decline,  2.5),   # decline_per_min
    "alert-cluster": (scenario_alert_cluster, 0.0),
    "idle":          (scenario_idle,          0.0),
}


# ---------------------------------------------------------------------------
# Main loop
# ---------------------------------------------------------------------------

def run(args: argparse.Namespace) -> None:
    broker = SimBroker(args.broker, args.port)
    broker.connect()
    # Announce all sim "devices" as online (matches real agent behaviour).
    broker.online("pendant/availability")
    broker.online("ld2450/{0}/availability".format(RADAR_A))
    broker.online("ld2450/{0}/availability".format(RADAR_B))
    broker.online("camera/{0}/availability".format(CAM_ID))
    broker.online("audio/{0}/availability".format(AUDIO_SRC))

    margaret = Margaret(pos=waypoint_in("LivingRoom"), room="LivingRoom")
    ctx = SimContext(broker=broker, margaret=margaret, speed=args.speed)

    scenario_fn, decline_per_min = SCENARIOS[args.scenario]
    events = scenario_fn()
    next_idx = 0

    # Target waypoint that motion model is currently walking toward
    target: Optional[Tuple[float, float]] = None
    last_target_set_t = -999.0
    LOG.info("scenario=%s speed=%.1fx decline=%.2fspm/min duration=%.0fs",
             args.scenario, args.speed, decline_per_min, args.duration)

    real_start = time.monotonic()
    tick_dt_real = 0.25                   # 4 Hz physical loop
    running = True

    def _stop(*_a) -> None:
        nonlocal running
        running = False
    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    try:
        while running:
            now_real = time.monotonic()
            ctx.sim_t = (now_real - real_start) * args.speed
            if ctx.sim_t > args.duration:
                LOG.info("duration reached; exiting")
                break

            # 1) Fire any scenario actions whose sim_t has passed.
            while next_idx < len(events) and events[next_idx][0] <= ctx.sim_t:
                t0, action = events[next_idx]
                try:
                    action(ctx)
                except Exception as exc:
                    LOG.warning("action @%.1fs failed: %s", t0, exc)
                next_idx += 1
                # After a goto, set a new target waypoint inside the new room.
                target = waypoint_in(ctx.margaret.room)
                last_target_set_t = ctx.sim_t

            # 2) Motion update -- only "physical" simulation here.
            sim_dt = tick_dt_real * args.speed
            if target is not None:
                reached = step_motion(ctx, sim_dt, target)
                if reached:
                    # Sit a moment then pick a new spot inside the room.
                    if ctx.sim_t - last_target_set_t > TURN_DWELL_S:
                        target = waypoint_in(ctx.margaret.room)
                        last_target_set_t = ctx.sim_t
            ctx.margaret.update_visible()

            # 3) Periodic emitters.
            tick_radar(ctx, tick_dt_real)
            tick_camera(ctx, tick_dt_real)
            tick_pendant(ctx, tick_dt_real)
            tick_gait(ctx, tick_dt_real, decline_per_min=decline_per_min)

            time.sleep(tick_dt_real)
    finally:
        broker.disconnect()
        LOG.info("disconnected; bye")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--broker", default="100.86.78.49",
                    help="Mosquitto host (default: Coral via Tailscale)")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--scenario", choices=list(SCENARIOS.keys()),
                    default="normal-day")
    ap.add_argument("--speed", type=float, default=12.0,
                    help="Sim-time multiplier (12 = 1 sim hr per 5 real min)")
    ap.add_argument("--duration", type=float, default=14 * 3600,
                    help="Sim-seconds before exit (default: 14 sim hours)")
    ap.add_argument("--log-level", default="INFO")
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    if args.seed:
        random.seed(args.seed)
    run(args)


if __name__ == "__main__":
    main()
