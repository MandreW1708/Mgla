#!/usr/bin/env python3
"""Mgla AI proxy on the WS-relay host (mglabot).

Runs next to relay.py. Clients call:
  POST /mgla-ai/v1/chat              — OpenRouter (OPENROUTER_API_KEY in env)
  POST /mgla-ai/v1/gemini            — Gemini text (user api_key in body)
  POST /mgla-ai/v1/gemini/transcribe — Gemini media (user api_key + base64)

Admin (hub → bot sync, not for clients):
  POST /mgla-ai/v1/admin/models      — replace OpenRouter fallback list
    Auth: X-Mgla-Sync-Token (MGLA_HUB_SYNC_TOKEN)

Auth for client routes: same X-Mgla-Token as the WS relay (MGLA_WS_TOKEN).
Stdlib only. Default listen 127.0.0.1:8768 (nginx terminates TLS).
"""

from __future__ import annotations

import hmac
import json
import logging
import os
import random
import re
import tempfile
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, urlsplit

HOST = os.environ.get("MGLA_AI_HOST", "127.0.0.1")
PORT = int(os.environ.get("MGLA_AI_PORT", "8768"))
TOKEN = os.environ.get("MGLA_WS_TOKEN", "").strip()
SYNC_TOKEN = os.environ.get("MGLA_HUB_SYNC_TOKEN", "").strip()
OPENROUTER_API_KEY = os.environ.get("OPENROUTER_API_KEY", "").strip()
MODELS_PATH = Path(
    os.environ.get("MGLA_OPENROUTER_MODELS_FILE", "/opt/mgla-ws-relay/openrouter_models.json")
)

MAX_AI_BODY = 32 * 1024
MAX_ADMIN_BODY = 16 * 1024
MAX_GEMINI_TRANSCRIBE_BODY = 22 * 1024 * 1024
MAX_AI_MESSAGE = 12_000
MAX_MODELS = 40

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
DEFAULT_AI_MODELS = (
    "nvidia/nemotron-3-super-120b-a12b:free",
    "nvidia/nemotron-3-ultra-550b-a55b:free",
    "nvidia/nemotron-3.5-lightning:free",
    "nex-agi/nex-n2.5-pro:free",
    "google/gemma-4-31b-it:free",
)
MODEL_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_./:-]{0,127}$")
CTRL_RE = re.compile(r"[\x00-\x1f\x7f]")

log = logging.getLogger("mgla-ai-proxy")

_models_lock = threading.Lock()
_runtime_models: list[str] = list(DEFAULT_AI_MODELS)


def validate_models(raw) -> list[str]:
    if not isinstance(raw, list) or not raw:
        raise ValueError("models must be a non-empty array")
    if len(raw) > MAX_MODELS:
        raise ValueError(f"too many models (max {MAX_MODELS})")
    out: list[str] = []
    seen = set()
    for item in raw:
        if not isinstance(item, str):
            raise ValueError("model ids must be strings")
        model = item.strip()
        if not MODEL_RE.match(model):
            raise ValueError(f"bad model id: {model[:40]}")
        if model in seen:
            continue
        seen.add(model)
        out.append(model)
    if not out:
        raise ValueError("at least one model required")
    return out


def get_models() -> tuple[str, ...]:
    with _models_lock:
        return tuple(_runtime_models)


def set_models(models: list[str], persist: bool = True) -> None:
    global _runtime_models
    cleaned = validate_models(models)
    with _models_lock:
        _runtime_models = list(cleaned)
        if persist:
            _write_models_file(cleaned)


def _write_models_file(models: list[str]) -> None:
    MODELS_PATH.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(models, ensure_ascii=False, indent=2) + "\n"
    fd, tmp_name = tempfile.mkstemp(
        dir=str(MODELS_PATH.parent), prefix=".openrouter_models.", suffix=".tmp"
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(payload)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_name, MODELS_PATH)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def load_models_from_disk() -> None:
    global _runtime_models
    try:
        raw = MODELS_PATH.read_text(encoding="utf-8")
        models = validate_models(json.loads(raw))
        with _models_lock:
            _runtime_models = models
        log.info("loaded %d openrouter models from %s", len(models), MODELS_PATH)
    except FileNotFoundError:
        log.info("no models file at %s — using defaults", MODELS_PATH)
        with _models_lock:
            _runtime_models = list(DEFAULT_AI_MODELS)
    except (OSError, ValueError, json.JSONDecodeError) as e:
        log.warning("failed to load %s (%s) — using defaults", MODELS_PATH, e)
        with _models_lock:
            _runtime_models = list(DEFAULT_AI_MODELS)


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
    if not OPENROUTER_API_KEY:
        raise RuntimeError("OPENROUTER_API_KEY is not set")
    models = get_models()
    system_prompt = _ai_system_prompt(lang)
    seed = random.getrandbits(63)
    failures = []
    for i, model in enumerate(models):
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
                payload = json.loads(resp.read().decode("utf-8", errors="replace"))
                return _extract_openrouter_content(payload)
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")[:500]
            failures.append(f"{model}: HTTP {e.code}")
            if e.code in (404, 429) and i < len(models) - 1:
                continue
            if e.code in (401, 402, 403):
                raise RuntimeError(f"OpenRouter {e.code}: {err_body}") from e
            if i < len(models) - 1:
                continue
        except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as e:
            failures.append(f"{model}: {e}")
            if i < len(models) - 1:
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
            return _extract_gemini_text(json.loads(resp.read().decode("utf-8", errors="replace")))
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")[:800]
        raise RuntimeError(f"Gemini HTTP {e.code}: {err_body}") from e


class Handler(BaseHTTPRequestHandler):
    server_version = "mgla-ai-proxy"
    sys_version = ""

    def log_message(self, fmt, *args):
        pass

    def _reply(self, code, body=b""):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _check_token(self) -> bool:
        token = self.headers.get("X-Mgla-Token", "")
        if not TOKEN or not hmac.compare_digest(token.encode(), TOKEN.encode()):
            self._reply(401)
            return False
        return True

    def _check_sync_token(self) -> bool:
        token = self.headers.get("X-Mgla-Sync-Token", "")
        if not SYNC_TOKEN or not hmac.compare_digest(token.encode(), SYNC_TOKEN.encode()):
            self._reply(401, b'{"error":"unauthorized"}')
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

    def do_GET(self):
        path = urlsplit(self.path).path.rstrip("/") or "/"
        if path in ("/mgla-ai/v1/health", "/health"):
            models = get_models()
            body = json.dumps(
                {"ok": True, "models": len(models)},
                ensure_ascii=False,
            ).encode("utf-8")
            self._reply(200, body)
        else:
            self._reply(404)

    def do_POST(self):
        path = urlsplit(self.path).path.rstrip("/") or "/"
        if path == "/mgla-ai/v1/admin/models":
            self._post_admin_models()
        elif path == "/mgla-ai/v1/chat":
            self._post_chat()
        elif path == "/mgla-ai/v1/gemini":
            self._post_gemini()
        elif path == "/mgla-ai/v1/gemini/transcribe":
            self._post_gemini_transcribe()
        else:
            self._reply(404)

    def _post_admin_models(self):
        if not self._check_sync_token():
            return
        raw = self._read_json_body(MAX_ADMIN_BODY)
        if raw is None:
            return
        if not isinstance(raw, dict):
            self._reply(400, b'{"error":"bad body"}')
            return
        try:
            models = validate_models(raw.get("models"))
            set_models(models, persist=True)
        except ValueError as e:
            self._reply(400, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        except OSError as e:
            log.exception("persist models failed")
            self._reply(500, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        log.info("openrouter models updated (%d)", len(models))
        self._reply(
            200,
            json.dumps({"ok": True, "models": models}, ensure_ascii=False).encode("utf-8"),
        )

    def _post_chat(self):
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
            log.warning("chat failed: %s", e)
            self._reply(502, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        self._reply(200, json.dumps({"content": content}, ensure_ascii=False).encode("utf-8"))

    def _post_gemini(self):
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
            log.warning("gemini failed: %s", e)
            self._reply(502, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        self._reply(200, json.dumps({"content": content}, ensure_ascii=False).encode("utf-8"))

    def _post_gemini_transcribe(self):
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
            log.warning("transcribe failed: %s", e)
            self._reply(502, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        self._reply(200, json.dumps({"content": content}, ensure_ascii=False).encode("utf-8"))


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if not TOKEN:
        raise SystemExit("MGLA_WS_TOKEN is not set")
    load_models_from_disk()
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    log.info(
        "listening on %s:%d, openrouter=%s, sync=%s, models=%d",
        HOST,
        PORT,
        "yes" if OPENROUTER_API_KEY else "no",
        "yes" if SYNC_TOKEN else "no",
        len(get_models()),
    )
    httpd.serve_forever()


if __name__ == "__main__":
    main()
