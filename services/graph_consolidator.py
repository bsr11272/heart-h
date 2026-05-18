#!/usr/bin/env python3
"""graph_consolidator.py - Hearth Memory Graph summarization daemon.

Periodically walks the Hearth Memory Graph (HMG, KuzuDB at
``/home/mendel/hearth/data/hmg.kuzu``) and rolls recent Delta nodes up
into hierarchical Summary nodes using the Gemini API (free tier
``gemini-2.5-flash``).

Three passes per minute:
  * Pass A - per-sensor 5-min summaries (level 1)
  * Pass B - global hourly summaries (level 2) built from level-1 summaries
  * Pass C - global daily summaries (level 3) built from level-2 summaries

Concurrency with graph_logger.py: opens short-lived KuzuDB connections only
when writing, releases the lock between ops, retries on contention.

Gemini key from GEMINI_API_KEY env var. If unset/fails, falls back to
deterministic count-based summaries so the demo still works offline.

Run on Coral:
    /home/mendel/hearth/.dep-kuzu/bin/python graph_consolidator.py \\
        --db /home/mendel/hearth/data/hmg.kuzu
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import signal
import sys
import time
from collections import Counter
from typing import Any, Iterable

import kuzu

try:
    import google.generativeai as genai  # type: ignore
    _HAS_GENAI = True
except Exception:
    genai = None  # type: ignore
    _HAS_GENAI = False

try:
    from graph_schema import ensure_schema  # type: ignore
except Exception:
    ensure_schema = None  # type: ignore


LOG = logging.getLogger("hearth.consolidator")

PERSONA_PROMPTS: dict = {
    "camera": (
        "You are the Hearth camera observer for Margaret's home. "
        "Given a list of camera object-detection / pose / fall / GAIT events "
        "from a single 5-minute window, write ONE concise sentence "
        "(max 25 words) describing what was visible. Focus on: people present, "
        "what they did, any fall events, and any GAIT snapshot trends "
        "(cadence in spm, stride length, swing asymmetry). For gait, call "
        "out 'normal' (>=100 spm, <10% asym), 'slow' (80-100 spm) or "
        "'concerning' (<80 spm or >15% asym). Ignore inanimate object "
        "detections unless unusual."
    ),
    "radar": (
        "You are the Hearth radar (LD2450 mmWave) observer for Margaret's "
        "home. Given target positions + presence events from a single 5-minute "
        "window per room, write ONE concise sentence (max 25 words) describing "
        "presence and motion. Mention the room name and whether it was "
        "occupied, briefly visited, or empty."
    ),
    "pendant": (
        "You are the Hearth UWB pendant observer tracking Margaret. Given "
        "position events from a single 5-minute window, write ONE concise "
        "sentence (max 25 words) describing where she was and whether she was "
        "moving."
    ),
    "vision": (
        "You are the Hearth vision-fall observer (Coral Edge TPU pose). Given "
        "vision fall events from a 5-minute window, write ONE concise sentence "
        "(max 25 words) describing the event severity and confidence."
    ),
    "audio": (
        "You are the Hearth ambient audio observer (YamNet on Coral). Given a "
        "list of audio events from a single 5-minute window, write ONE concise "
        "sentence (max 25 words) describing the soundscape. Each event has a "
        "label (e.g. 'Glass', 'Television', 'Doorbell'), a severity ('info' / "
        "'warn' / 'alert'), and a confidence. ALWAYS lead with any 'alert' "
        "severity items first, then 'warn', then briefly note any ambient "
        "INFO events (TV, water, microwave) as a group. If only INFO events "
        "happened, describe the household sound activity neutrally."
    ),
}

GLOBAL_HOUR_PROMPT = (
    "You are the Hearth global memory summarizer. Given per-sensor summaries "
    "from a 1-hour window, write 2-3 sentences (max 50 words total) describing "
    "Margaret's activity. Lead with the most clinically/safety-relevant "
    "signal. Mention rooms and time-of-window context."
)

GLOBAL_DAY_PROMPT = (
    "You are the Hearth global memory summarizer producing a daily digest. "
    "Given hourly summaries, write a 3-5 sentence daily summary (max 100 "
    "words) covering rooms occupied, key events, anomalies, and any falls or "
    "alerts."
)

MIN5_MS = 5 * 60 * 1000
HOUR_MS = 60 * 60 * 1000
DAY_MS = 24 * 60 * 60 * 1000

FRESH_GUARD_5MIN_MS = 5_000
FRESH_GUARD_HOUR_MS = 60_000
FRESH_GUARD_DAY_MS = 300_000

MAX_WINDOWS_PER_PASS = 4
MAX_API_CALLS_PER_MINUTE = 30
WRITE_RETRY_MAX = 3
WRITE_RETRY_BASE_SEC = 0.5

GEMINI_MODEL = "gemini-2.5-flash"


def now_ms() -> int:
    return int(time.time() * 1000)


class GeminiClient:
    def __init__(self, model_name: str = GEMINI_MODEL) -> None:
        self.model_name = model_name
        self._calls: list = []
        self._model = None
        self._enabled = False
        key = os.environ.get("GEMINI_API_KEY")
        if key and _HAS_GENAI:
            try:
                genai.configure(api_key=key)
                self._model = genai.GenerativeModel(model_name)
                self._enabled = True
                LOG.info("Gemini client enabled (model=%s)", model_name)
            except Exception as exc:
                LOG.warning("Gemini init failed (%s); using fallback summaries", exc)
        else:
            if not _HAS_GENAI:
                LOG.warning("google-generativeai not importable; using fallback summaries")
            else:
                LOG.warning("GEMINI_API_KEY not set; using fallback summaries")

    @property
    def enabled(self) -> bool:
        return self._enabled

    def _rate_limit(self) -> None:
        now = time.time()
        self._calls = [t for t in self._calls if now - t < 60.0]
        if len(self._calls) >= MAX_API_CALLS_PER_MINUTE:
            wait = 60.0 - (now - self._calls[0]) + 0.05
            if wait > 0:
                LOG.info("Gemini rate-limit hit; sleeping %.2fs", wait)
                time.sleep(wait)

    def generate(self, system_instruction: str, user_text: str, fallback_hint: str) -> str:
        if not self._enabled or self._model is None:
            return fallback_hint
        try:
            self._rate_limit()
            self._calls.append(time.time())
            prompt = "{0}\n\nData:\n{1}\n\nSummary:".format(system_instruction, user_text)
            resp = self._model.generate_content(prompt)
            text = (getattr(resp, "text", "") or "").strip()
            if not text:
                LOG.warning("Gemini returned empty text; using fallback")
                return fallback_hint
            return text
        except Exception as exc:
            LOG.warning("Gemini call failed (%s); using fallback", exc)
            return fallback_hint


class ShortLivedDB:
    def __init__(self, db_path: str) -> None:
        self.db_path = db_path
        self.db = None
        self.conn = None

    def __enter__(self) -> "ShortLivedDB":
        # Route through graph_schema.open_db so the shared-Database singleton
        # is honored when the consolidator runs in-process with logger/query.
        from graph_schema import open_db
        last_exc = None
        for attempt in range(WRITE_RETRY_MAX):
            try:
                self.db, self.conn = open_db(self.db_path)
                return self
            except Exception as exc:
                last_exc = exc
                backoff = WRITE_RETRY_BASE_SEC * (2 ** attempt)
                LOG.warning(
                    "KuzuDB open failed (attempt %d/%d): %s; retrying in %.2fs",
                    attempt + 1, WRITE_RETRY_MAX, exc, backoff,
                )
                time.sleep(backoff)
        assert last_exc is not None
        raise last_exc

    def __exit__(self, exc_type, exc, tb) -> None:
        self.conn = None
        self.db = None

    def query(self, cypher: str, params: dict = None) -> list:
        assert self.conn is not None
        result = self.conn.execute(cypher, parameters=params or {})
        rows = []
        cols = result.get_column_names()
        while result.has_next():
            values = result.get_next()
            rows.append({c: v for c, v in zip(cols, values)})
        return rows

    def execute(self, cypher: str, params: dict = None) -> None:
        assert self.conn is not None
        self.conn.execute(cypher, parameters=params or {})


def fallback_sensor_summary(src: str, ops: list) -> str:
    count = len(ops)
    op_counts = Counter(ops)
    parts = ["{0} {1}".format(n, op) for op, n in op_counts.most_common()]
    detail = ", ".join(parts) if parts else "no events"
    return "[fallback] {0}: {1} deltas ({2})".format(src, count, detail)


def fallback_global_summary(level_name: str, lines: list) -> str:
    bullet = "; ".join(s for s in lines if s)
    return "[fallback {0}] {1} summaries: {2}".format(level_name, len(lines), bullet[:300])


# -------- Pass A --------

def find_pending_5min_windows(db: ShortLivedDB, now: int) -> list:
    cutoff = now - FRESH_GUARD_5MIN_MS
    rows = db.query(
        """
        MATCH (tw:TimeWindow)
        WHERE tw.level = 1 AND tw.end_ts < $cutoff
        OPTIONAL MATCH (d:Delta)-[:AT_TIME]->(tw)
        WITH tw, count(d) AS n_deltas
        WHERE n_deltas > 0
        RETURN tw.id AS id, tw.start_ts AS start_ts, tw.end_ts AS end_ts
        ORDER BY tw.start_ts ASC
        LIMIT $cap
        """,
        {"cutoff": cutoff, "cap": MAX_WINDOWS_PER_PASS * 4},
    )
    return [(r["id"], int(r["start_ts"]), int(r["end_ts"])) for r in rows[:MAX_WINDOWS_PER_PASS]]


def existing_level1_srcs(db: ShortLivedDB, start_ts: int) -> set:
    rows = db.query(
        "MATCH (s:Summary) WHERE s.level = 1 AND s.start_ts = $start_ts RETURN s.src AS src",
        {"start_ts": start_ts},
    )
    return {r["src"] for r in rows if r.get("src")}


def deltas_in_window_by_src(db: ShortLivedDB, tw_id: str, src: str) -> list:
    return db.query(
        """
        MATCH (d:Delta)-[:AT_TIME]->(tw:TimeWindow {id: $tw_id})
        WHERE d.src = $src
        RETURN d.id AS id, d.op AS op, d.ts AS ts, d.payload AS payload,
               d.topic AS topic
        ORDER BY d.ts ASC
        """,
        {"tw_id": tw_id, "src": src},
    )


def build_packet_for_sensor(src: str, deltas: list) -> str:
    lines = ["sensor={0} count={1}".format(src, len(deltas))]
    for d in deltas:
        payload = d.get("payload", "")
        if isinstance(payload, (dict, list)):
            payload_str = json.dumps(payload, separators=(",", ":"))[:200]
        else:
            payload_str = str(payload)[:200]
        lines.append(
            "  t={0} op={1} topic={2} payload={3}".format(
                d.get('ts'), d.get('op', ''), d.get('topic', ''), payload_str
            )
        )
    return "\n".join(lines)


def write_level1_summary(
    db: ShortLivedDB, summary_id: str, src: str, start_ts: int, end_ts: int,
    text: str, stats: dict, covered_delta_ids: Iterable,
) -> None:
    stats_json = json.dumps(stats, separators=(",", ":"))
    db.execute(
        """
        MERGE (s:Summary {id: $id})
        ON CREATE SET s.level = 1, s.src = $src,
                      s.start_ts = $start_ts, s.end_ts = $end_ts,
                      s.text = $text, s.stats = $stats
        """,
        {"id": summary_id, "src": src, "start_ts": start_ts, "end_ts": end_ts,
         "text": text, "stats": stats_json},
    )
    for did in covered_delta_ids:
        db.execute(
            """
            MATCH (s:Summary {id: $sid}), (d:Delta {id: $did})
            MERGE (s)-[:SUMMARIZES]->(d)
            """,
            {"sid": summary_id, "did": did},
        )


def pass_a(db_path: str, gemini: GeminiClient) -> int:
    created = 0
    with ShortLivedDB(db_path) as db:
        pending = find_pending_5min_windows(db, now_ms())
    if not pending:
        return 0
    for tw_id, start_ts, end_ts in pending:
        with ShortLivedDB(db_path) as db:
            already = existing_level1_srcs(db, start_ts)
            rows = db.query(
                "MATCH (d:Delta)-[:AT_TIME]->(tw:TimeWindow {id: $tw_id}) RETURN DISTINCT d.src AS src",
                {"tw_id": tw_id},
            )
            srcs_in_window = {r["src"] for r in rows if r.get("src")}
        todo = sorted(srcs_in_window - already)
        if not todo:
            continue
        for src in todo:
            with ShortLivedDB(db_path) as db:
                deltas = deltas_in_window_by_src(db, tw_id, src)
            if len(deltas) < 2:
                continue
            ops = [d.get("op", "") for d in deltas]
            stats = {
                "count": len(deltas),
                "ops": dict(Counter(ops)),
                "first_ts": int(deltas[0]["ts"]),
                "last_ts": int(deltas[-1]["ts"]),
            }
            packet = build_packet_for_sensor(src, deltas)
            persona = PERSONA_PROMPTS.get(src) or (
                "You are the Hearth {0} observer. Summarize in one sentence (max 25 words).".format(src)
            )
            fallback = fallback_sensor_summary(src, ops)
            text = gemini.generate(persona, packet, fallback)
            summary_id = "sum:5min:{0}:{1}".format(start_ts, src)
            try:
                with ShortLivedDB(db_path) as db:
                    write_level1_summary(
                        db, summary_id, src, start_ts, end_ts, text, stats,
                        [d["id"] for d in deltas],
                    )
                created += 1
                LOG.info("L1 summary %s (%d deltas)", summary_id, len(deltas))
            except Exception as exc:
                LOG.error("write L1 summary %s failed: %s", summary_id, exc)
    return created


# -------- Pass B --------

def find_pending_hour_windows(db: ShortLivedDB, now: int) -> list:
    cutoff = now - FRESH_GUARD_HOUR_MS
    rows = db.query(
        """
        MATCH (tw:TimeWindow)
        WHERE tw.level = 2 AND tw.end_ts < $cutoff
        OPTIONAL MATCH (s:Summary) WHERE s.level = 2 AND s.start_ts = tw.start_ts
        WITH tw, count(s) AS n_existing
        WHERE n_existing = 0
        RETURN tw.id AS id, tw.start_ts AS start_ts, tw.end_ts AS end_ts
        ORDER BY tw.start_ts ASC
        LIMIT $cap
        """,
        {"cutoff": cutoff, "cap": MAX_WINDOWS_PER_PASS},
    )
    return [(r["id"], int(r["start_ts"]), int(r["end_ts"])) for r in rows]


def level1_summaries_in_range(db: ShortLivedDB, start_ts: int, end_ts: int) -> list:
    return db.query(
        """
        MATCH (s:Summary)
        WHERE s.level = 1 AND s.start_ts >= $start_ts AND s.start_ts < $end_ts
        RETURN s.id AS id, s.src AS src, s.start_ts AS start_ts,
               s.end_ts AS end_ts, s.text AS text
        ORDER BY s.start_ts ASC, s.src ASC
        """,
        {"start_ts": start_ts, "end_ts": end_ts},
    )


def write_higher_summary(
    db: ShortLivedDB, summary_id: str, level: int, start_ts: int, end_ts: int,
    text: str, stats: dict, child_summary_ids: Iterable,
) -> None:
    stats_json = json.dumps(stats, separators=(",", ":"))
    db.execute(
        """
        MERGE (s:Summary {id: $id})
        ON CREATE SET s.level = $level, s.src = 'global',
                      s.start_ts = $start_ts, s.end_ts = $end_ts,
                      s.text = $text, s.stats = $stats
        """,
        {"id": summary_id, "level": level, "start_ts": start_ts,
         "end_ts": end_ts, "text": text, "stats": stats_json},
    )
    for cid in child_summary_ids:
        db.execute(
            """
            MATCH (parent:Summary {id: $pid}), (child:Summary {id: $cid})
            MERGE (parent)-[:SUMMARIZES_SUMMARY]->(child)
            """,
            {"pid": summary_id, "cid": cid},
        )


def pass_b(db_path: str, gemini: GeminiClient) -> int:
    created = 0
    with ShortLivedDB(db_path) as db:
        pending = find_pending_hour_windows(db, now_ms())
    if not pending:
        return 0
    for tw_id, start_ts, end_ts in pending:
        with ShortLivedDB(db_path) as db:
            kids = level1_summaries_in_range(db, start_ts, end_ts)
        if not kids:
            continue
        packet = "\n".join("[{0}] {1}".format(k['src'], k['text']) for k in kids)
        stats = {"n_l1": len(kids), "srcs": dict(Counter(k["src"] for k in kids))}
        fallback = fallback_global_summary("hour", [k["text"] for k in kids])
        text = gemini.generate(GLOBAL_HOUR_PROMPT, packet, fallback)
        sid = "sum:hour:{0}:global".format(start_ts)
        try:
            with ShortLivedDB(db_path) as db:
                write_higher_summary(db, sid, 2, start_ts, end_ts, text, stats,
                                     [k["id"] for k in kids])
            created += 1
            LOG.info("L2 hour summary %s (%d L1 kids)", sid, len(kids))
        except Exception as exc:
            LOG.error("write hour summary %s failed: %s", sid, exc)
    return created


# -------- Pass C --------

def find_pending_day_windows(db: ShortLivedDB, now: int) -> list:
    cutoff = now - FRESH_GUARD_DAY_MS
    rows = db.query(
        """
        MATCH (tw:TimeWindow)
        WHERE tw.level = 3 AND tw.end_ts < $cutoff
        OPTIONAL MATCH (s:Summary) WHERE s.level = 3 AND s.start_ts = tw.start_ts
        WITH tw, count(s) AS n_existing
        WHERE n_existing = 0
        RETURN tw.id AS id, tw.start_ts AS start_ts, tw.end_ts AS end_ts
        ORDER BY tw.start_ts ASC
        LIMIT $cap
        """,
        {"cutoff": cutoff, "cap": MAX_WINDOWS_PER_PASS},
    )
    return [(r["id"], int(r["start_ts"]), int(r["end_ts"])) for r in rows]


def level2_summaries_in_range(db: ShortLivedDB, start_ts: int, end_ts: int) -> list:
    return db.query(
        """
        MATCH (s:Summary)
        WHERE s.level = 2 AND s.start_ts >= $start_ts AND s.start_ts < $end_ts
        RETURN s.id AS id, s.start_ts AS start_ts, s.end_ts AS end_ts, s.text AS text
        ORDER BY s.start_ts ASC
        """,
        {"start_ts": start_ts, "end_ts": end_ts},
    )


def pass_c(db_path: str, gemini: GeminiClient) -> int:
    created = 0
    with ShortLivedDB(db_path) as db:
        pending = find_pending_day_windows(db, now_ms())
    if not pending:
        return 0
    for tw_id, start_ts, end_ts in pending:
        with ShortLivedDB(db_path) as db:
            kids = level2_summaries_in_range(db, start_ts, end_ts)
        if not kids:
            continue
        packet = "\n".join(
            "+{0:02d}h: {1}".format((k['start_ts'] - start_ts) // HOUR_MS, k['text']) for k in kids
        )
        stats = {"n_l2": len(kids)}
        fallback = fallback_global_summary("day", [k["text"] for k in kids])
        text = gemini.generate(GLOBAL_DAY_PROMPT, packet, fallback)
        sid = "sum:day:{0}:global".format(start_ts)
        try:
            with ShortLivedDB(db_path) as db:
                write_higher_summary(db, sid, 3, start_ts, end_ts, text, stats,
                                     [k["id"] for k in kids])
            created += 1
            LOG.info("L3 day summary %s (%d L2 kids)", sid, len(kids))
        except Exception as exc:
            LOG.error("write day summary %s failed: %s", sid, exc)
    return created


# -------- Main loop --------

_STOP = False


def _on_signal(signum, _frame) -> None:
    global _STOP
    LOG.info("signal %s received; shutting down", signum)
    _STOP = True


def run_once(db_path: str, gemini: GeminiClient) -> None:
    a = pass_a(db_path, gemini)
    b = pass_b(db_path, gemini)
    c = pass_c(db_path, gemini)
    if a or b or c:
        LOG.info("tick: created L1=%d L2=%d L3=%d", a, b, c)


def main() -> int:
    parser = argparse.ArgumentParser(description="Hearth Memory Graph consolidator")
    parser.add_argument("--db", default="/home/mendel/hearth/data/hmg.kuzu")
    parser.add_argument("--interval", type=int, default=60)
    parser.add_argument("--log-level", default="INFO")
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s | %(message)s",
        stream=sys.stderr,
    )
    signal.signal(signal.SIGINT, _on_signal)
    signal.signal(signal.SIGTERM, _on_signal)

    if ensure_schema is not None:
        try:
            with ShortLivedDB(args.db) as db:
                ensure_schema(db.conn)
            LOG.info("schema ensured via graph_schema.ensure_schema")
        except Exception as exc:
            LOG.warning("ensure_schema failed (%s); assuming logger has run", exc)

    gemini = GeminiClient()
    LOG.info("graph_consolidator started (db=%s interval=%ss)", args.db, args.interval)

    if args.once:
        run_once(args.db, gemini)
        return 0

    while not _STOP:
        tick_started = time.time()
        try:
            run_once(args.db, gemini)
        except Exception as exc:
            LOG.exception("tick failed: %s", exc)
        elapsed = time.time() - tick_started
        end = time.time() + max(1.0, args.interval - elapsed)
        while not _STOP and time.time() < end:
            time.sleep(min(1.0, end - time.time()))

    LOG.info("graph_consolidator stopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
