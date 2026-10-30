"""Manual cross-device settings sync for any paid Mgla plan (per Telegram user id).

Protocol (POST /mgla-settings/v1/sync):
  Request:  { tg_id, v, updated_at, settings:{...} }
  Response: { ok, plan, v, updated_at, settings:{...} }

Whole-document LWW on updated_at (ms). Plans: premium, premium_plus, premium_ultra.
Secrets / tokens are stripped server-side before persist.
"""

from __future__ import annotations

import json
import re
import sqlite3
import time
from typing import Any

import mgla_entitlements

ALLOWED_PLANS = (
    mgla_entitlements.PLAN_PREMIUM,
    mgla_entitlements.PLAN_PREMIUM_PLUS,
    mgla_entitlements.PLAN_PREMIUM_ULTRA,
)

MAX_SETTINGS_BYTES = 400 * 1024
MAX_DEPTH = 8
MAX_KEYS = 800

_SECRET_KEY_RE = re.compile(
    r"(token|secret|password|passwd|api[_-]?key|auth|jwt|refresh|spki|socks_pass|socks_user)",
    re.IGNORECASE,
)
_BLOCKED_KEY_PREFIXES = (
    "mgla_subscription_",
    "mgla_auth",
    "ws_relay_token",
    "hub_token",
    "ws_saved_",
    "dpi_saved_",
    "dpi_socks_",
    "deleted_notify_",
    "memory_ai_",
    "ai_chats_",
    "install_id",
)


def ensure_schema(db: sqlite3.Connection) -> None:
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS settings_sync (
            tg_id INTEGER PRIMARY KEY,
            payload TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """
    )


def _as_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _empty_doc() -> dict[str, Any]:
    return {"v": 1, "updated_at": 0, "settings": {}}


def _load(db: sqlite3.Connection, tg_id: int) -> dict[str, Any]:
    ensure_schema(db)
    row = db.execute(
        "SELECT payload, updated_at FROM settings_sync WHERE tg_id = ?",
        (int(tg_id),),
    ).fetchone()
    if not row or not row[0]:
        return _empty_doc()
    try:
        data = json.loads(row[0])
    except (TypeError, ValueError, json.JSONDecodeError):
        return _empty_doc()
    if not isinstance(data, dict):
        return _empty_doc()
    settings = data.get("settings")
    if not isinstance(settings, dict):
        settings = {}
    return {
        "v": max(1, _as_int(data.get("v"), 1)),
        "updated_at": max(0, _as_int(data.get("updated_at"), _as_int(row[1], 0))),
        "settings": settings,
    }


def _save(db: sqlite3.Connection, tg_id: int, doc: dict[str, Any]) -> None:
    ensure_schema(db)
    updated_at = max(0, _as_int(doc.get("updated_at"), 0))
    payload = json.dumps(
        {
            "v": max(1, _as_int(doc.get("v"), 1)),
            "updated_at": updated_at,
            "settings": doc.get("settings") if isinstance(doc.get("settings"), dict) else {},
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
    if len(payload.encode("utf-8")) > MAX_SETTINGS_BYTES:
        raise ValueError("settings payload too large")
    db.execute(
        """
        INSERT INTO settings_sync(tg_id, payload, updated_at) VALUES(?, ?, ?)
        ON CONFLICT(tg_id) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at
        """,
        (int(tg_id), payload, updated_at),
    )


def _blocked_key(key: str) -> bool:
    if not isinstance(key, str) or not key:
        return True
    if _SECRET_KEY_RE.search(key):
        return True
    low = key.lower()
    return any(low.startswith(p) for p in _BLOCKED_KEY_PREFIXES)


def _sanitize(value: Any, depth: int = 0, key_count: list[int] | None = None) -> Any:
    if key_count is None:
        key_count = [0]
    if depth > MAX_DEPTH:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return int(value)
    if isinstance(value, float):
        if value != value:  # NaN
            return 0
        return value
    if isinstance(value, str):
        if len(value) > 8_000:
            return value[:8_000]
        return value
    if value is None:
        return None
    if isinstance(value, list):
        out = []
        for item in value[:200]:
            cleaned = _sanitize(item, depth + 1, key_count)
            if cleaned is not None:
                out.append(cleaned)
        return out
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for k, v in value.items():
            if key_count[0] >= MAX_KEYS:
                break
            if not isinstance(k, str) or _blocked_key(k):
                continue
            cleaned = _sanitize(v, depth + 1, key_count)
            if cleaned is None and not isinstance(v, (bool, int, float, str, list, dict)):
                continue
            key_count[0] += 1
            out[k[:120]] = cleaned
        return out
    return None


def sync(db: sqlite3.Connection, tg_id: int, raw: dict[str, Any], plan: str) -> dict[str, Any]:
    if plan not in ALLOWED_PLANS:
        raise PermissionError("Premium, Premium+ or Ultra required")

    incoming_settings = raw.get("settings")
    if incoming_settings is not None and not isinstance(incoming_settings, dict):
        raise ValueError("settings must be an object")

    server = _load(db, tg_id)
    client_updated = max(0, _as_int(raw.get("updated_at"), 0))
    now = int(time.time() * 1000)

    if isinstance(incoming_settings, dict) and client_updated > 0:
        cleaned = _sanitize(incoming_settings)
        if not isinstance(cleaned, dict):
            cleaned = {}
        if client_updated >= server["updated_at"]:
            server = {
                "v": max(1, _as_int(raw.get("v"), 1)),
                "updated_at": min(client_updated, now + 60_000),
                "settings": cleaned,
            }
            _save(db, tg_id, server)

    return {
        "ok": True,
        "plan": plan,
        "v": server["v"],
        "updated_at": server["updated_at"],
        "settings": server["settings"],
    }
