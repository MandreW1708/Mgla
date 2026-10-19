#!/usr/bin/env python3
"""Shared subscription helpers: plan ranks, tg_id parsing, install↔account binding."""

from __future__ import annotations

import sqlite3
import time
from typing import Iterable, List, Optional, Sequence, Set

PLAN_FREE = "free"
PLAN_PREMIUM = "premium"
PLAN_PREMIUM_PLUS = "premium_plus"
PLAN_PREMIUM_ULTRA = "premium_ultra"

PLAN_RANK = {
    PLAN_FREE: 0,
    PLAN_PREMIUM: 1,
    PLAN_PREMIUM_PLUS: 2,
    PLAN_PREMIUM_ULTRA: 3,
}

ACCOUNTS_SCHEMA = """
CREATE TABLE IF NOT EXISTS auth_install_accounts (
    iid TEXT NOT NULL,
    tg_id INTEGER NOT NULL,
    first_seen INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    PRIMARY KEY (iid, tg_id)
);
CREATE INDEX IF NOT EXISTS auth_install_accounts_tg ON auth_install_accounts(tg_id);
"""


def ensure_accounts_schema(db: sqlite3.Connection) -> None:
    db.executescript(ACCOUNTS_SCHEMA)
    db.commit()


def normalize_plan(plan_id: Optional[str]) -> str:
    if isinstance(plan_id, str) and plan_id.strip() in PLAN_RANK:
        return plan_id.strip()
    return PLAN_FREE


def plan_rank(plan_id: Optional[str]) -> int:
    return PLAN_RANK.get(normalize_plan(plan_id), 0)


def best_plan(plans: Iterable[str]) -> str:
    best = PLAN_FREE
    best_r = 0
    for plan in plans or ():
        pid = normalize_plan(plan)
        rank = PLAN_RANK.get(pid, 0)
        if rank > best_r:
            best = pid
            best_r = rank
    return best


def clamp_plan(claimed: Optional[str], hub_plan: Optional[str]) -> str:
    """Never allow a client claim above the hub-verified plan.

    Explicit lower claims are kept (e.g. utilities always request {@code free}
    even when the subscriber is Premium+ / Ultra). Missing claim → hub plan.
    """
    hub = normalize_plan(hub_plan)
    if claimed is None or (isinstance(claimed, str) and not claimed.strip()):
        return hub
    claim = normalize_plan(claimed)
    if plan_rank(claim) <= plan_rank(hub):
        return claim
    return hub


def parse_tg_ids(*sources) -> List[int]:
    """Collect positive Telegram user ids from ints, digit-strings, lists, CSV headers."""
    out: List[int] = []
    seen: Set[int] = set()

    def add(value) -> None:
        tid = None
        if isinstance(value, bool):
            return
        if isinstance(value, int) and value > 0:
            tid = value
        elif isinstance(value, str):
            raw = value.strip()
            if not raw:
                return
            if raw.isdigit():
                tid = int(raw)
            else:
                for part in raw.replace(";", ",").split(","):
                    part = part.strip()
                    if part.isdigit():
                        add(int(part))
                return
        elif isinstance(value, (list, tuple)):
            for item in value:
                add(item)
            return
        if tid is None or tid <= 0 or tid in seen:
            return
        seen.add(tid)
        out.append(tid)

    for src in sources:
        add(src)
    return out


def touch_install_accounts(db: sqlite3.Connection, iid: str, tg_ids: Sequence[int]) -> None:
    if not iid or not tg_ids:
        return
    now = int(time.time())
    for tid in tg_ids:
        if int(tid) <= 0:
            continue
        db.execute(
            """INSERT INTO auth_install_accounts(iid, tg_id, first_seen, last_seen)
               VALUES(?,?,?,?)
               ON CONFLICT(iid, tg_id) DO UPDATE SET last_seen=excluded.last_seen""",
            (iid, int(tid), now, now),
        )
    db.commit()


def install_owns_tg(db: sqlite3.Connection, iid: str, tg_id: int) -> bool:
    if not iid or tg_id <= 0:
        return False
    row = db.execute(
        "SELECT 1 FROM auth_install_accounts WHERE iid=? AND tg_id=? LIMIT 1",
        (iid, int(tg_id)),
    ).fetchone()
    return row is not None


def filter_owned_tg_ids(db: sqlite3.Connection, iid: str, tg_ids: Sequence[int]) -> List[int]:
    if not iid:
        return []
    owned = []
    for tid in tg_ids:
        if install_owns_tg(db, iid, int(tid)):
            owned.append(int(tid))
    return owned
