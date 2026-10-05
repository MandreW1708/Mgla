#!/usr/bin/env python3
"""Receiver for anonymous Mgla client analytics (POST /mgla-stats/v1/batch).

Standard library only. Stores into SQLite; never records client IPs.
Clients authenticate with the same X-Mgla-Token as the WS relay.
"""

import hmac
import json
import logging
import os
import re
import sqlite3
import threading
import time
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = os.environ.get("MGLA_STATS_HOST", "127.0.0.1")
PORT = int(os.environ.get("MGLA_STATS_PORT", "8767"))
TOKEN = os.environ.get("MGLA_WS_TOKEN", "")
DB_PATH = os.environ.get("MGLA_STATS_DB", "/opt/mgla-ws-relay/stats.db")
RETENTION_DAYS = int(os.environ.get("MGLA_STATS_RETENTION_DAYS", "400"))

MAX_BODY = 256 * 1024
MAX_DAYS = 14
MAX_NAMES_PER_DAY = 400
MAX_COUNT = 100_000
MAX_SETTINGS = 300

IID_RE = re.compile(r"^[0-9a-f]{32}$")
DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
NAME_RE = re.compile(r"^[A-Za-z0-9_:./=-]{1,64}$")
KEY_RE = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")
CTRL_RE = re.compile(r"[\x00-\x1f\x7f]")

log = logging.getLogger("mgla-stats")

SCHEMA = """
CREATE TABLE IF NOT EXISTS installs (
    iid TEXT PRIMARY KEY,
    first_seen TEXT NOT NULL,
    last_seen TEXT NOT NULL,
    ver TEXT, tg TEXT, age_days INTEGER,
    sdk INTEGER, man TEXT, model TEXT, lang TEXT, rom TEXT
);
CREATE TABLE IF NOT EXISTS counters (
    day TEXT NOT NULL,
    iid TEXT NOT NULL,
    name TEXT NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (day, iid, name)
);
CREATE INDEX IF NOT EXISTS counters_name ON counters(name, day);
CREATE TABLE IF NOT EXISTS search_miss (
    day TEXT NOT NULL,
    query TEXT NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (day, query)
);
CREATE TABLE IF NOT EXISTS snapshot (
    iid TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (iid, key)
);
"""


class Store:
    def __init__(self, path):
        self._lock = threading.Lock()
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(SCHEMA)
        self._db.commit()
        self._last_cleanup = 0.0

    def ingest(self, batch):
        today = datetime.now(timezone.utc).date()
        iid = batch["iid"]
        app = batch.get("app") or {}
        dev = batch.get("dev") or {}
        with self._lock, self._db:
            db = self._db
            db.execute(
                """INSERT INTO installs(iid, first_seen, last_seen, ver, tg, age_days, sdk, man, model, lang, rom)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(iid) DO UPDATE SET last_seen=excluded.last_seen, ver=excluded.ver,
                     tg=excluded.tg, age_days=excluded.age_days, sdk=excluded.sdk, man=excluded.man,
                     model=excluded.model, lang=excluded.lang, rom=excluded.rom""",
                (iid, today.isoformat(), today.isoformat(),
                 _short(app.get("ver")), _short(app.get("tg")), _int(app.get("age_days")),
                 _int(dev.get("sdk")), _short(dev.get("man")), _short(dev.get("model")),
                 _short(dev.get("lang"), 8), _short(dev.get("rom"), 16)))

            for day, data in batch["days"].items():
                for name, count in (data.get("c") or {}).items():
                    db.execute(
                        """INSERT INTO counters(day, iid, name, count) VALUES(?,?,?,?)
                           ON CONFLICT(day, iid, name) DO UPDATE SET count = count + excluded.count""",
                        (day, iid, name, count))
                for query, count in (data.get("m") or {}).items():
                    db.execute(
                        """INSERT INTO search_miss(day, query, count) VALUES(?,?,?)
                           ON CONFLICT(day, query) DO UPDATE SET count = count + excluded.count""",
                        (day, query, count))

            settings = batch.get("settings")
            if settings:
                db.execute("DELETE FROM snapshot WHERE iid = ?", (iid,))
                db.executemany(
                    "INSERT INTO snapshot(iid, key, value) VALUES(?,?,?)",
                    [(iid, k, v) for k, v in settings.items()])

            if time.time() - self._last_cleanup > 3600:
                self._last_cleanup = time.time()
                cutoff = (today - timedelta(days=RETENTION_DAYS)).isoformat()
                db.execute("DELETE FROM counters WHERE day < ?", (cutoff,))
                db.execute("DELETE FROM search_miss WHERE day < ?", (cutoff,))
                stale = [r[0] for r in db.execute("SELECT iid FROM installs WHERE last_seen < ?", (cutoff,))]
                for s in stale:
                    db.execute("DELETE FROM snapshot WHERE iid = ?", (s,))
                    db.execute("DELETE FROM installs WHERE iid = ?", (s,))


def _short(v, limit=64):
    if v is None:
        return None
    return CTRL_RE.sub("", str(v))[:limit]


def _int(v):
    if isinstance(v, bool) or not isinstance(v, int):
        return None
    return v


def validate(raw):
    """Returns a cleaned batch, or raises ValueError."""
    if not isinstance(raw, dict) or raw.get("v") != 1:
        raise ValueError("bad version")
    iid = raw.get("iid")
    if not isinstance(iid, str) or not IID_RE.match(iid):
        raise ValueError("bad iid")

    today = datetime.now(timezone.utc).date()
    earliest = today - timedelta(days=30)
    latest = today + timedelta(days=1)

    days_in = raw.get("days") or {}
    if not isinstance(days_in, dict) or len(days_in) > MAX_DAYS:
        raise ValueError("bad days")
    days = {}
    for day, data in days_in.items():
        if not isinstance(day, str) or not DAY_RE.match(day) or not isinstance(data, dict):
            raise ValueError("bad day")
        try:
            d = date.fromisoformat(day)
        except ValueError:
            raise ValueError("bad day")
        if d < earliest or d > latest:
            continue
        counters = {}
        for name, count in (data.get("c") or {}).items():
            if len(counters) >= MAX_NAMES_PER_DAY:
                break
            if isinstance(name, str) and NAME_RE.match(name) and _valid_count(count):
                counters[name] = count
        misses = {}
        for query, count in (data.get("m") or {}).items():
            if len(misses) >= MAX_NAMES_PER_DAY:
                break
            if isinstance(query, str) and _valid_count(count):
                q = CTRL_RE.sub("", query).strip().lower()[:40]
                if len(q) >= 3:
                    misses[q] = misses.get(q, 0) + count
        days[day] = {"c": counters, "m": misses}

    settings = {}
    settings_in = raw.get("settings") or {}
    if isinstance(settings_in, dict):
        for key, value in settings_in.items():
            if len(settings) >= MAX_SETTINGS:
                break
            if not isinstance(key, str) or not KEY_RE.match(key):
                continue
            if isinstance(value, bool):
                settings[key] = "true" if value else "false"
            elif isinstance(value, (int, float)):
                settings[key] = str(value)
            elif isinstance(value, str) and len(value) <= 32:
                settings[key] = CTRL_RE.sub("", value)

    return {
        "iid": iid,
        "app": raw.get("app") if isinstance(raw.get("app"), dict) else {},
        "dev": raw.get("dev") if isinstance(raw.get("dev"), dict) else {},
        "days": days,
        "settings": settings,
    }


def _valid_count(c):
    return isinstance(c, int) and not isinstance(c, bool) and 0 < c <= MAX_COUNT


class Handler(BaseHTTPRequestHandler):
    server_version = "mgla-stats"
    sys_version = ""
    store = None

    def log_message(self, fmt, *args):
        # The default handler logs the client address; analytics must stay anonymous.
        pass

    def _reply(self, code, body=b""):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        if self.path == "/mgla-stats/v1/health":
            self._reply(200, b'{"ok":true}')
        else:
            self._reply(404)

    def do_POST(self):
        if self.path != "/mgla-stats/v1/batch":
            self._reply(404)
            return
        token = self.headers.get("X-Mgla-Token", "")
        if not TOKEN or not hmac.compare_digest(token.encode(), TOKEN.encode()):
            self._reply(401)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length <= 0 or length > MAX_BODY:
            self._reply(413)
            return
        try:
            batch = validate(json.loads(self.rfile.read(length).decode("utf-8")))
        except (ValueError, UnicodeDecodeError):
            self._reply(400)
            return
        try:
            self.store.ingest(batch)
        except sqlite3.Error:
            log.exception("ingest failed")
            self._reply(500)
            return
        self._reply(200, b'{"ok":true}')


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if not TOKEN:
        raise SystemExit("MGLA_WS_TOKEN is not set")
    Handler.store = Store(DB_PATH)
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    log.info("listening on %s:%d, db=%s", HOST, PORT, DB_PATH)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
