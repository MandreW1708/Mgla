#!/usr/bin/env python3
"""Mgla Hub backend: analytics + OpenRouter AI proxy.

- POST /mgla-stats/v1/batch — anonymous client analytics (SQLite; no client IPs)
- POST /mgla-ai/v1/chat — proxies chat to OpenRouter; API key stays server-side

Standard library only. Clients authenticate with the same X-Mgla-Token as the WS relay.
"""

import base64
import hmac
import json
import logging
import os
import random
import re
import sqlite3
import threading
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

import stats_report

HOST = os.environ.get("MGLA_STATS_HOST", "127.0.0.1")
PORT = int(os.environ.get("MGLA_STATS_PORT", "8767"))
TOKEN = os.environ.get("MGLA_WS_TOKEN", "")
DB_PATH = os.environ.get("MGLA_STATS_DB", "/opt/mgla-ws-relay/stats.db")
RETENTION_DAYS = int(os.environ.get("MGLA_STATS_RETENTION_DAYS", "400"))
REPORT_USER = os.environ.get("MGLA_STATS_REPORT_USER", "admin")
REPORT_PASSWORD = os.environ.get("MGLA_STATS_REPORT_PASSWORD", "")
OPENROUTER_API_KEY = os.environ.get("OPENROUTER_API_KEY", "").strip()

MAX_BODY = 256 * 1024
MAX_AI_BODY = 32 * 1024
MAX_AI_MESSAGE = 12_000
MAX_DAYS = 14
MAX_NAMES_PER_DAY = 400
MAX_COUNT = 100_000
MAX_SETTINGS = 300

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
# Same free-model order as the former in-app AiAssistant.
AI_MODELS = (
    "nvidia/nemotron-3-super-120b-a12b:free",
    "nvidia/nemotron-3-ultra-550b-a55b:free",
    "nvidia/nemotron-3.5-lightning:free",
    "nex-agi/nex-n2.5-pro:free",
    "google/gemma-4-31b-it:free",
)

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


def _ai_system_prompt(lang: str) -> str:
    lang_name = lang.strip() if isinstance(lang, str) and lang.strip() else "Russian"
    lang_name = CTRL_RE.sub("", lang_name)[:80]
    return (
        "Ты — инструмент обработки текста. Каждый запрос НЕЗАВИСИМЫЙ. НЕТ истории. "
        "НЕТ памяти. НЕ здоровайся. НЕ прощайся. НЕ комментируй. НЕ упоминай предыдущие "
        "запросы. ТОЛЬКО результат. ВАЖНО: Всегда отвечай на языке, который установлен "
        "в настройках клиента по умолчанию (" + lang_name + "), если в самом запросе "
        "явно не требуется перевод на другой язык."
    )


def _extract_openrouter_content(payload: dict) -> str:
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ValueError("no choices")
    msg = choices[0].get("message") if isinstance(choices[0], dict) else None
    if not isinstance(msg, dict):
        raise ValueError("no message")
    content = msg.get("content")
    if not isinstance(content, str) or not content:
        raise ValueError("empty content")
    return content


def call_openrouter(message: str, lang: str) -> str:
    """Try models in order; raise RuntimeError if all fail."""
    if not OPENROUTER_API_KEY:
        raise RuntimeError("OPENROUTER_API_KEY is not set")
    system_prompt = _ai_system_prompt(lang)
    seed = random.getrandbits(63)
    failures = []
    last_err = None
    for i, model in enumerate(AI_MODELS):
        body = {
            "model": model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": message},
            ],
            "temperature": 0.3,
            "max_tokens": 1024,
            "seed": seed,
        }
        req = urllib.request.Request(
            OPENROUTER_URL,
            data=json.dumps(body).encode("utf-8"),
            method="POST",
            headers={
                "Authorization": "Bearer " + OPENROUTER_API_KEY,
                "Content-Type": "application/json",
                "HTTP-Referer": "https://mgla.app",
                "X-Title": "Mgla",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
                payload = json.loads(raw)
                return _extract_openrouter_content(payload)
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")[:500]
            last_err = f"{model}: HTTP {e.code}"
            failures.append(last_err)
            if e.code in (404, 429) and i < len(AI_MODELS) - 1:
                continue
            if e.code in (401, 402, 403):
                raise RuntimeError(f"OpenRouter {e.code}: {err_body}") from e
            if i < len(AI_MODELS) - 1:
                continue
        except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as e:
            last_err = f"{model}: {e}"
            failures.append(last_err)
            if i < len(AI_MODELS) - 1:
                continue
    raise RuntimeError("Все модели недоступны (" + "; ".join(failures) + ")")


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
        url = urlsplit(self.path)
        path = url.path.rstrip("/") or "/"
        if path in ("/mgla-stats/v1/health", "/health"):
            self._reply(200, b'{"ok":true}')
        elif path in ("/", "/report", "/mgla-stats/report", "/hub", "/dashboard"):
            self._report(parse_qs(url.query))
        else:
            self._reply(404)

    def _report(self, query):
        # Without a configured password the page is disabled rather than public.
        if not REPORT_PASSWORD:
            self._reply(404)
            return
        expected = base64.b64encode(f"{REPORT_USER}:{REPORT_PASSWORD}".encode()).decode()
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Basic ") or not hmac.compare_digest(auth[6:].strip().encode(), expected.encode()):
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="Mgla Hub", charset="UTF-8"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        try:
            days = min(400, max(1, int(query.get("days", ["30"])[0])))
        except ValueError:
            days = 30
        try:
            db = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True)
            try:
                body = stats_report.generate_html(db, days, 25).encode("utf-8")
            finally:
                db.close()
        except sqlite3.Error:
            log.exception("report failed")
            self._reply(500)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Robots-Tag", "noindex, nofollow")
        self.end_headers()
        self.wfile.write(body)

    def _check_token(self):
        token = self.headers.get("X-Mgla-Token", "")
        if not TOKEN or not hmac.compare_digest(token.encode(), TOKEN.encode()):
            self._reply(401)
            return False
        return True

    def _read_json_body(self, max_len):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length <= 0 or length > max_len:
            self._reply(413)
            return None
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._reply(400)
            return None

    def do_POST(self):
        path = urlsplit(self.path).path.rstrip("/") or "/"
        if path == "/mgla-stats/v1/batch":
            self._post_stats()
        elif path == "/mgla-ai/v1/chat":
            self._post_ai_chat()
        else:
            self._reply(404)

    def _post_stats(self):
        if not self._check_token():
            return
        raw = self._read_json_body(MAX_BODY)
        if raw is None:
            return
        try:
            batch = validate(raw)
        except ValueError:
            self._reply(400)
            return
        try:
            self.store.ingest(batch)
        except sqlite3.Error:
            log.exception("ingest failed")
            self._reply(500)
            return
        self._reply(200, b'{"ok":true}')

    def _post_ai_chat(self):
        if not self._check_token():
            return
        if not OPENROUTER_API_KEY:
            self._reply(503, b'{"error":"OPENROUTER_API_KEY not configured"}')
            return
        raw = self._read_json_body(MAX_AI_BODY)
        if raw is None:
            return
        if not isinstance(raw, dict):
            self._reply(400, b'{"error":"bad body"}')
            return
        message = raw.get("message")
        lang = raw.get("lang") or "Russian"
        if not isinstance(message, str) or not message.strip():
            self._reply(400, b'{"error":"message required"}')
            return
        if len(message) > MAX_AI_MESSAGE:
            self._reply(413, b'{"error":"message too long"}')
            return
        try:
            content = call_openrouter(message.strip(), lang if isinstance(lang, str) else "Russian")
        except RuntimeError as e:
            log.warning("ai chat failed: %s", e)
            body = json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8")
            self._reply(502, body)
            return
        body = json.dumps({"content": content}, ensure_ascii=False).encode("utf-8")
        self._reply(200, body)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if not TOKEN:
        raise SystemExit("MGLA_WS_TOKEN is not set")
    Handler.store = Store(DB_PATH)
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    log.info(
        "listening on %s:%d, db=%s, openrouter=%s",
        HOST, PORT, DB_PATH, "yes" if OPENROUTER_API_KEY else "no",
    )
    httpd.serve_forever()


if __name__ == "__main__":
    main()
