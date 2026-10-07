#!/usr/bin/env python3
"""Mgla Hub backend: analytics + admin control plane + legacy AI proxy.

- POST /mgla-stats/v1/batch — anonymous client analytics (SQLite; no client IPs)
- POST /mgla-config/v1/features — feature flags for a Telegram user id
- POST /mgla-updates/v1/check — in-app update check
- GET  /mgla-updates/v1/apk — download published APK
- GET/POST /admin — feature flags + OpenRouter model list (Basic Auth)
- GET/POST /admin/updates — publish APK + changelog (Basic Auth)
- POST /mgla-ai/v1/* — legacy AI proxy (production clients use mglabot)

Standard library only. Clients authenticate with X-Mgla-Token (MGLA_WS_TOKEN on hub).
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
from urllib.parse import parse_qs, quote, unquote, urlsplit

import hub_admin
import hub_updates
import stats_report

HOST = os.environ.get("MGLA_STATS_HOST", "127.0.0.1")
PORT = int(os.environ.get("MGLA_STATS_PORT", "8767"))
TOKEN = os.environ.get("MGLA_WS_TOKEN", "")
DB_PATH = os.environ.get("MGLA_STATS_DB", "/opt/mgla-ws-relay/stats.db")
RETENTION_DAYS = int(os.environ.get("MGLA_STATS_RETENTION_DAYS", "400"))
REPORT_USER = os.environ.get("MGLA_STATS_REPORT_USER", "admin")
REPORT_PASSWORD = os.environ.get("MGLA_STATS_REPORT_PASSWORD", "")
OPENROUTER_API_KEY = os.environ.get("OPENROUTER_API_KEY", "").strip()
HUB_SYNC_TOKEN = os.environ.get("MGLA_HUB_SYNC_TOKEN", "").strip()
BOT_AI_URL = os.environ.get("MGLA_BOT_AI_URL", "").strip().rstrip("/")

MAX_BODY = 256 * 1024
MAX_AI_BODY = 32 * 1024
MAX_GEMINI_TRANSCRIBE_BODY = 22 * 1024 * 1024
MAX_UPDATE_UPLOAD = hub_updates.MAX_APK_BYTES + 2 * 1024 * 1024
MAX_AI_MESSAGE = 12_000
GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
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
CREATE TABLE IF NOT EXISTS feature_global (
    feature TEXT PRIMARY KEY,
    disabled INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS feature_deny (
    tg_id INTEGER NOT NULL,
    feature TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (tg_id, feature)
);
CREATE INDEX IF NOT EXISTS feature_deny_feature ON feature_deny(feature);
CREATE TABLE IF NOT EXISTS ai_config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


class Store:
    def __init__(self, path):
        self._lock = threading.Lock()
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(SCHEMA)
        self._ensure_default_models()
        self._db.commit()
        self._last_cleanup = 0.0

    def _ensure_default_models(self):
        row = self._db.execute(
            "SELECT value FROM ai_config WHERE key = ?", ("openrouter_models",)
        ).fetchone()
        if row is None:
            self._db.execute(
                "INSERT INTO ai_config(key, value) VALUES(?, ?)",
                ("openrouter_models", json.dumps(list(hub_admin.DEFAULT_OPENROUTER_MODELS))),
            )

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

    def get_global_disabled(self):
        with self._lock:
            rows = self._db.execute(
                "SELECT feature, disabled FROM feature_global"
            ).fetchall()
        return {f: bool(d) for f, d in rows}

    def set_global_flags(self, disabled_map):
        now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        with self._lock, self._db:
            for feature, disabled in disabled_map.items():
                self._db.execute(
                    """INSERT INTO feature_global(feature, disabled, updated_at) VALUES(?,?,?)
                       ON CONFLICT(feature) DO UPDATE SET
                         disabled=excluded.disabled, updated_at=excluded.updated_at""",
                    (feature, 1 if disabled else 0, now),
                )

    def list_denies(self):
        with self._lock:
            return self._db.execute(
                "SELECT tg_id, feature, created_at FROM feature_deny ORDER BY created_at DESC, tg_id, feature"
            ).fetchall()

    def add_deny(self, tg_id, feature):
        now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        with self._lock, self._db:
            self._db.execute(
                """INSERT INTO feature_deny(tg_id, feature, created_at) VALUES(?,?,?)
                   ON CONFLICT(tg_id, feature) DO NOTHING""",
                (tg_id, feature, now),
            )

    def remove_deny(self, tg_id, feature):
        with self._lock, self._db:
            self._db.execute(
                "DELETE FROM feature_deny WHERE tg_id = ? AND feature = ?",
                (tg_id, feature),
            )

    def disabled_for_user(self, tg_ids):
        """Union of global kill-switches and personal denies for any of tg_ids."""
        disabled = set()
        with self._lock:
            for feature, flag in self._db.execute(
                "SELECT feature, disabled FROM feature_global WHERE disabled = 1"
            ):
                if feature in hub_admin.FEATURE_IDS:
                    disabled.add(feature)
            if tg_ids:
                placeholders = ",".join("?" * len(tg_ids))
                rows = self._db.execute(
                    f"SELECT DISTINCT feature FROM feature_deny WHERE tg_id IN ({placeholders})",
                    tuple(tg_ids),
                ).fetchall()
                for (feature,) in rows:
                    if feature in hub_admin.FEATURE_IDS:
                        disabled.add(feature)
        return sorted(disabled)

    def get_openrouter_models(self):
        with self._lock:
            row = self._db.execute(
                "SELECT value FROM ai_config WHERE key = ?", ("openrouter_models",)
            ).fetchone()
        if not row:
            return list(hub_admin.DEFAULT_OPENROUTER_MODELS)
        try:
            return hub_admin.models_from_json(row[0])
        except (ValueError, json.JSONDecodeError):
            return list(hub_admin.DEFAULT_OPENROUTER_MODELS)

    def set_openrouter_models(self, models):
        with self._lock, self._db:
            self._db.execute(
                """INSERT INTO ai_config(key, value) VALUES(?, ?)
                   ON CONFLICT(key) DO UPDATE SET value = excluded.value""",
                ("openrouter_models", json.dumps(list(models))),
            )


def push_models_to_bot(models):
    """Push OpenRouter model list to mglabot. Returns (ok, message)."""
    if not BOT_AI_URL:
        return False, "MGLA_BOT_AI_URL не задан"
    if not HUB_SYNC_TOKEN:
        return False, "MGLA_HUB_SYNC_TOKEN не задан"
    url = BOT_AI_URL + "/mgla-ai/v1/admin/models"
    body = json.dumps({"models": list(models)}).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Mgla-Sync-Token": HUB_SYNC_TOKEN,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            if resp.status != 200:
                return False, f"mglabot HTTP {resp.status}: {raw[:200]}"
            return True, "модели отправлены на mglabot"
    except urllib.error.HTTPError as e:
        err = e.read().decode("utf-8", errors="replace")[:300]
        return False, f"mglabot HTTP {e.code}: {err}"
    except (urllib.error.URLError, TimeoutError) as e:
        return False, f"mglabot недоступен: {e}"


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


def _extract_gemini_text(payload: dict) -> str:
    candidates = payload.get("candidates")
    if not isinstance(candidates, list) or not candidates:
        raise ValueError("no candidates")
    content = candidates[0].get("content") if isinstance(candidates[0], dict) else None
    if not isinstance(content, dict):
        raise ValueError("no content")
    parts = content.get("parts")
    if not isinstance(parts, list) or not parts:
        raise ValueError("no parts")
    part0 = parts[0] if isinstance(parts[0], dict) else None
    if not part0:
        raise ValueError("empty part")
    text = part0.get("text")
    if not isinstance(text, str) or not text.strip():
        raise ValueError("empty text")
    return text


def call_gemini_json(api_key: str, model: str, body: dict) -> str:
    if not api_key or not model:
        raise RuntimeError("gemini api_key/model required")
    safe_model = re.sub(r"[^a-zA-Z0-9._-]", "", model)[:120]
    url = f"{GEMINI_API_BASE}/{safe_model}:generateContent?key={quote(api_key.strip())}"
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return _extract_gemini_text(json.loads(raw))
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")[:800]
        raise RuntimeError(f"Gemini HTTP {e.code}: {err_body}") from e


class Handler(BaseHTTPRequestHandler):
    server_version = "mgla-stats"
    sys_version = ""
    store = None
    updates = None

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
        elif path in ("/admin", "/mgla-admin", "/control"):
            self._admin_get(parse_qs(url.query))
        elif path in ("/admin/updates", "/mgla-admin/updates"):
            self._admin_updates_get(parse_qs(url.query))
        elif path == "/mgla-updates/v1/apk":
            self._get_update_apk()
        else:
            self._reply(404)

    def _check_basic_auth(self):
        if not REPORT_PASSWORD:
            self._reply(404)
            return False
        expected = base64.b64encode(f"{REPORT_USER}:{REPORT_PASSWORD}".encode()).decode()
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Basic ") or not hmac.compare_digest(
            auth[6:].strip().encode(), expected.encode()
        ):
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="Mgla Hub", charset="UTF-8"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return False
        return True

    def _html_reply(self, body: bytes):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Robots-Tag", "noindex, nofollow")
        self.end_headers()
        self.wfile.write(body)

    def _redirect(self, location: str):
        body = b""
        self.send_response(303)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _report(self, query):
        if not self._check_basic_auth():
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
        self._html_reply(body)

    def _admin_get(self, query):
        if not self._check_basic_auth():
            return
        flash_ok = unquote(query.get("ok", [""])[0] or "")
        flash_err = unquote(query.get("err", [""])[0] or "")
        try:
            body = hub_admin.render_admin_html(
                global_disabled=self.store.get_global_disabled(),
                denies=self.store.list_denies(),
                models=self.store.get_openrouter_models(),
                flash_ok=flash_ok,
                flash_err=flash_err,
                bot_url=BOT_AI_URL,
            ).encode("utf-8")
        except Exception:
            log.exception("admin render failed")
            self._reply(500)
            return
        self._html_reply(body)

    def _read_form_body(self, max_len=64 * 1024):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length < 0 or length > max_len:
            self._reply(413)
            return None
        raw = self.rfile.read(length) if length else b""
        try:
            return parse_qs(raw.decode("utf-8"), keep_blank_values=True)
        except UnicodeDecodeError:
            self._reply(400)
            return None

    def _admin_post(self):
        if not self._check_basic_auth():
            return
        form = self._read_form_body()
        if form is None:
            return
        action = (form.get("action") or [""])[0]
        try:
            if action == "save_globals":
                disabled_map = {
                    fid: bool((form.get("kill_" + fid) or [""])[0])
                    for fid, _ in hub_admin.FEATURE_CATALOG
                }
                self.store.set_global_flags(disabled_map)
                self._redirect(hub_admin.flash_redirect(ok="kill-switch сохранён"))
            elif action == "deny_add":
                tg_raw = (form.get("tg_id") or [""])[0].strip()
                feature = (form.get("feature") or [""])[0].strip()
                tg_id = int(tg_raw)
                if tg_id <= 0:
                    raise ValueError("bad tg_id")
                if feature not in hub_admin.FEATURE_IDS:
                    raise ValueError("unknown feature")
                self.store.add_deny(tg_id, feature)
                self._redirect(hub_admin.flash_redirect(ok=f"запрет для {tg_id} / {feature}"))
            elif action == "deny_remove":
                tg_id = int((form.get("tg_id") or ["0"])[0])
                feature = (form.get("feature") or [""])[0].strip()
                if feature not in hub_admin.FEATURE_IDS:
                    raise ValueError("unknown feature")
                self.store.remove_deny(tg_id, feature)
                self._redirect(hub_admin.flash_redirect(ok="запрет снят"))
            elif action == "save_models":
                models = hub_admin.parse_models_text((form.get("models") or [""])[0])
                self.store.set_openrouter_models(models)
                ok, msg = push_models_to_bot(models)
                if ok:
                    self._redirect(hub_admin.flash_redirect(ok="модели сохранены; " + msg))
                else:
                    self._redirect(
                        hub_admin.flash_redirect(
                            ok="модели сохранены на хабе",
                            err="push: " + msg,
                        )
                    )
            elif action == "push_models":
                models = self.store.get_openrouter_models()
                ok, msg = push_models_to_bot(models)
                if ok:
                    self._redirect(hub_admin.flash_redirect(ok=msg))
                else:
                    self._redirect(hub_admin.flash_redirect(err=msg))
            else:
                self._redirect(hub_admin.flash_redirect(err="неизвестное действие"))
        except (ValueError, TypeError) as e:
            self._redirect(hub_admin.flash_redirect(err=str(e)))
        except sqlite3.Error:
            log.exception("admin post failed")
            self._redirect(hub_admin.flash_redirect(err="ошибка БД"))

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
        if path in ("/admin", "/mgla-admin", "/control"):
            self._admin_post()
        elif path in ("/admin/updates", "/mgla-admin/updates"):
            self._admin_updates_post()
        elif path == "/mgla-stats/v1/batch":
            self._post_stats()
        elif path == "/mgla-config/v1/features":
            self._post_features()
        elif path == "/mgla-updates/v1/check":
            self._post_update_check()
        elif path == "/mgla-ai/v1/chat":
            self._post_ai_chat()
        elif path == "/mgla-ai/v1/gemini":
            self._post_ai_gemini()
        elif path == "/mgla-ai/v1/gemini/transcribe":
            self._post_ai_gemini_transcribe()
        else:
            self._reply(404)

    def _admin_updates_get(self, query):
        if not self._check_basic_auth():
            return
        flash_ok = unquote(query.get("ok", [""])[0] or "")
        flash_err = unquote(query.get("err", [""])[0] or "")
        try:
            meta = self.updates.get()
            body = hub_updates.render_updates_html(meta, flash_ok=flash_ok, flash_err=flash_err).encode("utf-8")
        except Exception:
            log.exception("updates admin render failed")
            self._reply(500)
            return
        self._html_reply(body)

    def _admin_updates_post(self):
        if not self._check_basic_auth():
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length < 0 or length > MAX_UPDATE_UPLOAD:
            self._reply(413, b'{"ok":false,"error":"too large"}')
            return
        raw = self.rfile.read(length) if length else b""
        ctype = self.headers.get("Content-Type", "")
        fields = {}
        files = {}
        action = ""
        try:
            if "multipart/form-data" in ctype:
                fields, files = hub_updates.parse_multipart(ctype, raw)
            else:
                form = parse_qs(raw.decode("utf-8"), keep_blank_values=True)
                fields = {k: (v[0] if v else "") for k, v in form.items()}
            action = (fields.get("action") or "").strip()

            if action == "upload_apk":
                if "apk" not in files or not files["apk"][1]:
                    self._reply(400, b'{"ok":false,"error":"no apk"}')
                    return
                fname, data = files["apk"]
                file_meta = self.updates.save_apk_bytes(data, fname)
                cur = self.updates.get()
                # Refresh only file_* columns; keep the rest of the release.
                self.updates.save_meta(
                    mgla_version=cur.get("mgla_version") or "v0.0.1",
                    app_version=cur.get("app_version") or "0.0.0",
                    version_code=int(cur.get("version_code") or 1),
                    changelog=cur.get("changelog") or "",
                    published=bool(cur.get("published")),
                    file_meta=file_meta,
                )
                body = json.dumps(
                    {
                        "ok": True,
                        "file_name": file_meta["file_name"],
                        "file_size": file_meta["file_size"],
                        "file_sha256": file_meta["file_sha256"],
                    },
                    ensure_ascii=False,
                ).encode("utf-8")
                self._reply(200, body)
                return

            if action != "save_update":
                self._redirect(hub_updates.flash_redirect(err="неизвестное действие"))
                return

            file_meta = None
            if "apk" in files and files["apk"][1]:
                fname, data = files["apk"]
                file_meta = self.updates.save_apk_bytes(data, fname)
            try:
                version_code = int((fields.get("version_code") or "0").strip() or "0")
            except ValueError as e:
                raise ValueError("version_code должен быть числом") from e
            self.updates.save_meta(
                mgla_version=fields.get("mgla_version") or "",
                app_version=fields.get("app_version") or "",
                version_code=version_code,
                changelog=fields.get("changelog") or "",
                published=bool(fields.get("published")),
                file_meta=file_meta,
            )
            msg = "релиз сохранён"
            if file_meta:
                msg += f"; APK {file_meta['file_size'] // (1024 * 1024)} МБ"
            elif self.updates.get().get("has_file"):
                msg += "; APK уже на сервере"
            self._redirect(hub_updates.flash_redirect(ok=msg))
        except ValueError as e:
            if action == "upload_apk":
                self._reply(
                    400,
                    json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False).encode("utf-8"),
                )
            else:
                self._redirect(hub_updates.flash_redirect(err=str(e)))
        except Exception:
            log.exception("updates admin post failed")
            if action == "upload_apk":
                self._reply(500, b'{"ok":false,"error":"upload failed"}')
            else:
                self._redirect(hub_updates.flash_redirect(err="ошибка сохранения"))

    def _post_update_check(self):
        if not self._check_token():
            return
        raw = self._read_json_body(16 * 1024)
        if raw is None:
            return
        if not isinstance(raw, dict):
            self._reply(400, b'{"error":"bad body"}')
            return
        client_mgla = raw.get("mgla_version") or raw.get("mgla") or ""
        client_app = raw.get("app_version") or raw.get("tg") or ""
        try:
            client_code = int(raw.get("version_code") or raw.get("code") or 0)
        except (TypeError, ValueError):
            client_code = 0
        if not isinstance(client_mgla, str):
            client_mgla = str(client_mgla)
        if not isinstance(client_app, str):
            client_app = str(client_app)
        try:
            payload = self.updates.check_for_client(
                client_mgla=client_mgla.strip(),
                client_app=client_app.strip(),
                client_code=client_code,
            )
        except Exception:
            log.exception("update check failed")
            self._reply(500)
            return
        self._reply(200, json.dumps(payload, ensure_ascii=False).encode("utf-8"))

    def _get_update_apk(self):
        if not self._check_token():
            return
        path = self.updates.apk_path()
        meta = self.updates.get()
        if not path or not meta.get("published"):
            self._reply(404, b'{"error":"no apk"}')
            return
        try:
            size = path.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", 'attachment; filename="mgla-update.apk"')
            if meta.get("file_sha256"):
                self.send_header("X-Mgla-Sha256", meta["file_sha256"])
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with path.open("rb") as f:
                while True:
                    chunk = f.read(1024 * 256)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except BrokenPipeError:
            return
        except Exception:
            log.exception("apk download failed")
            return

    def _post_features(self):
        if not self._check_token():
            return
        raw = self._read_json_body(MAX_BODY)
        if raw is None:
            return
        if not isinstance(raw, dict):
            self._reply(400, b'{"error":"bad body"}')
            return
        tg_ids = []
        single = raw.get("tg_id")
        multi = raw.get("tg_ids")
        if isinstance(single, int) and not isinstance(single, bool) and single > 0:
            tg_ids.append(single)
        elif isinstance(single, str) and single.isdigit():
            tg_ids.append(int(single))
        if isinstance(multi, list):
            for item in multi:
                if isinstance(item, int) and not isinstance(item, bool) and item > 0:
                    tg_ids.append(item)
                elif isinstance(item, str) and item.isdigit():
                    tg_ids.append(int(item))
        # Deduplicate while preserving order.
        seen = set()
        uniq = []
        for tid in tg_ids:
            if tid not in seen:
                seen.add(tid)
                uniq.append(tid)
        if not uniq:
            self._reply(400, b'{"error":"tg_id required"}')
            return
        if len(uniq) > 8:
            self._reply(400, b'{"error":"too many tg_ids"}')
            return
        try:
            disabled = self.store.disabled_for_user(uniq)
        except sqlite3.Error:
            log.exception("features lookup failed")
            self._reply(500)
            return
        body = json.dumps({"v": 1, "disabled": disabled}, ensure_ascii=False).encode("utf-8")
        self._reply(200, body)

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

    def _post_ai_gemini(self):
        if not self._check_token():
            return
        raw = self._read_json_body(MAX_AI_BODY)
        if raw is None:
            return
        if not isinstance(raw, dict):
            self._reply(400, b'{"error":"bad body"}')
            return
        api_key = raw.get("api_key")
        model = raw.get("model") or "gemini-2.0-flash"
        text = raw.get("text") or raw.get("message")
        if not isinstance(api_key, str) or not api_key.strip():
            self._reply(400, b'{"error":"api_key required"}')
            return
        if not isinstance(text, str) or not text.strip():
            self._reply(400, b'{"error":"text required"}')
            return
        if len(text) > MAX_AI_MESSAGE:
            self._reply(413, b'{"error":"text too long"}')
            return
        body = {
            "contents": [{"parts": [{"text": text.strip()}]}],
            "generationConfig": {"temperature": 0.3, "maxOutputTokens": 1024},
        }
        try:
            content = call_gemini_json(api_key.strip(), str(model), body)
        except RuntimeError as e:
            log.warning("gemini proxy failed: %s", e)
            body_err = json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8")
            self._reply(502, body_err)
            return
        body_ok = json.dumps({"content": content}, ensure_ascii=False).encode("utf-8")
        self._reply(200, body_ok)

    def _post_ai_gemini_transcribe(self):
        if not self._check_token():
            return
        raw = self._read_json_body(MAX_GEMINI_TRANSCRIBE_BODY)
        if raw is None:
            return
        if not isinstance(raw, dict):
            self._reply(400, b'{"error":"bad body"}')
            return
        api_key = raw.get("api_key")
        model = raw.get("model") or "gemini-2.0-flash"
        mime_type = raw.get("mime_type")
        data_b64 = raw.get("data_b64")
        if not isinstance(api_key, str) or not api_key.strip():
            self._reply(400, b'{"error":"api_key required"}')
            return
        if not isinstance(mime_type, str) or not mime_type.strip():
            self._reply(400, b'{"error":"mime_type required"}')
            return
        if not isinstance(data_b64, str) or len(data_b64) < 16:
            self._reply(400, b'{"error":"data_b64 required"}')
            return
        if len(data_b64) > 20 * 1024 * 1024:
            self._reply(413, b'{"error":"media too large"}')
            return
        is_video = mime_type.strip().startswith("video")
        prompt = (
            "Transcribe the following "
            + ("video" if is_video else "audio")
            + " accurately. Return ONLY the transcribed text in Russian, no additional commentary."
        )
        body = {
            "contents": [
                {
                    "parts": [
                        {"text": prompt},
                        {"inline_data": {"mime_type": mime_type.strip(), "data": data_b64.strip()}},
                    ]
                }
            ],
            "generationConfig": {"temperature": 0.1, "maxOutputTokens": 1024},
        }
        try:
            content = call_gemini_json(api_key.strip(), str(model), body)
        except RuntimeError as e:
            log.warning("gemini transcribe failed: %s", e)
            body_err = json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8")
            self._reply(502, body_err)
            return
        body_ok = json.dumps({"content": content}, ensure_ascii=False).encode("utf-8")
        self._reply(200, body_ok)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if not TOKEN:
        raise SystemExit("MGLA_WS_TOKEN is not set")
    store = Store(DB_PATH)
    Handler.store = store
    Handler.updates = hub_updates.UpdateStore(store._db, store._lock)
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    log.info(
        "listening on %s:%d, db=%s, openrouter=%s, updates=%s",
        HOST,
        PORT,
        DB_PATH,
        "yes" if OPENROUTER_API_KEY else "no",
        hub_updates.UPDATES_DIR,
    )
    httpd.serve_forever()


if __name__ == "__main__":
    main()
