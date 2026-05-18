#!/usr/bin/env python3
"""graph_query.py — MQTT request/response query layer for HMG.

Subscribes to `memory/query`, executes a JSON-described query against the
KuzuDB in read-only mode, publishes JSON results to `memory/response`.

Request types:
  {"id": "...", "type": "recent_deltas", "src": "camera",
   "since_min": 10, "limit": 50}
  {"id": "...", "type": "recent_summaries", "level": 1, "since_min": 60}
  {"id": "...", "type": "entity_observations",
   "entity": "ent:person:margaret", "since_min": 30}
  {"id": "...", "type": "cypher",
   "query": "MATCH (e:Entity)-[:OBSERVES]->(d:Delta) ... RETURN ...",
   "params": {"since": 1779083400000}}

Run:
  /home/mendel/hearth/.dep-kuzu/bin/python services/graph_query.py \
      --broker localhost --db /home/mendel/hearth/data/hmg.kuzu
"""
from __future__ import annotations

import argparse
import json
import logging
import queue
import signal
import time
from pathlib import Path
from typing import Any, Optional

import paho.mqtt.client as mqtt

from graph_schema import DEFAULT_DB_DIR, open_db, result_to_dicts

LOG = logging.getLogger("graph_query")

REQ_TOPIC = "memory/query"
RES_TOPIC = "memory/response"
QUEUE_MAX = 1024
MAX_LIMIT = 500
DEFAULT_LIMIT = 100


def now_ms() -> int:
    return int(time.time() * 1000)


class GraphQuery:
    def __init__(self, broker: str, db_path: Path, mqtt_port: int = 1883):
        self.broker = broker
        self.mqtt_port = mqtt_port
        self.db_path = db_path

        self.db = None
        self.conn = None
        self.client: Optional[mqtt.Client] = None
        self.q: queue.Queue = queue.Queue(maxsize=QUEUE_MAX)
        self.running = True

    # ----- DB --------------------------------------------------------------
    def open(self) -> None:
        # READ-ONLY so the writer (graph_logger) keeps the write lock.
        self.db, self.conn = open_db(self.db_path, read_only=True)
        LOG.info("kuzu open ro at %s", self.db_path)

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

    # ----- canned queries -------------------------------------------------
    def _q_recent_deltas(self, req: dict) -> list:
        since_min = int(req.get("since_min", 10))
        limit = min(int(req.get("limit", DEFAULT_LIMIT)), MAX_LIMIT)
        since = now_ms() - since_min * 60_000
        src = req.get("src")
        if src:
            cypher = (
                "MATCH (d:Delta) WHERE d.ts > $since AND d.src = $src "
                "RETURN d.id, d.src, d.topic, d.ts, d.op, d.payload "
                "ORDER BY d.ts DESC LIMIT {0}".format(limit)
            )
            params = {"since": since, "src": str(src)}
        else:
            cypher = (
                "MATCH (d:Delta) WHERE d.ts > $since "
                "RETURN d.id, d.src, d.topic, d.ts, d.op, d.payload "
                "ORDER BY d.ts DESC LIMIT {0}".format(limit)
            )
            params = {"since": since}
        return result_to_dicts(self.conn.execute(cypher, params))

    def _q_recent_summaries(self, req: dict) -> list:
        level = int(req.get("level", 1))
        since_min = int(req.get("since_min", 60))
        limit = min(int(req.get("limit", DEFAULT_LIMIT)), MAX_LIMIT)
        since = now_ms() - since_min * 60_000
        cypher = (
            "MATCH (s:Summary) "
            "WHERE s.level = $level AND s.end_ts > $since "
            "RETURN s.id, s.level, s.src, s.start_ts, s.end_ts, "
            "       s.text, s.stats "
            "ORDER BY s.end_ts DESC LIMIT {0}".format(limit)
        )
        return result_to_dicts(self.conn.execute(
            cypher, {"level": level, "since": since}))

    def _q_entity_observations(self, req: dict) -> list:
        entity = req.get("entity")
        if not entity:
            raise ValueError("entity_observations requires 'entity'")
        since_min = int(req.get("since_min", 30))
        limit = min(int(req.get("limit", DEFAULT_LIMIT)), MAX_LIMIT)
        since = now_ms() - since_min * 60_000
        cypher = (
            "MATCH (e:Entity {id: $eid})-[:OBSERVES]->(d:Delta) "
            "WHERE d.ts > $since "
            "RETURN e.id, e.name, d.id, d.src, d.topic, d.ts, "
            "       d.op, d.payload "
            "ORDER BY d.ts DESC LIMIT {0}".format(limit)
        )
        return result_to_dicts(self.conn.execute(
            cypher, {"eid": str(entity), "since": since}))

    def _q_cypher(self, req: dict) -> list:
        cypher = req.get("query")
        if not isinstance(cypher, str) or not cypher.strip():
            raise ValueError("cypher requires 'query' string")
        # safety: reject obvious mutations from the read-only socket
        bad = ("CREATE ", "MERGE ", "DELETE ", "DROP ", "SET ", "ALTER ")
        upper = cypher.upper()
        if any(b in upper for b in bad):
            raise ValueError("write Cypher rejected on read-only endpoint")
        params = req.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("'params' must be an object")
        return result_to_dicts(self.conn.execute(cypher, params))

    HANDLERS = {
        "recent_deltas": "_q_recent_deltas",
        "recent_summaries": "_q_recent_summaries",
        "entity_observations": "_q_entity_observations",
        "cypher": "_q_cypher",
    }

    # ----- dispatch -------------------------------------------------------
    def _handle(self, raw: bytes) -> dict:
        try:
            req = json.loads(raw.decode("utf-8", errors="replace"))
        except Exception as exc:
            return {"id": None, "ok": False,
                    "error": "bad JSON: {0}".format(exc)}
        rid = req.get("id")
        rtype = req.get("type")
        handler_name = self.HANDLERS.get(rtype)
        if not handler_name:
            return {"id": rid, "ok": False,
                    "error": "unknown type: {0}".format(rtype)}
        try:
            rows = getattr(self, handler_name)(req)
            return {"id": rid, "ok": True, "rows": rows}
        except Exception as exc:
            LOG.warning("query %s (%s) failed: %s", rid, rtype, exc)
            return {"id": rid, "ok": False, "error": str(exc)}

    # ----- MQTT ----------------------------------------------------------
    def _on_connect(self, client, userdata, flags, rc, properties=None):
        LOG.info("mqtt connected rc=%s", rc)
        client.subscribe(REQ_TOPIC, qos=1)

    def _on_message(self, client, userdata, msg):
        try:
            self.q.put_nowait(msg.payload)
        except queue.Full:
            LOG.warning("query queue full; dropping request")

    def setup_mqtt(self) -> None:
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id="hmg-query")
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.client.connect(self.broker, self.mqtt_port, keepalive=30)
        self.client.loop_start()

    # ----- run loop ------------------------------------------------------
    def run(self) -> None:
        self.open()
        self.setup_mqtt()
        LOG.info("graph_query running; broker=%s db=%s",
                 self.broker, self.db_path)
        try:
            while self.running:
                try:
                    raw = self.q.get(timeout=0.5)
                except queue.Empty:
                    continue
                resp = self._handle(raw)
                try:
                    self.client.publish(
                        RES_TOPIC,
                        json.dumps(resp, separators=(",", ":")),
                        qos=1,
                    )
                except Exception as exc:
                    LOG.exception("publish response failed: %s", exc)
        finally:
            LOG.info("shutting down graph_query")
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

    qd = GraphQuery(broker=args.broker, db_path=Path(args.db),
                    mqtt_port=args.mqtt_port)
    signal.signal(signal.SIGINT, qd.stop)
    signal.signal(signal.SIGTERM, qd.stop)
    qd.run()


if __name__ == "__main__":
    main()
