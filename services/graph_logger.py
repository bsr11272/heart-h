#!/usr/bin/env python3
"""graph_logger.py — MQTT -> KuzuDB writer daemon for Hearth Memory Graph.

Subscribes to `#` on local mosquitto, turns every relevant message into a
Delta node, lazily creates Entity nodes, attaches each Delta to the current
5-minute TimeWindow. Single writer process — owns the KuzuDB write lock.

Run:
  /home/mendel/hearth/.dep-kuzu/bin/python services/graph_logger.py \
      --broker localhost --db /home/mendel/hearth/data/hmg.kuzu
"""
from __future__ import annotations

import argparse
import json
import logging
import queue
import re
import signal
import time
import uuid
from pathlib import Path
from typing import Any, Optional

import paho.mqtt.client as mqtt

from graph_schema import DEFAULT_DB_DIR, ensure_schema, open_db

LOG = logging.getLogger("graph_logger")

PAYLOAD_MAX_BYTES = 2048
QUEUE_MAX = 10_000

# Topics whose messages we should NOT log (binary, high-volume, transient).
SKIP_TOPIC_RE = re.compile(
    r"^(memory/|test/)|/(availability|thumb|seg_mask)$"
)

# Canonical resident — hardcoded for the demo.
RESIDENT_ID = "ent:person:margaret"
RESIDENT_NAME = "Margaret"


def now_ms() -> int:
    return int(time.time() * 1000)


def floor_5min(ts_ms: int) -> int:
    return (ts_ms // 300_000) * 300_000


def derive_src(topic: str) -> str:
    """Coarse src classification from MQTT topic prefix."""
    head = topic.split("/", 1)[0].lower()
    if head == "camera":
        return "camera"
    if head in ("radar", "ld2450"):
        return "radar"
    if head == "pendant" or head.startswith("pozyx"):
        return "pendant"
    if head == "vision":
        return "vision"
    return head or "system"


def derive_op(topic: str, payload_obj: Any) -> str:
    """Pull an op label from payload if present, else last topic segment."""
    if isinstance(payload_obj, dict):
        for k in ("op", "event", "type"):
            v = payload_obj.get(k)
            if isinstance(v, str) and v:
                return v
    last = topic.rsplit("/", 1)[-1]
    return last or "state"


def truncate_payload(raw: bytes) -> str:
    if len(raw) <= PAYLOAD_MAX_BYTES:
        try:
            return raw.decode("utf-8", errors="replace")
        except Exception:
            return repr(raw[:PAYLOAD_MAX_BYTES])
    head = raw[:PAYLOAD_MAX_BYTES]
    return head.decode("utf-8", errors="replace") + "...[truncated]"


class GraphLogger:
    def __init__(self, broker: str, db_path: Path, mqtt_port: int = 1883):
        self.broker = broker
        self.mqtt_port = mqtt_port
        self.db_path = db_path

        self.db = None
        self.conn = None
        self.client: Optional[mqtt.Client] = None
        self.q: queue.Queue = queue.Queue(maxsize=QUEUE_MAX)
        self.running = True

        # in-memory caches to skip redundant MERGEs
        self.entity_cache: set = set()
        self.tw_cache: set = set()
        self.cur_tw_id: Optional[str] = None
        self.cur_tw_floor: int = 0

        self.delta_count = 0
        self.dropped = 0

    # ----- DB --------------------------------------------------------------
    def open(self) -> None:
        self.db, self.conn = open_db(self.db_path, read_only=False)
        ensure_schema(self.conn)
        # Seed canonical resident.
        self._ensure_entity(RESIDENT_ID, "person", RESIDENT_NAME)
        LOG.info("kuzu open rw at %s", self.db_path)

    def close(self) -> None:
        try:
            if self.conn is not None:
                self.conn.close()
        except Exception:
            pass
        try:
            if self.db is not None:
                self.db.close()
        except Exception:
            pass

    # ----- entity / timewindow helpers ------------------------------------
    def _ensure_entity(self, ent_id: str, kind: str, name: str) -> None:
        if ent_id in self.entity_cache:
            return
        try:
            self.conn.execute(
                "MERGE (e:Entity {id: $id}) "
                "ON CREATE SET e.kind = $kind, e.name = $name, "
                "              e.created_ts = $ts",
                {"id": ent_id, "kind": kind, "name": name, "ts": now_ms()},
            )
            self.entity_cache.add(ent_id)
            LOG.info("entity+ %s (%s)", ent_id, kind)
        except Exception as exc:
            LOG.warning("ensure_entity %s failed: %s", ent_id, exc)

    def _ensure_timewindow(self, ts_ms: int) -> str:
        f = floor_5min(ts_ms)
        if f == self.cur_tw_floor and self.cur_tw_id:
            return self.cur_tw_id
        tw_id = "tw:5min:{0}".format(f)
        if tw_id not in self.tw_cache:
            try:
                self.conn.execute(
                    "MERGE (w:TimeWindow {id: $id}) "
                    "ON CREATE SET w.level = 1, "
                    "              w.start_ts = $s, w.end_ts = $e",
                    {"id": tw_id, "s": f, "e": f + 300_000},
                )
                self.tw_cache.add(tw_id)
            except Exception as exc:
                LOG.warning("ensure_timewindow %s failed: %s", tw_id, exc)
        self.cur_tw_id = tw_id
        self.cur_tw_floor = f
        return tw_id

    # ----- entity extraction ---------------------------------------------
    def _entities_from(self, topic: str, payload: Any) -> list:
        """Return list of (ent_id, kind, name) implied by topic+payload."""
        out: list = []
        parts = topic.split("/")
        head = parts[0].lower() if parts else ""

        if head == "camera" and len(parts) >= 2:
            cam = parts[1]
            out.append(("ent:camera:{0}".format(cam), "camera", cam))
        if head in ("radar", "ld2450") and len(parts) >= 2:
            node = parts[1]
            out.append(("ent:radar:{0}".format(node), "radar", node))
        if head == "pendant" or head.startswith("pozyx"):
            out.append(("ent:device:pendant", "device", "pendant"))
        if head == "audio" and len(parts) >= 2:
            src = parts[1]
            out.append(("ent:audio:{0}".format(src), "audio", src))

        if isinstance(payload, dict):
            ent_name = payload.get("entity")
            cls = payload.get("cls") or payload.get("class")
            if isinstance(ent_name, str) and ent_name:
                if isinstance(cls, str) and cls == "person":
                    out.append((RESIDENT_ID, "person", RESIDENT_NAME))
                else:
                    safe = re.sub(r"[^a-zA-Z0-9_.-]", "_", ent_name)[:64]
                    kind = cls if isinstance(cls, str) and cls else "object"
                    out.append((
                        "ent:object:{0}".format(safe), kind, ent_name,
                    ))
            op = payload.get("op")
            # Resident tagging — these all imply Margaret is the subject.
            #  * pose-based fall on camera
            #  * any detected person (cls=="person")
            #  * gait snapshot (single-resident demo: always Margaret)
            #  * audio event with severity alert/warn (a person made or
            #    triggered the sound)
            if (op in ("fall_event", "gait")
                    or cls == "person"
                    or (op == "event" and head == "audio"
                        and payload.get("severity") in ("alert", "warn"))):
                out.append((RESIDENT_ID, "person", RESIDENT_NAME))

        # de-dup preserving order
        seen: set = set()
        deduped: list = []
        for tup in out:
            if tup[0] in seen:
                continue
            seen.add(tup[0])
            deduped.append(tup)
        return deduped

    # ----- main write path -----------------------------------------------
    def _write_delta(self, topic: str, payload_bytes: bytes) -> None:
        if not payload_bytes:
            return
        if SKIP_TOPIC_RE.search(topic):
            return

        payload_str = truncate_payload(payload_bytes)
        payload_obj: Any
        try:
            payload_obj = json.loads(payload_str)
        except Exception:
            payload_obj = None

        src = derive_src(topic)
        op = derive_op(topic, payload_obj)
        ts = now_ms()
        delta_id = uuid.uuid4().hex

        try:
            self.conn.execute(
                "CREATE (d:Delta {id: $id, src: $src, topic: $topic, "
                "                 ts: $ts, op: $op, payload: $payload})",
                {"id": delta_id, "src": src, "topic": topic,
                 "ts": ts, "op": op, "payload": payload_str},
            )
        except Exception as exc:
            LOG.warning("create Delta failed (topic=%s): %s", topic, exc)
            return

        # entities -> OBSERVES edges
        for ent_id, kind, name in self._entities_from(topic, payload_obj):
            self._ensure_entity(ent_id, kind, name)
            try:
                self.conn.execute(
                    "MATCH (e:Entity {id: $eid}), (d:Delta {id: $did}) "
                    "CREATE (e)-[:OBSERVES {confidence: 1.0}]->(d)",
                    {"eid": ent_id, "did": delta_id},
                )
            except Exception as exc:
                LOG.warning("OBSERVES %s->%s failed: %s",
                            ent_id, delta_id, exc)

        # AT_TIME edge to current 5min bucket
        tw_id = self._ensure_timewindow(ts)
        try:
            self.conn.execute(
                "MATCH (d:Delta {id: $did}), (w:TimeWindow {id: $wid}) "
                "CREATE (d)-[:AT_TIME]->(w)",
                {"did": delta_id, "wid": tw_id},
            )
        except Exception as exc:
            LOG.warning("AT_TIME %s->%s failed: %s", delta_id, tw_id, exc)

        self.delta_count += 1
        if self.delta_count % 100 == 0:
            LOG.info("deltas written=%d  dropped=%d  q=%d",
                     self.delta_count, self.dropped, self.q.qsize())

    # ----- MQTT ----------------------------------------------------------
    def _on_connect(self, client, userdata, flags, rc, properties=None):
        LOG.info("mqtt connected rc=%s", rc)
        client.subscribe("#", qos=0)

    def _on_message(self, client, userdata, msg):
        try:
            self.q.put_nowait((msg.topic, msg.payload))
        except queue.Full:
            self.dropped += 1
            if self.dropped % 100 == 1:
                LOG.warning("queue full; dropped=%d", self.dropped)

    def setup_mqtt(self) -> None:
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id="hmg-logger")
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.client.connect(self.broker, self.mqtt_port, keepalive=30)
        self.client.loop_start()

    # ----- run loop ------------------------------------------------------
    def run(self) -> None:
        self.open()
        self.setup_mqtt()
        LOG.info("graph_logger running; broker=%s db=%s",
                 self.broker, self.db_path)
        try:
            while self.running:
                try:
                    topic, payload = self.q.get(timeout=0.5)
                except queue.Empty:
                    continue
                try:
                    self._write_delta(topic, payload)
                except Exception as exc:
                    LOG.exception("write loop error: %s", exc)
        finally:
            LOG.info("shutting down; total=%d dropped=%d",
                     self.delta_count, self.dropped)
            try:
                if self.client:
                    self.client.loop_stop()
                    self.client.disconnect()
            except Exception:
                pass
            self.close()

    def stop(self, *_a) -> None:
        self.running = False


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--broker", default="localhost")
    ap.add_argument("--mqtt-port", type=int, default=1883)
    ap.add_argument("--db", default=str(DEFAULT_DB_DIR))
    ap.add_argument("--log-level", default="INFO")
    args = ap.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    logger = GraphLogger(broker=args.broker, db_path=Path(args.db),
                         mqtt_port=args.mqtt_port)
    signal.signal(signal.SIGINT, logger.stop)
    signal.signal(signal.SIGTERM, logger.stop)
    logger.run()


if __name__ == "__main__":
    main()
