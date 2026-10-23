"""Cross-device task sync for Premium / Premium+ / Ultra (per Telegram user id).

Protocol (POST /mgla-tasks/v1/sync):
  Request:  { tg_id, groups:[...], tasks:[...], deleted_groups:[uuid...], deleted_tasks:[uuid...] }
  Response: { ok, plan, groups, tasks, deleted_groups, deleted_tasks }

Each group/task carries uuid + updatedAt (ms). LWW merge; tombstones win when
deletedAt >= updatedAt. Plans allowed: premium, premium_plus, premium_ultra.
"""

from __future__ import annotations

import json
import sqlite3
import time
from typing import Any

import mgla_entitlements

ALLOWED_PLANS = (
    mgla_entitlements.PLAN_PREMIUM,
    mgla_entitlements.PLAN_PREMIUM_PLUS,
    mgla_entitlements.PLAN_PREMIUM_ULTRA,
)

MAX_GROUPS = 200
MAX_TASKS = 2000
MAX_TOMBSTONES = 2000
MAX_TITLE = 500
MAX_NOTE = 8000
MAX_SUBS = 100


def ensure_schema(db: sqlite3.Connection) -> None:
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS task_sync (
            tg_id INTEGER PRIMARY KEY,
            payload TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """
    )


def _empty_doc() -> dict[str, Any]:
    return {
        "groups": {},
        "tasks": {},
        "deleted_groups": {},
        "deleted_tasks": {},
    }


def _load(db: sqlite3.Connection, tg_id: int) -> dict[str, Any]:
    ensure_schema(db)
    row = db.execute(
        "SELECT payload FROM task_sync WHERE tg_id = ?", (int(tg_id),)
    ).fetchone()
    if not row or not row[0]:
        return _empty_doc()
    try:
        data = json.loads(row[0])
    except (TypeError, ValueError, json.JSONDecodeError):
        return _empty_doc()
    if not isinstance(data, dict):
        return _empty_doc()
    for key in ("groups", "tasks", "deleted_groups", "deleted_tasks"):
        if not isinstance(data.get(key), dict):
            data[key] = {}
    return data


def _save(db: sqlite3.Connection, tg_id: int, doc: dict[str, Any]) -> None:
    ensure_schema(db)
    now = int(time.time() * 1000)
    payload = json.dumps(doc, ensure_ascii=False, separators=(",", ":"))
    db.execute(
        """
        INSERT INTO task_sync(tg_id, payload, updated_at) VALUES(?, ?, ?)
        ON CONFLICT(tg_id) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at
        """,
        (int(tg_id), payload, now),
    )


def _clean_str(value: Any, limit: int) -> str:
    if not isinstance(value, str):
        return ""
    return value.strip()[:limit]


def _as_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        return value.strip().lower() in ("1", "true", "yes")
    return False


def _normalize_group(raw: Any) -> dict[str, Any] | None:
    if not isinstance(raw, dict):
        return None
    uuid = _clean_str(raw.get("uuid"), 64)
    if not uuid:
        return None
    updated = _as_int(raw.get("updatedAt") or raw.get("updated_at"), 0)
    if updated <= 0:
        updated = int(time.time() * 1000)
    return {
        "uuid": uuid,
        "name": _clean_str(raw.get("name"), 120) or "Группа",
        "color": _as_int(raw.get("color"), 0),
        "sortOrder": _as_int(raw.get("sortOrder") or raw.get("sort_order"), 0),
        "updatedAt": updated,
    }


def _normalize_sub(raw: Any, index: int) -> dict[str, Any] | None:
    if not isinstance(raw, dict):
        return None
    title = _clean_str(raw.get("title"), 300)
    if not title:
        return None
    uuid = _clean_str(raw.get("uuid"), 64) or f"sub-{index}"
    return {
        "uuid": uuid,
        "title": title,
        "done": _as_bool(raw.get("done")),
        "sortOrder": _as_int(raw.get("sortOrder") or raw.get("sort_order"), index),
    }


def _normalize_task(raw: Any) -> dict[str, Any] | None:
    if not isinstance(raw, dict):
        return None
    uuid = _clean_str(raw.get("uuid"), 64)
    title = _clean_str(raw.get("title"), MAX_TITLE)
    if not uuid or not title:
        return None
    updated = _as_int(raw.get("updatedAt") or raw.get("updated_at"), 0)
    if updated <= 0:
        updated = int(time.time() * 1000)
    subs_in = raw.get("subItems") or raw.get("sub_items") or []
    subs: list[dict[str, Any]] = []
    if isinstance(subs_in, list):
        for i, item in enumerate(subs_in[:MAX_SUBS]):
            sub = _normalize_sub(item, i)
            if sub:
                subs.append(sub)
    return {
        "uuid": uuid,
        "title": title,
        "note": _clean_str(raw.get("note"), MAX_NOTE),
        "groupUuid": _clean_str(raw.get("groupUuid") or raw.get("group_uuid"), 64),
        "dueAt": _as_int(raw.get("dueAt") or raw.get("due_at"), 0),
        "remindAt": _as_int(raw.get("remindAt") or raw.get("remind_at"), 0),
        "done": _as_bool(raw.get("done")),
        "doneAt": _as_int(raw.get("doneAt") or raw.get("done_at"), 0),
        "createdAt": _as_int(raw.get("createdAt") or raw.get("created_at"), updated),
        "sortOrder": _as_int(raw.get("sortOrder") or raw.get("sort_order"), 0),
        "dialogId": _as_int(raw.get("dialogId") or raw.get("dialog_id"), 0),
        "messageId": _as_int(raw.get("messageId") or raw.get("message_id"), 0),
        "repeatInterval": _as_int(raw.get("repeatInterval") or raw.get("repeat_interval"), 0),
        "repeatUnit": _as_int(raw.get("repeatUnit") or raw.get("repeat_unit"), 0),
        "priority": _as_int(raw.get("priority"), 0),
        "subItems": subs,
        "updatedAt": updated,
    }


def _merge_entity(dst: dict[str, Any], uuid: str, entity: dict[str, Any], tombstones: dict[str, int]) -> None:
    deleted_at = int(tombstones.get(uuid) or 0)
    updated = int(entity.get("updatedAt") or 0)
    if deleted_at > 0 and deleted_at >= updated:
        dst.pop(uuid, None)
        return
    prev = dst.get(uuid)
    if prev is None or int(prev.get("updatedAt") or 0) <= updated:
        dst[uuid] = entity
        if deleted_at > 0 and updated > deleted_at:
            tombstones.pop(uuid, None)


def _apply_tombstones(dst: dict[str, Any], tombstones: dict[str, int], incoming: list[Any]) -> None:
    for item in incoming[:MAX_TOMBSTONES]:
        if isinstance(item, dict):
            uuid = _clean_str(item.get("uuid"), 64)
            deleted_at = _as_int(item.get("deletedAt") or item.get("deleted_at"), 0)
        else:
            uuid = _clean_str(item, 64)
            deleted_at = int(time.time() * 1000)
        if not uuid:
            continue
        if deleted_at <= 0:
            deleted_at = int(time.time() * 1000)
        prev = int(tombstones.get(uuid) or 0)
        if deleted_at >= prev:
            tombstones[uuid] = deleted_at
        entity = dst.get(uuid)
        if entity is not None and int(entity.get("updatedAt") or 0) <= tombstones[uuid]:
            dst.pop(uuid, None)


def merge_client(doc: dict[str, Any], body: dict[str, Any]) -> dict[str, Any]:
    groups = dict(doc.get("groups") or {})
    tasks = dict(doc.get("tasks") or {})
    del_groups = {str(k): int(v) for k, v in (doc.get("deleted_groups") or {}).items()}
    del_tasks = {str(k): int(v) for k, v in (doc.get("deleted_tasks") or {}).items()}

    raw_groups = body.get("groups") or []
    if isinstance(raw_groups, list):
        for item in raw_groups[:MAX_GROUPS]:
            g = _normalize_group(item)
            if g:
                _merge_entity(groups, g["uuid"], g, del_groups)

    raw_tasks = body.get("tasks") or []
    if isinstance(raw_tasks, list):
        for item in raw_tasks[:MAX_TASKS]:
            t = _normalize_task(item)
            if t:
                _merge_entity(tasks, t["uuid"], t, del_tasks)

    _apply_tombstones(groups, del_groups, body.get("deleted_groups") or [])
    _apply_tombstones(tasks, del_tasks, body.get("deleted_tasks") or [])

    # Drop orphan group refs softly (keep task, clear groupUuid).
    for task in tasks.values():
        gu = task.get("groupUuid") or ""
        if gu and gu not in groups:
            task["groupUuid"] = ""

    # Cap tombstone maps (keep newest).
    def trim_tombs(m: dict[str, int]) -> dict[str, int]:
        if len(m) <= MAX_TOMBSTONES:
            return m
        items = sorted(m.items(), key=lambda kv: kv[1], reverse=True)[:MAX_TOMBSTONES]
        return dict(items)

    return {
        "groups": groups,
        "tasks": tasks,
        "deleted_groups": trim_tombs(del_groups),
        "deleted_tasks": trim_tombs(del_tasks),
    }


def doc_to_response(doc: dict[str, Any], plan: str) -> dict[str, Any]:
    groups = sorted(
        (doc.get("groups") or {}).values(),
        key=lambda g: (int(g.get("sortOrder") or 0), str(g.get("uuid") or "")),
    )
    tasks = sorted(
        (doc.get("tasks") or {}).values(),
        key=lambda t: (int(t.get("sortOrder") or 0), str(t.get("uuid") or "")),
    )
    return {
        "ok": True,
        "v": 1,
        "plan": plan,
        "groups": groups,
        "tasks": tasks,
        "deleted_groups": [
            {"uuid": u, "deletedAt": ts}
            for u, ts in sorted((doc.get("deleted_groups") or {}).items(), key=lambda kv: kv[1])
        ],
        "deleted_tasks": [
            {"uuid": u, "deletedAt": ts}
            for u, ts in sorted((doc.get("deleted_tasks") or {}).items(), key=lambda kv: kv[1])
        ],
    }


def sync(db: sqlite3.Connection, tg_id: int, body: dict[str, Any], plan: str) -> dict[str, Any]:
    if plan not in ALLOWED_PLANS:
        raise PermissionError("tasks sync requires Premium, Premium+ or Ultra")
    doc = _load(db, tg_id)
    merged = merge_client(doc, body if isinstance(body, dict) else {})
    _save(db, tg_id, merged)
    return doc_to_response(merged, plan)
