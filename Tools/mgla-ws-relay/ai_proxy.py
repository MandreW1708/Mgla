#!/usr/bin/env python3
"""Mgla AI proxy on the WS-relay host (mglabot).

Runs next to relay.py. Clients call:
  POST /mgla-ai/v1/chat              ??? OpenRouter (OPENROUTER_API_KEY in env)
  POST /mgla-ai/v1/gemini            ??? Gemini text (user api_key in body)
  POST /mgla-ai/v1/gemini/transcribe ??? Gemini media (user api_key + base64)
  POST /mgla-ai/v1/features          ??? feature flags (proxies to mglahub)
  POST /mgla-ai/v1/update-check      ??? in-app update check (proxies to mglahub)
  GET  /mgla-ai/v1/update-apk        ??? APK download (proxies to mglahub)
  GET  /mgla-ai/v1/models            — model list + crowd status (up/degraded/down, no probes)
  POST /mgla-ai/v1/models/report     — crowd success/failure (1→yellow, 2+→red)
  POST /mgla-ai/v1/stats-batch       ??? anonymous stats batch (proxies to mglahub)

Admin (hub → bot sync, not for clients):
  POST /mgla-ai/v1/admin/models      — replace OpenRouter model lists per plan
       body: {"models":[...free...], "plans":{"free":[...],"premium_plus":[...],"premium_ultra":[...]},
              "aliases":{"premium":"free"}}
  Auth: X-Mgla-Sync-Token (MGLA_HUB_SYNC_TOKEN)

Subscription plan ids (stable for future billing):
  free | premium | premium_plus | premium_ultra
  (premium has no own models — same list + key as free)
API keys (mglabot env only):
  OPENROUTER_API_KEY                 — free + premium
  OPENROUTER_API_KEY_PREMIUM_PLUS    — Premium+
  OPENROUTER_API_KEY_PREMIUM_ULTRA   — Premium Ultra

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
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, urlsplit

import mgla_token

HOST = os.environ.get("MGLA_AI_HOST", "127.0.0.1")
PORT = int(os.environ.get("MGLA_AI_PORT", "8768"))
TOKEN = os.environ.get("MGLA_WS_TOKEN", "").strip()
SYNC_TOKEN = os.environ.get("MGLA_HUB_SYNC_TOKEN", "").strip()
OPENROUTER_API_KEY = os.environ.get("OPENROUTER_API_KEY", "").strip()
OPENROUTER_API_KEY_PREMIUM_PLUS = os.environ.get("OPENROUTER_API_KEY_PREMIUM_PLUS", "").strip()
OPENROUTER_API_KEY_PREMIUM_ULTRA = os.environ.get(
    "OPENROUTER_API_KEY_PREMIUM_ULTRA", ""
).strip()
# Client feature-flag poll while WS relay is active: mglabot → mglahub.
HUB_FEATURES_URL = os.environ.get(
    "MGLA_HUB_FEATURES_URL",
    "https://mglahub.mooo.com/mgla-config/v1/features",
).strip()
HUB_FEATURES_TOKEN = (
    os.environ.get("MGLA_HUB_FEATURES_TOKEN", "").strip()
    or os.environ.get("MGLA_HUB_CLIENT_TOKEN", "").strip()
)
HUB_UPDATE_CHECK_URL = os.environ.get(
    "MGLA_HUB_UPDATE_CHECK_URL",
    "https://mglahub.mooo.com/mgla-updates/v1/check",
).strip()
HUB_UPDATE_APK_URL = os.environ.get(
    "MGLA_HUB_UPDATE_APK_URL",
    "https://mglahub.mooo.com/mgla-updates/v1/apk",
).strip()
HUB_STATS_BATCH_URL = os.environ.get(
    "MGLA_HUB_STATS_BATCH_URL",
    "https://mglahub.mooo.com/mgla-stats/v1/batch",
).strip()
HUB_AUTH_REGISTER_URL = os.environ.get(
    "MGLA_HUB_AUTH_REGISTER_URL",
    "https://mglahub.mooo.com/mgla-auth/v1/register",
).strip()
HUB_AUTH_TOKEN_URL = os.environ.get(
    "MGLA_HUB_AUTH_TOKEN_URL",
    "https://mglahub.mooo.com/mgla-auth/v1/token",
).strip()
MODELS_PATH = Path(
    os.environ.get("MGLA_OPENROUTER_MODELS_FILE", "/opt/mgla-ws-relay/openrouter_models.json")
)
REPORTS_PATH = Path(
    os.environ.get(
        "MGLA_OPENROUTER_MODEL_REPORTS_FILE",
        "/opt/mgla-ws-relay/openrouter_model_reports.json",
    )
)

MAX_AI_BODY = 512 * 1024
MAX_ADMIN_BODY = 16 * 1024
MAX_FEATURES_BODY = 8 * 1024
MAX_UPDATE_CHECK_BODY = 8 * 1024
MAX_STATS_BATCH_BODY = 256 * 1024
MAX_GEMINI_TRANSCRIBE_BODY = 22 * 1024 * 1024
# Hard caps for OpenRouter — never exposed to clients as settings.
# Oversized prompts are clipped silently (keep the tail = recent context).
MAX_AI_MESSAGE = 200_000
# Free / Plus defaults (chars ≈ tokens * 3).
OPENROUTER_MAX_INPUT_CHARS = 6_000
OPENROUTER_MAX_OUTPUT_TOKENS = 768
CHARS_PER_TOKEN = 3
# Premium Ultra: Mgla user caps (not native model windows).
ULTRA_DAILY_REQUESTS = 20
ULTRA_MODEL_LIMITS: dict[str, dict[str, int]] = {
    "deepseek/deepseek-v4.1-flash": {"input": 32_000, "output": 4_000, "context": 32_000},
    "z-ai/glm-5.3-flash": {"input": 32_000, "output": 4_000, "context": 32_000},
    "xiaomi/mimo-v2.6-flash": {"input": 24_000, "output": 4_000, "context": 24_000},
    "nex-agi/nex-n2.5-mini": {"input": 48_000, "output": 4_000, "context": 48_000},
    "qwen/qwen3.7-flash": {"input": 48_000, "output": 4_000, "context": 48_000},
    "google/gemma-3-4b-it": {"input": 16_000, "output": 2_000, "context": 16_000},
}
DEFAULT_ULTRA_MODELS = tuple(ULTRA_MODEL_LIMITS.keys())
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

# Stable plan ids — must match hub_admin.PLAN_* (future subscription entitlements).
PLAN_FREE = "free"
PLAN_PREMIUM = "premium"
PLAN_PREMIUM_PLUS = "premium_plus"
PLAN_PREMIUM_ULTRA = "premium_ultra"
# Plans that store their own model list on disk / accept admin push.
PLAN_IDS = (PLAN_FREE, PLAN_PREMIUM_PLUS, PLAN_PREMIUM_ULTRA)
# All plans exposed to clients (includes aliases).
PLAN_IDS_ALL = (PLAN_FREE, PLAN_PREMIUM, PLAN_PREMIUM_PLUS, PLAN_PREMIUM_ULTRA)
PLAN_MODELS_ALIAS = {
    PLAN_PREMIUM: PLAN_FREE,  # Premium → same models + key as Free
}
PLAN_LABELS = {
    PLAN_FREE: "Free",
    PLAN_PREMIUM: "Premium",
    PLAN_PREMIUM_PLUS: "Premium+",
    PLAN_PREMIUM_ULTRA: "Premium Ultra",
}

MODEL_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_./:-]{0,127}$")
CTRL_RE = re.compile(r"[\x00-\x1f\x7f]")

log = logging.getLogger("mgla-ai-proxy")

_models_lock = threading.Lock()
_runtime_models_by_plan: dict[str, list[str]] = {
    PLAN_FREE: list(DEFAULT_AI_MODELS),
    PLAN_PREMIUM_PLUS: [],
    PLAN_PREMIUM_ULTRA: list(DEFAULT_ULTRA_MODELS),
}

# Crowd reports: model_id -> error_kind -> install_id -> last_ts
# Status is derived only from real client failures (fast, no live OpenRouter probes).
#   0 reporters → up (green)
#   1 reporter  → degraded (yellow) — works with interruptions
#   2+ reporters → down (red)
_reports_lock = threading.Lock()
_reports: dict[str, dict[str, dict[str, float]]] = {}
REPORT_WINDOW_SEC = 45 * 60
REPORT_DEGRADED = 1
REPORT_DOWN = 2
# Known flaky free models: yellow until crowd proves them fully down.
KNOWN_DEGRADED_MODELS = frozenset({
    "nvidia/nemotron-3.5-lightning:free",
})
INSTALL_ID_RE = re.compile(r"^[a-fA-F0-9]{16,64}$")
ERROR_KINDS = frozenset({"timeout", "no_endpoints", "unavailable"})


def load_reports_from_disk() -> None:
    global _reports
    try:
        raw = REPORTS_PATH.read_text(encoding="utf-8")
        data = json.loads(raw)
        if not isinstance(data, dict):
            return
        cleaned: dict[str, dict[str, dict[str, float]]] = {}
        now = time.time()
        for model_id, kinds in data.items():
            if not isinstance(model_id, str) or not isinstance(kinds, dict):
                continue
            kind_map: dict[str, dict[str, float]] = {}
            for kind, installs in kinds.items():
                if kind not in ERROR_KINDS or not isinstance(installs, dict):
                    continue
                keep = {
                    iid: float(ts)
                    for iid, ts in installs.items()
                    if isinstance(iid, str)
                    and INSTALL_ID_RE.match(iid)
                    and isinstance(ts, (int, float))
                    and now - float(ts) <= REPORT_WINDOW_SEC * 2
                }
                if keep:
                    kind_map[kind] = keep
            if kind_map:
                cleaned[model_id] = kind_map
        with _reports_lock:
            _reports = cleaned
        log.info("loaded model failure reports for %d models", len(cleaned))
    except FileNotFoundError:
        pass
    except Exception as e:
        log.warning("failed to load model reports: %s", e)


def _persist_reports_unlocked() -> None:
    try:
        REPORTS_PATH.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(_reports, ensure_ascii=False, indent=2) + "\n"
        fd, tmp_name = tempfile.mkstemp(
            dir=str(REPORTS_PATH.parent), prefix=".openrouter_reports.", suffix=".tmp"
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(payload)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp_name, REPORTS_PATH)
        except Exception:
            try:
                os.unlink(tmp_name)
            except OSError:
                pass
            raise
    except Exception as e:
        log.warning("persist model reports failed: %s", e)


def _prune_reports_unlocked(now: float | None = None) -> None:
    now = time.time() if now is None else now
    dead_models = []
    for model_id, kinds in _reports.items():
        dead_kinds = []
        for kind, installs in kinds.items():
            for iid, ts in list(installs.items()):
                if now - float(ts) > REPORT_WINDOW_SEC:
                    del installs[iid]
            if not installs:
                dead_kinds.append(kind)
        for kind in dead_kinds:
            del kinds[kind]
        if not kinds:
            dead_models.append(model_id)
    for model_id in dead_models:
        del _reports[model_id]


def crowd_unique_reporters(model_id: str) -> int:
    """Unique installs that reported any failure for this model in the window."""
    now = time.time()
    with _reports_lock:
        _prune_reports_unlocked(now)
        kinds = _reports.get(model_id, {})
        ids: set[str] = set()
        for installs in kinds.values():
            for iid, ts in installs.items():
                if now - float(ts) <= REPORT_WINDOW_SEC:
                    ids.add(iid)
        return len(ids)


def crowd_status(model_id: str) -> str:
    n = crowd_unique_reporters(model_id)
    if n >= REPORT_DOWN:
        return "down"
    if n >= REPORT_DEGRADED or model_id in KNOWN_DEGRADED_MODELS:
        return "degraded"
    return "up"


def record_model_failure(model_id: str, install_id: str, kind: str) -> dict:
    if not MODEL_RE.match(model_id):
        raise ValueError("bad model id")
    if not INSTALL_ID_RE.match(install_id):
        raise ValueError("bad install id")
    if kind not in ERROR_KINDS:
        raise ValueError("bad error kind")
    now = time.time()
    with _reports_lock:
        _prune_reports_unlocked(now)
        kinds = _reports.setdefault(model_id, {})
        installs = kinds.setdefault(kind, {})
        installs[install_id] = now
        _persist_reports_unlocked()
    status = crowd_status(model_id)
    return {
        "ok": True,
        "model": model_id,
        "kind": kind,
        "reporters": crowd_unique_reporters(model_id),
        "status": status,
        "available": status != "down",
    }


def record_model_success(model_id: str, install_id: str) -> dict:
    """Clear this install's failure reports after a successful reply."""
    if not MODEL_RE.match(model_id) or not INSTALL_ID_RE.match(install_id):
        raise ValueError("bad model/install id")
    with _reports_lock:
        kinds = _reports.get(model_id)
        if kinds:
            changed = False
            for installs in kinds.values():
                if install_id in installs:
                    del installs[install_id]
                    changed = True
            dead = [k for k, v in kinds.items() if not v]
            for k in dead:
                del kinds[k]
            if not kinds:
                _reports.pop(model_id, None)
            if changed:
                _persist_reports_unlocked()
    status = crowd_status(model_id)
    return {"ok": True, "model": model_id, "status": status, "available": status != "down"}


def resolve_models_plan(plan_id: str) -> str:
    """Map alias plans (e.g. premium) to the plan that owns the model list."""
    return PLAN_MODELS_ALIAS.get(plan_id, plan_id)


def models_with_availability(plan_id: str = PLAN_FREE) -> list[dict]:
    """Fast: no outbound OpenRouter calls — status from crowd reports only."""
    source = resolve_models_plan(plan_id)
    out: list[dict] = []
    for model_id in get_models(source):
        status = crowd_status(model_id)
        out.append(
            {
                "id": model_id,
                "status": status,
                "available": status != "down",
                "reporters": crowd_unique_reporters(model_id),
                "plan": plan_id,
                "models_from": source if source != plan_id else plan_id,
            }
        )
    return out


def validate_models(raw, *, allow_empty: bool = False) -> list[str]:
    if not isinstance(raw, list):
        raise ValueError("models must be an array")
    if not raw and not allow_empty:
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
    if not out and not allow_empty:
        raise ValueError("at least one model required")
    return out


def validate_plans_disjoint(plans: dict[str, list[str]]) -> None:
    owner: dict[str, str] = {}
    for plan_id, models in plans.items():
        for model in models or []:
            prev = owner.get(model)
            if prev and prev != plan_id:
                raise ValueError(
                    f"model {model} is in both {prev} and {plan_id} (plans must be disjoint)"
                )
            owner[model] = plan_id


def get_models(plan_id: str = PLAN_FREE) -> tuple[str, ...]:
    source = resolve_models_plan(plan_id)
    with _models_lock:
        return tuple(_runtime_models_by_plan.get(source) or ())


def get_all_plan_models() -> dict[str, list[str]]:
    """Own lists only (no aliases)."""
    with _models_lock:
        return {pid: list(_runtime_models_by_plan.get(pid) or []) for pid in PLAN_IDS}


def plan_for_model(model_id: str) -> str | None:
    """Which owning plan (not alias) contains this model id."""
    if not model_id:
        return None
    with _models_lock:
        for plan_id in PLAN_IDS:
            if model_id in (_runtime_models_by_plan.get(plan_id) or ()):
                return plan_id
    return None


def api_key_for_plan(plan_id: str) -> str:
    source = resolve_models_plan(plan_id)
    if source == PLAN_PREMIUM_PLUS:
        return OPENROUTER_API_KEY_PREMIUM_PLUS
    if source == PLAN_PREMIUM_ULTRA:
        return OPENROUTER_API_KEY_PREMIUM_ULTRA
    return OPENROUTER_API_KEY


def set_models(models: list[str], persist: bool = True) -> None:
    """Legacy: set free-plan models only."""
    set_plan_models({PLAN_FREE: models}, persist=persist)


def set_plan_models(plans: dict[str, list[str]], persist: bool = True) -> None:
    cleaned: dict[str, list[str]] = {}
    for plan_id in PLAN_IDS:
        raw = plans.get(plan_id)
        if raw is None and plan_id != PLAN_FREE:
            # Keep existing if omitted (partial update from old hub).
            with _models_lock:
                cleaned[plan_id] = list(_runtime_models_by_plan.get(plan_id) or [])
            continue
        cleaned[plan_id] = validate_models(
            raw if raw is not None else [],
            allow_empty=(plan_id != PLAN_FREE),
        )
    if PLAN_FREE not in cleaned or not cleaned[PLAN_FREE]:
        raise ValueError("free plan requires at least one model")
    validate_plans_disjoint(cleaned)
    with _models_lock:
        for plan_id in PLAN_IDS:
            _runtime_models_by_plan[plan_id] = list(cleaned[plan_id])
        if persist:
            _write_models_file(cleaned)


def _write_models_file(plans: dict[str, list[str]]) -> None:
    MODELS_PATH.parent.mkdir(parents=True, exist_ok=True)
    payload_obj = {plan_id: list(plans.get(plan_id) or []) for plan_id in PLAN_IDS}
    payload = json.dumps(payload_obj, ensure_ascii=False, indent=2) + "\n"
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
    try:
        raw = MODELS_PATH.read_text(encoding="utf-8")
        data = json.loads(raw)
        if isinstance(data, list):
            # Legacy file: plain array = free plan only.
            plans = {
                PLAN_FREE: validate_models(data),
                PLAN_PREMIUM_PLUS: [],
                PLAN_PREMIUM_ULTRA: [],
            }
        elif isinstance(data, dict):
            plans = {}
            for plan_id in PLAN_IDS:
                plans[plan_id] = validate_models(
                    data.get(plan_id) or ([] if plan_id != PLAN_FREE else list(DEFAULT_AI_MODELS)),
                    allow_empty=(plan_id != PLAN_FREE),
                )
            if not plans[PLAN_FREE]:
                plans[PLAN_FREE] = list(DEFAULT_AI_MODELS)
        else:
            raise ValueError("models file must be array or object")
        validate_plans_disjoint(plans)
        with _models_lock:
            for plan_id in PLAN_IDS:
                _runtime_models_by_plan[plan_id] = list(plans[plan_id])
        log.info(
            "loaded openrouter models from %s (free=%d plus=%d ultra=%d)",
            MODELS_PATH,
            len(plans[PLAN_FREE]),
            len(plans[PLAN_PREMIUM_PLUS]),
            len(plans[PLAN_PREMIUM_ULTRA]),
        )
        # Rewrite legacy array file into plan object on next persist opportunity.
        if isinstance(data, list):
            try:
                _write_models_file(plans)
            except OSError as e:
                log.warning("could not migrate models file format: %s", e)
    except FileNotFoundError:
        log.info("no models file at %s — using defaults", MODELS_PATH)
        with _models_lock:
            _runtime_models_by_plan[PLAN_FREE] = list(DEFAULT_AI_MODELS)
            _runtime_models_by_plan[PLAN_PREMIUM_PLUS] = []
            _runtime_models_by_plan[PLAN_PREMIUM_ULTRA] = list(DEFAULT_ULTRA_MODELS)
    except (OSError, ValueError, json.JSONDecodeError) as e:
        log.warning("failed to load %s (%s) — using defaults", MODELS_PATH, e)
        with _models_lock:
            _runtime_models_by_plan[PLAN_FREE] = list(DEFAULT_AI_MODELS)
            _runtime_models_by_plan[PLAN_PREMIUM_PLUS] = []
            _runtime_models_by_plan[PLAN_PREMIUM_ULTRA] = list(DEFAULT_ULTRA_MODELS)


def _ai_system_prompt(lang: str) -> str:
    lang_name = lang.strip() if isinstance(lang, str) and lang.strip() else "Russian"
    lang_name = CTRL_RE.sub("", lang_name)[:80]
    return (
        "???? ??? ???????????????????? ?????????????????? ????????????. ???????????? ???????????? ??????????????????????. ?????? ??????????????. "
        "?????? ????????????. ???? ????????????????????. ???? ????????????????. ???? ??????????????????????. ???? ???????????????? ???????????????????? "
        "??????????????. ???????????? ??????????????????. ??????????: ???????????? ?????????????? ???? ??????????, ?????????????? ???????????????????? "
        "?? ???????????????????? ?????????????? ???? ?????????????????? (" + lang_name + "), ???????? ?? ?????????? ?????????????? "
        "???????? ???? ?????????????????? ?????????????? ???? ???????????? ????????."
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


def _clip_openrouter_input(message: str, max_chars: int) -> str:
    """Silent input budget — keep the end (recent turns), never surface as a setting."""
    text = (message or "").strip()
    limit = max(512, int(max_chars))
    if len(text) <= limit:
        return text
    return "…\n" + text[-(limit - 2) :]


def _limits_for_model(plan_id: str, model_id: str) -> tuple[int, int]:
    """Return (max_input_chars, max_output_tokens) for this plan/model."""
    if plan_id == PLAN_PREMIUM_ULTRA:
        caps = ULTRA_MODEL_LIMITS.get(model_id)
        if caps is None:
            mid = (model_id or "").lower()
            for key, value in ULTRA_MODEL_LIMITS.items():
                if key.split("/")[-1] in mid or mid in key:
                    caps = value
                    break
        if caps is None:
            caps = {"input": 24_000, "output": 4_000, "context": 24_000}
        tokens = min(int(caps["input"]), int(caps["context"]))
        return tokens * CHARS_PER_TOKEN, int(caps["output"])
    return OPENROUTER_MAX_INPUT_CHARS, OPENROUTER_MAX_OUTPUT_TOKENS


def call_openrouter(
    message: str,
    lang: str,
    preferred_model: str | None = None,
    plan_id: str | None = None,
) -> str:
    resolved = PLAN_FREE
    if isinstance(plan_id, str) and plan_id.strip() in PLAN_IDS_ALL:
        resolved = plan_id.strip()
    elif preferred_model:
        preferred = preferred_model.strip()
        found = plan_for_model(preferred)
        if found:
            resolved = found
        # Unknown model id → stay on free and ignore preferred (legacy safety).
    plan_id = resolved
    api_key = api_key_for_plan(plan_id)
    if not api_key:
        env_name = {
            PLAN_PREMIUM_PLUS: "OPENROUTER_API_KEY_PREMIUM_PLUS",
            PLAN_PREMIUM_ULTRA: "OPENROUTER_API_KEY_PREMIUM_ULTRA",
        }.get(plan_id, "OPENROUTER_API_KEY")
        raise RuntimeError(f"{env_name} is not set")
    models = list(get_models(plan_id))
    if not models and plan_id == PLAN_PREMIUM_ULTRA:
        models = list(DEFAULT_ULTRA_MODELS)
    if not models:
        raise RuntimeError(f"no models configured for plan {plan_id}")
    if preferred_model:
        preferred = preferred_model.strip()
        if preferred in models:
            models = [preferred] + [m for m in models if m != preferred]
    system_prompt = _ai_system_prompt(lang)
    seed = random.getrandbits(63)
    failures = []
    for i, model in enumerate(models):
        in_chars, out_tokens = _limits_for_model(plan_id, model)
        clipped = _clip_openrouter_input(message, in_chars)
        body = {
            "model": model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": clipped},
            ],
            "temperature": 0.3,
            "max_tokens": out_tokens,
            "seed": seed,
        }
        req = urllib.request.Request(
            OPENROUTER_URL,
            data=json.dumps(body).encode("utf-8"),
            method="POST",
            headers={
                "Authorization": "Bearer " + api_key,
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
    raise RuntimeError("?????? ???????????? ???????????????????? (" + "; ".join(failures) + ")")


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


def _hub_client_token() -> str:
    if not HUB_FEATURES_TOKEN:
        raise RuntimeError("hub features proxy not configured (MGLA_HUB_FEATURES_TOKEN)")
    return HUB_FEATURES_TOKEN


def _hub_headers(content_type: str | None = "application/json; charset=utf-8") -> dict:
    headers = {
        "X-Mgla-Token": _hub_client_token(),
        "X-Mgla-Client": "mgla",
    }
    if content_type:
        headers["Content-Type"] = content_type
    return headers


def proxy_hub_features(body_bytes: bytes) -> tuple[int, bytes]:
    """Forward the client's features body to mglahub with the hub client token."""
    if not HUB_FEATURES_URL or not HUB_FEATURES_TOKEN:
        raise RuntimeError("hub features proxy not configured (MGLA_HUB_FEATURES_TOKEN)")
    req = urllib.request.Request(
        HUB_FEATURES_URL,
        data=body_bytes,
        method="POST",
        headers=_hub_headers(),
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return int(resp.status), resp.read()
    except urllib.error.HTTPError as e:
        return int(e.code), e.read()


def proxy_hub_update_check(body_bytes: bytes) -> tuple[int, bytes]:
    url = HUB_UPDATE_CHECK_URL
    if not url:
        raise RuntimeError("hub update check URL not configured")
    req = urllib.request.Request(
        url,
        data=body_bytes,
        method="POST",
        headers=_hub_headers(),
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return int(resp.status), resp.read()
    except urllib.error.HTTPError as e:
        return int(e.code), e.read()


def proxy_hub_stats_batch(body_bytes: bytes) -> tuple[int, bytes]:
    url = HUB_STATS_BATCH_URL
    if not url:
        raise RuntimeError("hub stats batch URL not configured")
    req = urllib.request.Request(
        url,
        data=body_bytes,
        method="POST",
        headers=_hub_headers(),
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return int(resp.status), resp.read()
    except urllib.error.HTTPError as e:
        return int(e.code), e.read()


def proxy_hub_update_apk_open(tg_ids_header: str = ""):
    url = HUB_UPDATE_APK_URL
    if not url:
        raise RuntimeError("hub update apk URL not configured")
    headers = _hub_headers(content_type=None)
    if tg_ids_header:
        headers["X-Mgla-Tg-Id"] = tg_ids_header.strip()[:512]
    req = urllib.request.Request(
        url,
        method="GET",
        headers=headers,
    )
    try:
        resp = urllib.request.urlopen(req, timeout=300)
    except urllib.error.HTTPError as e:
        return int(e.code), e.read(), None
    out_headers = {
        "Content-Type": resp.headers.get("Content-Type") or "application/vnd.android.package-archive",
        "Content-Length": resp.headers.get("Content-Length") or "",
        "Content-Disposition": resp.headers.get("Content-Disposition")
        or 'attachment; filename="mgla-update.apk"',
        "X-Mgla-Sha256": resp.headers.get("X-Mgla-Sha256") or "",
    }
    return 200, None, (resp, out_headers)


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

    def _check_client(self) -> bool:
        if not mgla_token.client_header_ok(self.headers):
            self._reply(401)
            return False
        return True

    def _check_token(self) -> bool:
        if not self._check_client():
            return False
        token = self.headers.get("X-Mgla-Token", "")
        if mgla_token.token_ok(token):
            return True
        if TOKEN:
            try:
                if hmac.compare_digest(token.encode(), TOKEN.encode()):
                    return True
            except Exception:
                pass
        self._reply(401)
        return False

    def _check_sync_token(self) -> bool:
        token = self.headers.get("X-Mgla-Sync-Token", "")
        if not SYNC_TOKEN or not hmac.compare_digest(token.encode(), SYNC_TOKEN.encode()):
            self._reply(401, b'{"error":"unauthorized"}')
            return False
        return True

    def _read_raw_body(self, max_len):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length <= 0 or length > max_len:
            self._reply(413)
            return None
        return self.rfile.read(length)

    def _read_json_body(self, max_len):
        raw = self._read_raw_body(max_len)
        if raw is None:
            return None
        try:
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._reply(400)
            return None

    def do_GET(self):
        path = urlsplit(self.path).path.rstrip("/") or "/"
        if path in ("/mgla-ai/v1/health", "/health"):
            models = get_models()
            body = json.dumps(
                {
                    "ok": True,
                    "models": len(models),
                    "features_proxy": bool(HUB_FEATURES_URL and HUB_FEATURES_TOKEN),
                    "update_proxy": bool(HUB_UPDATE_CHECK_URL and HUB_FEATURES_TOKEN),
                    "stats_proxy": bool(HUB_STATS_BATCH_URL and HUB_FEATURES_TOKEN),
                },
                ensure_ascii=False,
            ).encode("utf-8")
            self._reply(200, body)
        elif path == "/mgla-ai/v1/models":
            self._get_models()
        elif path == "/mgla-ai/v1/update-apk":
            self._get_update_apk()
        else:
            self._reply(404)

    def _get_models(self):
        if not self._check_token():
            return
        # `models` stays free-tier only so current clients keep working.
        free_models = models_with_availability(PLAN_FREE)
        plans_payload = {}
        for plan_id in PLAN_IDS_ALL:
            source = resolve_models_plan(plan_id)
            entry = {
                "id": plan_id,
                "label": PLAN_LABELS.get(plan_id, plan_id),
                "models": models_with_availability(plan_id),
            }
            if source != plan_id:
                entry["models_from"] = source
                entry["own_models"] = False
            else:
                entry["own_models"] = True
            plans_payload[plan_id] = entry
        self._reply(
            200,
            json.dumps(
                {
                    "ok": True,
                    "default_plan": PLAN_FREE,
                    "models": free_models,
                    "plans": plans_payload,
                    "aliases": dict(PLAN_MODELS_ALIAS),
                },
                ensure_ascii=False,
            ).encode("utf-8"),
        )

    def _post_model_report(self):
        """Clients report chat success/failure for crowd availability (up/degraded/down)."""
        if not self._check_token():
            return
        raw = self._read_json_body(4 * 1024)
        if raw is None:
            return
        if not isinstance(raw, dict):
            self._reply(400, b'{"error":"bad body"}')
            return
        model = raw.get("model")
        install_id = raw.get("install_id") or raw.get("iid")
        kind = raw.get("kind") or raw.get("error") or "timeout"
        if isinstance(kind, str):
            kind = kind.strip().lower()
            if kind in ("ok", "success", "clear"):
                try:
                    result = record_model_success(str(model or ""), str(install_id or ""))
                except ValueError as e:
                    self._reply(400, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
                    return
                self._reply(200, json.dumps(result, ensure_ascii=False).encode("utf-8"))
                return
            if "timeout" in kind:
                kind = "timeout"
            elif "endpoint" in kind or "404" in kind:
                kind = "no_endpoints"
            elif kind not in ERROR_KINDS:
                kind = "unavailable"
        try:
            result = record_model_failure(str(model or ""), str(install_id or ""), kind)
        except ValueError as e:
            self._reply(400, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        self._reply(200, json.dumps(result, ensure_ascii=False).encode("utf-8"))

    def do_POST(self):
        path = urlsplit(self.path).path.rstrip("/") or "/"
        if path == "/mgla-ai/v1/admin/models":
            self._post_admin_models()
        elif path in ("/mgla-ai/v1/auth/register", "/mgla-auth/v1/register"):
            self._post_auth_proxy(HUB_AUTH_REGISTER_URL)
        elif path in ("/mgla-ai/v1/auth/token", "/mgla-auth/v1/token"):
            self._post_auth_proxy(HUB_AUTH_TOKEN_URL)
        elif path == "/mgla-ai/v1/features":
            self._post_features_proxy()
        elif path == "/mgla-ai/v1/update-check":
            self._post_update_check()
        elif path == "/mgla-ai/v1/stats-batch":
            self._post_stats_batch()
        elif path == "/mgla-ai/v1/chat":
            self._post_chat()
        elif path in ("/mgla-ai/v1/models/report", "/mgla-ai/v1/model-report"):
            self._post_model_report()
        elif path == "/mgla-ai/v1/gemini":
            self._post_gemini()
        elif path == "/mgla-ai/v1/gemini/transcribe":
            self._post_gemini_transcribe()
        else:
            self._reply(404)

    def _post_auth_proxy(self, hub_url: str):
        """Enrollment/refresh: only X-Mgla-Client required (no access token yet)."""
        if not self._check_client():
            return
        if not hub_url:
            self._reply(503, b'{"error":"auth proxy not configured"}')
            return
        raw = self._read_raw_body(8 * 1024)
        if raw is None:
            return
        req = urllib.request.Request(
            hub_url,
            data=raw,
            method="POST",
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "X-Mgla-Client": "mgla",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                body = resp.read()
                self._reply(int(resp.status), body)
        except urllib.error.HTTPError as e:
            self._reply(int(e.code), e.read() or b'{"error":"hub auth failed"}')
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            log.warning("auth proxy failed: %s", e)
            self._reply(502, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))


    def _post_update_check(self):
        if not self._check_token():
            return
        raw = self._read_raw_body(MAX_UPDATE_CHECK_BODY)
        if raw is None:
            return
        try:
            code, body = proxy_hub_update_check(raw)
        except RuntimeError as e:
            self._reply(503, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            log.warning("update check proxy failed: %s", e)
            self._reply(502, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        self._reply(code, body)

    def _post_stats_batch(self):
        if not self._check_token():
            return
        raw = self._read_raw_body(MAX_STATS_BATCH_BODY)
        if raw is None:
            return
        try:
            code, body = proxy_hub_stats_batch(raw)
        except RuntimeError as e:
            self._reply(503, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            log.warning("stats batch proxy failed: %s", e)
            self._reply(502, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        self._reply(code, body)

    def _get_update_apk(self):
        if not self._check_token():
            return
        try:
            tg_hdr = self.headers.get("X-Mgla-Tg-Id", "") or ""
            code, err_body, opened = proxy_hub_update_apk_open(tg_hdr)
        except RuntimeError as e:
            self._reply(503, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            log.warning("update apk proxy failed: %s", e)
            self._reply(502, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        if opened is None:
            self._reply(code, err_body or b'{"error":"upstream"}')
            return
        resp, headers = opened
        try:
            self.send_response(200)
            self.send_header("Content-Type", headers["Content-Type"])
            if headers.get("Content-Length"):
                self.send_header("Content-Length", headers["Content-Length"])
            self.send_header("Content-Disposition", headers["Content-Disposition"])
            if headers.get("X-Mgla-Sha256"):
                self.send_header("X-Mgla-Sha256", headers["X-Mgla-Sha256"])
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            while True:
                chunk = resp.read(256 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)
        except BrokenPipeError:
            return
        finally:
            try:
                resp.close()
            except Exception:
                pass

    def _post_features_proxy(self):
        if not self._check_token():
            return
        raw = self._read_raw_body(MAX_FEATURES_BODY)
        if raw is None:
            return
        try:
            code, body = proxy_hub_features(raw)
        except RuntimeError as e:
            self._reply(503, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            log.warning("features proxy failed: %s", e)
            self._reply(502, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        self._reply(code, body)

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
            plans_in = raw.get("plans")
            if isinstance(plans_in, dict):
                plans = {pid: plans_in.get(pid) for pid in PLAN_IDS}
                # Prefer explicit free in plans; else legacy "models".
                if plans.get(PLAN_FREE) is None and raw.get("models") is not None:
                    plans[PLAN_FREE] = raw.get("models")
            else:
                plans = {
                    PLAN_FREE: raw.get("models"),
                    PLAN_PREMIUM_PLUS: None,
                    PLAN_PREMIUM_ULTRA: None,
                }
            set_plan_models(plans, persist=True)
            saved = get_all_plan_models()
        except ValueError as e:
            self._reply(400, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        except OSError as e:
            log.exception("persist models failed")
            self._reply(500, json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8"))
            return
        log.info(
            "openrouter models updated free=%d plus=%d ultra=%d",
            len(saved[PLAN_FREE]),
            len(saved[PLAN_PREMIUM_PLUS]),
            len(saved[PLAN_PREMIUM_ULTRA]),
        )
        self._reply(
            200,
            json.dumps(
                {"ok": True, "models": saved[PLAN_FREE], "plans": saved},
                ensure_ascii=False,
            ).encode("utf-8"),
        )

    def _post_chat(self):
        if not self._check_token():
            return
        if not (
            OPENROUTER_API_KEY
            or OPENROUTER_API_KEY_PREMIUM_PLUS
            or OPENROUTER_API_KEY_PREMIUM_ULTRA
        ):
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
        preferred_model = raw.get("model")
        if isinstance(preferred_model, str):
            preferred_model = preferred_model.strip() or None
        else:
            preferred_model = None
        client_plan = raw.get("plan")
        if isinstance(client_plan, str):
            client_plan = client_plan.strip() or None
        else:
            client_plan = None
        if not isinstance(message, str) or not message.strip():
            self._reply(400, b'{"error":"message required"}')
            return
        # Oversized bodies are clipped inside call_openrouter; hard reject only absurd sizes.
        if len(message) > MAX_AI_MESSAGE * 2:
            self._reply(413, b'{"error":"message too long"}')
            return
        try:
            content = call_openrouter(
                message.strip(),
                lang if isinstance(lang, str) else "Russian",
                preferred_model,
                client_plan,
            )
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
    load_reports_from_disk()
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    plans = get_all_plan_models()
    log.info(
        "listening on %s:%d, openrouter=%s plus=%s ultra=%s, sync=%s, features_proxy=%s, models free=%d plus=%d ultra=%d",
        HOST,
        PORT,
        "yes" if OPENROUTER_API_KEY else "no",
        "yes" if OPENROUTER_API_KEY_PREMIUM_PLUS else "no",
        "yes" if OPENROUTER_API_KEY_PREMIUM_ULTRA else "no",
        "yes" if SYNC_TOKEN else "no",
        "yes" if (HUB_FEATURES_URL and HUB_FEATURES_TOKEN) else "no",
        len(plans[PLAN_FREE]),
        len(plans[PLAN_PREMIUM_PLUS]),
        len(plans[PLAN_PREMIUM_ULTRA]),
    )
    httpd.serve_forever()


if __name__ == "__main__":
    main()
