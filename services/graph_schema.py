#!/usr/bin/env python3
"""graph_schema.py — Hearth Memory Graph (HMG) shared schema.

Idempotent KuzuDB schema for the Hearth aging-in-place app. Defines the
node / rel tables used by graph_logger.py (writer) and graph_query.py
(read-only). Safe to call ensure_schema() on every daemon start.
"""
from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any

import kuzu

LOG = logging.getLogger("graph_schema")

DEFAULT_DB_DIR = Path(
    os.environ.get("HEARTH_HMG_DB", "/home/mendel/hearth/data/hmg.kuzu")
)

# Process-wide shared Database for multi-component daemons. When set,
# open_db() returns a fresh Connection on this Database instead of
# opening a new one — required because KuzuDB enforces a single
# process-level file lock (read_only mode still requires the lock).
_SHARED_DB: Any = None


def set_shared_db(db: Any) -> None:
    """Install a shared kuzu.Database for the current process."""
    global _SHARED_DB
    _SHARED_DB = db

NODE_TABLE_DDL: list[str] = [
    """
    CREATE NODE TABLE IF NOT EXISTS Entity (
        id STRING,
        kind STRING,
        name STRING,
        created_ts INT64,
        PRIMARY KEY (id)
    )
    """,
    """
    CREATE NODE TABLE IF NOT EXISTS Delta (
        id STRING,
        src STRING,
        topic STRING,
        ts INT64,
        op STRING,
        payload STRING,
        PRIMARY KEY (id)
    )
    """,
    """
    CREATE NODE TABLE IF NOT EXISTS Summary (
        id STRING,
        level INT8,
        src STRING,
        start_ts INT64,
        end_ts INT64,
        text STRING,
        stats STRING,
        PRIMARY KEY (id)
    )
    """,
    """
    CREATE NODE TABLE IF NOT EXISTS TimeWindow (
        id STRING,
        level INT8,
        start_ts INT64,
        end_ts INT64,
        PRIMARY KEY (id)
    )
    """,
]

REL_TABLE_DDL: list[str] = [
    "CREATE REL TABLE IF NOT EXISTS OBSERVES (FROM Entity TO Delta, confidence DOUBLE DEFAULT 1.0)",
    "CREATE REL TABLE IF NOT EXISTS AT_TIME (FROM Delta TO TimeWindow)",
    "CREATE REL TABLE IF NOT EXISTS SUMMARIZES (FROM Summary TO Delta)",
    "CREATE REL TABLE IF NOT EXISTS SUMMARIZES_SUMMARY (FROM Summary TO Summary)",
    "CREATE REL TABLE IF NOT EXISTS CONTAINS_TIME (FROM TimeWindow TO TimeWindow)",
    "CREATE REL TABLE IF NOT EXISTS RELATES (FROM Entity TO Entity, kind STRING, ts INT64)",
]


def ensure_schema(conn: "kuzu.Connection") -> None:
    """Idempotently create every node/rel table needed by HMG."""
    for ddl in NODE_TABLE_DDL + REL_TABLE_DDL:
        ddl_clean = " ".join(ddl.split())
        try:
            conn.execute(ddl_clean)
        except Exception as exc:
            LOG.error("schema DDL failed: %s\n  %s", exc, ddl_clean)
            raise
    LOG.info("schema ensured (%d node, %d rel tables)",
             len(NODE_TABLE_DDL), len(REL_TABLE_DDL))


def open_db(db_path: "Path | str" = DEFAULT_DB_DIR, *,
            read_only: bool = False) -> "tuple[kuzu.Database, kuzu.Connection]":
    """Open a Kuzu Database + Connection.

    If a shared Database has been installed via set_shared_db() (e.g. by
    graph_daemon.py), returns that with a new Connection — required for
    multi-component single-process operation (Kuzu enforces a process-level
    file lock).
    """
    if _SHARED_DB is not None:
        return _SHARED_DB, kuzu.Connection(_SHARED_DB)
    db_path = Path(db_path)
    if not read_only:
        db_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        db = kuzu.Database(str(db_path), read_only=read_only)
    except TypeError:
        LOG.warning("kuzu.Database has no read_only kwarg; opening RW")
        db = kuzu.Database(str(db_path))
    conn = kuzu.Connection(db)
    return db, conn


def _coerce(val: Any) -> Any:
    """Coerce Kuzu scalars into JSON-safe Python primitives."""
    if val is None or isinstance(val, (bool, int, float, str)):
        return val
    if isinstance(val, (bytes, bytearray)):
        try:
            return val.decode("utf-8", errors="replace")
        except Exception:
            return list(val)
    if isinstance(val, (list, tuple)):
        return [_coerce(v) for v in val]
    if isinstance(val, dict):
        return {str(k): _coerce(v) for k, v in val.items()}
    return str(val)


def result_to_dicts(result: Any) -> list:
    """Convert a Kuzu QueryResult into list[dict] keyed by column name."""
    rows: list = []
    try:
        cols = result.get_column_names()
    except Exception:
        cols = []
    while result.has_next():
        row = result.get_next()
        rec: dict = {}
        for i, val in enumerate(row):
            key = cols[i] if i < len(cols) else "c{0}".format(i)
            rec[key] = _coerce(val)
        rows.append(rec)
    return rows


__all__ = [
    "DEFAULT_DB_DIR", "NODE_TABLE_DDL", "REL_TABLE_DDL",
    "ensure_schema", "open_db", "result_to_dicts", "_coerce",
]
