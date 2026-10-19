#!/usr/bin/env python3
"""Shared Mgla access-token helpers (stdlib only).

Access tokens are compact HS256 JWTs signed with MGLA_TOKEN_SIGNING_KEY.
Legacy static tokens (MGLA_WS_TOKEN / hub token) remain accepted so old APKs
and server-to-server calls keep working during migration.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from typing import Optional

ACCESS_TTL_SEC = int(os.environ.get("MGLA_ACCESS_TTL_SEC", str(12 * 3600)))
REFRESH_TTL_SEC = int(os.environ.get("MGLA_REFRESH_TTL_SEC", str(180 * 24 * 3600)))
SIGNING_KEY = os.environ.get("MGLA_TOKEN_SIGNING_KEY", "").strip()
LEGACY_TOKENS = [
    t.strip()
    for t in (
        os.environ.get("MGLA_WS_TOKEN", ""),
        os.environ.get("MGLA_HUB_TOKEN", ""),
        os.environ.get("MGLA_HUB_FEATURES_TOKEN", ""),
        os.environ.get("MGLA_HUB_CLIENT_TOKEN", ""),
    )
    if t and t.strip()
]

IID_RE_LEN = 32

AUTH_SCHEMA = """
CREATE TABLE IF NOT EXISTS auth_installs (
    iid TEXT PRIMARY KEY,
    refresh_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    refreshed_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    app_version TEXT,
    mgla_version TEXT,
    revoked INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS auth_installs_expires ON auth_installs(expires_at);
"""


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64url_json(obj: dict) -> str:
    return _b64url(json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


def _b64url_decode(text: str) -> bytes:
    pad = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + pad)


def ensure_schema(db: sqlite3.Connection) -> None:
    db.executescript(AUTH_SCHEMA)
    try:
        import mgla_entitlements

        mgla_entitlements.ensure_accounts_schema(db)
    except Exception:
        db.commit()
    else:
        db.commit()


def token_identity(token: str, db: Optional[sqlite3.Connection] = None) -> tuple[str, Optional[str]]:
    """Return (\"jwt\", iid) or (\"legacy\", None) or (\"\", None) if invalid."""
    if not token:
        return "", None
    if legacy_token_ok(token):
        return "legacy", None
    iid = verify_access_token(token)
    if not iid:
        return "", None
    if db is not None:
        row = db.execute(
            "SELECT revoked, expires_at FROM auth_installs WHERE iid = ?", (iid,)
        ).fetchone()
        if row is not None:
            revoked, expires_at = int(row[0]), int(row[1])
            if revoked or expires_at < int(time.time()):
                return "", None
    return "jwt", iid


def hash_refresh(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def issue_access_token(iid: str, now: Optional[int] = None) -> tuple[str, int]:
    if not SIGNING_KEY:
        raise RuntimeError("MGLA_TOKEN_SIGNING_KEY not set")
    if not iid or len(iid) != IID_RE_LEN:
        raise ValueError("bad iid")
    now = int(now or time.time())
    exp = now + ACCESS_TTL_SEC
    header = _b64url_json({"alg": "HS256", "typ": "JWT"})
    payload = _b64url_json({"v": 1, "iid": iid, "iat": now, "exp": exp})
    msg = f"{header}.{payload}".encode("ascii")
    sig = _b64url(hmac.new(SIGNING_KEY.encode("utf-8"), msg, hashlib.sha256).digest())
    return f"{header}.{payload}.{sig}", ACCESS_TTL_SEC


def verify_access_token(token: str, now: Optional[int] = None) -> Optional[str]:
    """Return iid if JWT is valid, else None."""
    if not token or not SIGNING_KEY or token.count(".") != 2:
        return None
    header_b64, payload_b64, sig_b64 = token.split(".", 2)
    msg = f"{header_b64}.{payload_b64}".encode("ascii")
    expected = _b64url(hmac.new(SIGNING_KEY.encode("utf-8"), msg, hashlib.sha256).digest())
    if not hmac.compare_digest(expected, sig_b64):
        return None
    try:
        payload = json.loads(_b64url_decode(payload_b64).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    if payload.get("v") != 1:
        return None
    iid = payload.get("iid")
    exp = payload.get("exp")
    if not isinstance(iid, str) or len(iid) != IID_RE_LEN:
        return None
    if not isinstance(exp, int):
        return None
    now = int(now or time.time())
    if exp < now:
        return None
    return iid


def legacy_token_ok(token: str) -> bool:
    if not token:
        return False
    for legacy in LEGACY_TOKENS:
        try:
            if hmac.compare_digest(token.encode("utf-8"), legacy.encode("utf-8")):
                return True
        except Exception:
            continue
    return False


def token_ok(token: str, db: Optional[sqlite3.Connection] = None) -> bool:
    """Accept a live JWT or a configured legacy static token."""
    if legacy_token_ok(token):
        return True
    iid = verify_access_token(token)
    if not iid:
        return False
    if db is None:
        return True
    row = db.execute(
        "SELECT revoked, expires_at FROM auth_installs WHERE iid = ?", (iid,)
    ).fetchone()
    if row is None:
        # Token signed by us but install row missing — still accept until refresh.
        return True
    revoked, expires_at = int(row[0]), int(row[1])
    if revoked:
        return False
    if expires_at < int(time.time()):
        return False
    return True


def client_header_ok(headers) -> bool:
    try:
        got = headers.get("X-Mgla-Client") or headers.get("x-mgla-client") or ""
    except Exception:
        got = ""
    got = (got or "").strip().lower()
    return got == "mgla" or got.startswith("mgla/")


def register_install(
    db: sqlite3.Connection,
    iid: str,
    app_version: str = "",
    mgla_version: str = "",
) -> dict:
    if not iid or len(iid) != IID_RE_LEN or any(c not in "0123456789abcdef" for c in iid):
        raise ValueError("bad iid")
    now = int(time.time())
    refresh = secrets.token_urlsafe(32)
    rh = hash_refresh(refresh)
    exp = now + REFRESH_TTL_SEC
    db.execute(
        """INSERT INTO auth_installs(iid, refresh_hash, created_at, refreshed_at, expires_at,
                                     app_version, mgla_version, revoked)
           VALUES(?,?,?,?,?,?,?,0)
           ON CONFLICT(iid) DO UPDATE SET
             refresh_hash=excluded.refresh_hash,
             refreshed_at=excluded.refreshed_at,
             expires_at=excluded.expires_at,
             app_version=excluded.app_version,
             mgla_version=excluded.mgla_version,
             revoked=0""",
        (iid, rh, now, now, exp, (app_version or "")[:64], (mgla_version or "")[:64]),
    )
    db.commit()
    access, ttl = issue_access_token(iid, now)
    return {
        "v": 1,
        "iid": iid,
        "access_token": access,
        "refresh_token": refresh,
        "expires_in": ttl,
        "refresh_expires_in": REFRESH_TTL_SEC,
    }


def refresh_install(db: sqlite3.Connection, iid: str, refresh_token: str) -> dict:
    if not iid or len(iid) != IID_RE_LEN:
        raise ValueError("bad iid")
    if not refresh_token or len(refresh_token) < 20:
        raise ValueError("bad refresh")
    row = db.execute(
        "SELECT refresh_hash, expires_at, revoked FROM auth_installs WHERE iid = ?",
        (iid,),
    ).fetchone()
    if row is None:
        raise PermissionError("unknown install")
    rh, expires_at, revoked = row[0], int(row[1]), int(row[2])
    if revoked:
        raise PermissionError("revoked")
    now = int(time.time())
    if expires_at < now:
        raise PermissionError("refresh expired")
    if not hmac.compare_digest(rh, hash_refresh(refresh_token)):
        raise PermissionError("bad refresh")
    # Rotate refresh token.
    new_refresh = secrets.token_urlsafe(32)
    new_exp = now + REFRESH_TTL_SEC
    db.execute(
        """UPDATE auth_installs SET refresh_hash=?, refreshed_at=?, expires_at=? WHERE iid=?""",
        (hash_refresh(new_refresh), now, new_exp, iid),
    )
    db.commit()
    access, ttl = issue_access_token(iid, now)
    return {
        "v": 1,
        "iid": iid,
        "access_token": access,
        "refresh_token": new_refresh,
        "expires_in": ttl,
        "refresh_expires_in": REFRESH_TTL_SEC,
    }
