#!/usr/bin/env python3
"""graph_daemon.py — single-process HMG orchestrator.

KuzuDB enforces a process-level file lock; multiple OS processes can't
open the same database even read-only. So we run all three HMG roles
(logger, query, consolidator) as threads inside ONE process, sharing one
kuzu.Database object (which IS thread-safe). Each thread holds its own
kuzu.Connection.

Run:
  /home/mendel/hearth/.dep-kuzu/bin/python services/graph_daemon.py \\
    --broker localhost --db /home/mendel/hearth/data/hmg.kuzu

Logs all three components into the same stream — `tail -f` on
graph-daemon.log gets logger + query + consolidator output combined.
"""
from __future__ import annotations

import argparse
import logging
import signal
import sys
import threading
import time
from pathlib import Path

import kuzu

from graph_schema import DEFAULT_DB_DIR, ensure_schema, set_shared_db
from graph_logger import GraphLogger
from graph_query import GraphQuery
import graph_consolidator

LOG = logging.getLogger("graph_daemon")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--broker", default="localhost")
    ap.add_argument("--mqtt-port", type=int, default=1883)
    ap.add_argument("--db", default=str(DEFAULT_DB_DIR))
    ap.add_argument("--consolidator-interval", type=int, default=60)
    ap.add_argument("--log-level", default="INFO")
    args = ap.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s | %(message)s",
        stream=sys.stderr,
    )

    db_path = Path(args.db)
    db_path.parent.mkdir(parents=True, exist_ok=True)

    LOG.info("opening shared KuzuDB at %s", db_path)
    shared_db = kuzu.Database(str(db_path))
    set_shared_db(shared_db)
    # Ensure schema once on a throwaway connection.
    ensure_schema(kuzu.Connection(shared_db))

    stop = threading.Event()

    def _on_signal(signum, _frame):
        LOG.info("signal %s received; shutting down", signum)
        stop.set()
        # Best-effort stop of subclassed daemons:
        for d in (logger_daemon, query_daemon):
            try:
                d.stop()
            except Exception:
                pass
        # Set consolidator module-level flag
        try:
            graph_consolidator._STOP = True  # type: ignore[attr-defined]
        except Exception:
            pass

    # ----- logger thread -----
    logger_daemon = GraphLogger(
        broker=args.broker, db_path=db_path, mqtt_port=args.mqtt_port,
    )
    logger_thread = threading.Thread(
        target=logger_daemon.run, name="hmg-logger", daemon=True,
    )

    # ----- query thread -----
    query_daemon = GraphQuery(
        broker=args.broker, db_path=db_path, mqtt_port=args.mqtt_port,
    )
    query_thread = threading.Thread(
        target=query_daemon.run, name="hmg-query", daemon=True,
    )

    # ----- consolidator thread -----
    gemini_client = graph_consolidator.GeminiClient()

    def _consolidator_loop():
        LOG.info("consolidator loop started (interval=%ss)",
                 args.consolidator_interval)
        while not stop.is_set():
            t0 = time.time()
            try:
                graph_consolidator.run_once(str(db_path), gemini_client)
            except Exception as exc:
                LOG.exception("consolidator tick failed: %s", exc)
            end = t0 + max(1.0, args.consolidator_interval)
            while not stop.is_set() and time.time() < end:
                time.sleep(min(1.0, end - time.time()))
        LOG.info("consolidator loop stopped")

    consolidator_thread = threading.Thread(
        target=_consolidator_loop, name="hmg-consolidator", daemon=True,
    )

    signal.signal(signal.SIGINT, _on_signal)
    signal.signal(signal.SIGTERM, _on_signal)

    logger_thread.start()
    # Give logger a moment to settle (schema + entity seeding)
    time.sleep(1.0)
    query_thread.start()
    time.sleep(0.5)
    consolidator_thread.start()

    LOG.info("graph_daemon up: logger=%s query=%s consolidator=%s",
             logger_thread.is_alive(), query_thread.is_alive(),
             consolidator_thread.is_alive())

    try:
        while not stop.is_set():
            time.sleep(1.0)
            # If any worker died, shut down so systemd / supervisor can restart
            if not logger_thread.is_alive():
                LOG.error("logger thread died; exiting")
                break
            if not query_thread.is_alive():
                LOG.error("query thread died; exiting")
                break
            if not consolidator_thread.is_alive():
                LOG.error("consolidator thread died; exiting")
                break
    except KeyboardInterrupt:
        pass
    finally:
        _on_signal("internal", None)
        for t in (logger_thread, query_thread, consolidator_thread):
            t.join(timeout=3.0)
        LOG.info("graph_daemon exit")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
