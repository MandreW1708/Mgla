#!/usr/bin/env python3
"""
Mgla WS relay: WebSocket /apiws?dc=N[&media=1] ??? Telegram

  ??? ?????????????? ???????????????????? ??? ???????????????? ???? ???????????????????? :443 ????????????????????
  ??? ?????????? ??? ?????????? ?????????????????? ?????????????????????? MTProxy (127.0.0.1:2398),
    ?? ???????? ???????????????????? ?????????????? middle-end (:8888); ?????????? :443 ?????? ?????????? ????????????

  pip3 install 'websockets>=12,<16' 'cryptography>=42,<47'
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import secrets
import time
from typing import Dict, Optional, Tuple
from urllib.parse import parse_qs, urlparse

import websockets

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
except Exception:  # pragma: no cover
    Cipher = None

LOG = logging.getLogger("mgla-ws-relay")

LISTEN_HOST = os.environ.get("MGLA_WS_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("MGLA_WS_PORT", "8766"))
AUTH_TOKEN = os.environ.get("MGLA_WS_TOKEN", "").strip()
MTPROXY_HOST = os.environ.get("MGLA_MTPROXY_HOST", "127.0.0.1")
MTPROXY_PORT = int(os.environ.get("MGLA_MTPROXY_PORT", "2398"))
# Verify Ultra / priority against mglahub subscriptions (never trust client headers alone).
HUB_ENTITLEMENTS_URL = os.environ.get(
    "MGLA_HUB_ENTITLEMENTS_URL",
    "https://mglahub.mooo.com/mgla-config/v1/entitlements",
).strip()
HUB_ENTITLEMENTS_TOKEN = (
    os.environ.get("MGLA_HUB_ENTITLEMENTS_TOKEN", "").strip()
    or os.environ.get("MGLA_HUB_SYNC_TOKEN", "").strip()
    or os.environ.get("MGLA_HUB_FEATURES_TOKEN", "").strip()
    or AUTH_TOKEN
)
_secret_hex = (
    os.environ.get("MGLA_WEB_PROXY_SECRET")
    or os.environ.get("MTPROXY_SECRET")
    or ""
).strip().lower()
MTPROXY_SECRET = bytes.fromhex(_secret_hex) if len(_secret_hex) == 32 else b""

DC_MAIN: Dict[int, list] = {
    1: ["149.154.175.50"],
    2: ["149.154.167.51", "95.161.76.100"],
    3: ["149.154.175.100"],
    4: ["91.108.4.178", "91.108.4.206", "91.108.4.155", "149.154.167.91"],
    5: ["91.108.56.134", "91.108.56.127", "149.154.171.5"],
    203: ["91.105.192.110", "91.105.192.100"],
}
# Fallback only when MTProxy is down. Prefer middle-end via MTProxy for media.
DC_MEDIA: Dict[int, list] = {
    1: ["149.154.175.50"],
    2: ["149.154.167.151", "149.154.167.51"],
    3: ["149.154.175.100"],
    4: ["149.154.164.250", "149.154.166.120"],
    5: ["91.108.56.134", "91.108.56.127", "149.154.171.5"],
    203: ["91.105.192.110", "91.105.192.100"],
}

_silent_until: Dict[tuple, float] = {}
CONNECT_TIMEOUT = 6.0
CONNECT_TIMEOUT_ULTRA = 8.0
# Keepalive: longer pong wait so brief mobile radio blips / hub lag don't tear Ultra bridges.
WS_PING_INTERVAL = 30.0
WS_PING_TIMEOUT = 90.0
TCP_BUF = 512 * 1024
# Needs net.core.rmem_max/wmem_max >= this (see deploy sysctl on hub).
TCP_BUF_ULTRA = 4 * 1024 * 1024
# Drain TCP write buffer only after this much is queued (throughput hint).
# Keep Ultra watermark modest: large delays break MTProto upload RPCs
# ("file loads but never sends") because the last chunks sit un-drained
# while the client waits for a server ack that never arrives.
TCP_DRAIN_WATERMARK = 64 * 1024
TCP_DRAIN_WATERMARK_ULTRA = 128 * 1024
MAX_WS_SIZE = 32 * 1024 * 1024
# Soft capacity: standard clients share SOFT_CAP - ULTRA_RESERVED slots.
# Ultra always accepted (best-effort QoS). On mglahub set MGLA_WS_ULTRA_ONLY=1
# so this node is a dedicated Ultra path and never serves free/premium clients.
SOFT_CAP = int(os.environ.get("MGLA_WS_SOFT_CAP", "220"))
ULTRA_RESERVED = int(os.environ.get("MGLA_WS_ULTRA_RESERVED", "40"))
STANDARD_WAIT_SEC = float(os.environ.get("MGLA_WS_STANDARD_WAIT_SEC", "6"))
ULTRA_ONLY = os.environ.get("MGLA_WS_ULTRA_ONLY", "").strip().lower() in (
    "1", "true", "yes", "on",
)

_slot_lock: Optional[asyncio.Lock] = None
_active_standard = 0
_active_ultra = 0
_standard_waiters: list = []  # list of asyncio.Event


def _get_slot_lock() -> asyncio.Lock:
    global _slot_lock
    if _slot_lock is None:
        _slot_lock = asyncio.Lock()
    return _slot_lock
_PROTO_TAGS = (b"\xef" * 4, b"\xee" * 4, b"\xdd" * 4)
_RESERVED_FIRST = {0xEF}
_RESERVED_STARTS = {
    b"HEAD", b"POST", b"GET ",
    b"\xee\xee\xee\xee", b"\xdd\xdd\xdd\xdd",
    b"\x16\x03\x01\x02",
}


class AesCtr:
    def __init__(self, key: bytes, iv: bytes, skip: int = 0):
        if Cipher is None:
            raise RuntimeError("cryptography required")
        self._e = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
        if skip:
            self._e.update(b"\x00" * skip)

    def update(self, data: bytes) -> bytes:
        if not data:
            return data
        return self._e.update(data)


def resolve_dc(path: str) -> Optional[int]:
    parsed = urlparse(path)
    if "/apiws" not in parsed.path:
        return None
    raw = parse_qs(parsed.query).get("dc", [None])[0]
    if raw is None:
        return None
    try:
        dc = abs(int(raw))
    except ValueError:
        return None
    if dc == 203 or 1 <= dc <= 5:
        return dc
    return None


def want_media(path: str) -> bool:
    raw = parse_qs(urlparse(path).query).get("media", ["0"])[0]
    return str(raw).lower() in ("1", "true", "yes")


def dc_index_from_init(packet: bytes) -> Optional[int]:
    if Cipher is None or not isinstance(packet, (bytes, bytearray)) or len(packet) < 64:
        return None
    try:
        encryptor = Cipher(algorithms.AES(bytes(packet[8:40])), modes.CTR(bytes(packet[40:56]))).encryptor()
        keystream = encryptor.update(b"\x00" * 64)
        tail = bytes(a ^ b for a, b in zip(packet[56:64], keystream[56:64]))
    except Exception:
        return None
    if tail[:4] not in _PROTO_TAGS:
        return None
    return int.from_bytes(tail[4:6], "little", signed=True)


def proto_from_init(packet: bytes) -> bytes:
    idx = dc_index_from_init(packet)
    if idx is None:
        return b"\xef\xef\xef\xef"
    encryptor = Cipher(algorithms.AES(bytes(packet[8:40])), modes.CTR(bytes(packet[40:56]))).encryptor()
    keystream = encryptor.update(b"\x00" * 64)
    return bytes(a ^ b for a, b in zip(packet[56:60], keystream[56:60]))


def ws_path(ws) -> str:
    if getattr(ws, "path", None):
        return ws.path
    req = getattr(ws, "request", None)
    if req is not None and getattr(req, "path", None):
        return req.path
    return "/"


def ws_headers(ws):
    headers = getattr(ws, "request_headers", None)
    if headers is not None:
        return headers
    req = getattr(ws, "request", None)
    if req is not None and getattr(req, "headers", None) is not None:
        return req.headers
    return {}


def _header_get(headers, *names):
    if headers is None:
        return ""
    wanted = {n.lower() for n in names}
    for name in names:
        try:
            if hasattr(headers, "get"):
                val = headers.get(name)
                if val:
                    return val if isinstance(val, str) else str(val)
        except Exception:
            pass
        try:
            val = headers[name]
            if val:
                return val if isinstance(val, str) else str(val)
        except Exception:
            pass
    try:
        items = headers.items() if hasattr(headers, "items") else []
        for k, v in items:
            if str(k).lower() in wanted and v:
                return v if isinstance(v, str) else str(v)
    except Exception:
        pass
    return ""


def token_ok(headers) -> bool:
    got = _header_get(headers, "X-Mgla-Token", "x-mgla-token")
    try:
        import mgla_token
        if mgla_token.token_ok(got):
            return True
    except Exception:
        pass
    if not AUTH_TOKEN:
        return True
    return got == AUTH_TOKEN


def client_ok(headers) -> bool:
    """Marker header from Mgla APK. Optional when token already validates."""
    got = _header_get(headers, "X-Mgla-Client", "x-mgla-client").strip().lower()
    return got == "mgla" or got.startswith("mgla/")


def parse_priority(headers) -> tuple:
    """Return client-claimed (plan_id, wants_ultra). Always re-verify via hub."""
    plan = _header_get(headers, "X-Mgla-Plan", "x-mgla-plan").strip().lower()
    prio = _header_get(headers, "X-Mgla-Priority", "x-mgla-priority").strip().lower()
    if plan not in ("free", "premium", "premium_plus", "premium_ultra"):
        plan = "free"
    wants_ultra = plan == "premium_ultra" or prio in ("1", "true", "yes", "ultra")
    if wants_ultra:
        plan = "premium_ultra"
    return plan, wants_ultra


def parse_tg_ids_from_headers(headers) -> list:
    raw = _header_get(
        headers,
        "X-Mgla-Tg-Ids",
        "x-mgla-tg-ids",
        "X-Mgla-Tg-Id",
        "x-mgla-tg-id",
    )
    out = []
    seen = set()
    for part in (raw or "").replace(";", ",").split(","):
        part = part.strip()
        if part.isdigit():
            tid = int(part)
            if tid > 0 and tid not in seen:
                seen.add(tid)
                out.append(tid)
    return out[:8]


_entitlements_cache: Dict[str, Tuple[float, str]] = {}
_ENTITLEMENTS_TTL = 45.0


async def fetch_hub_plan(tg_ids: list, client_token: str = "") -> str:
    """Best plan among tg_ids from mglahub. Fail-closed → free.

    Install-wide Ultra: hub returns the best plan across *all* claimed tg_ids.
    Prefer dedicated s2s entitlements/sync token. Do **not** attach the client's
    X-Mgla-Token when s2s is available — JWT ownership filters can strip the
    Ultra account and collapse the plan to free even when another account on
    the same install is Ultra.
    """
    if not tg_ids:
        return "free"
    client_token = (client_token or "").strip()
    if not HUB_ENTITLEMENTS_URL:
        LOG.warning("entitlements URL missing — treating as free")
        return "free"
    has_s2s = bool(HUB_ENTITLEMENTS_TOKEN)
    if not has_s2s and not client_token:
        LOG.warning("entitlements not configured — treating as free")
        return "free"
    import hashlib

    # Cache by tg_ids only when using s2s (install-wide plan).
    if has_s2s:
        cache_key = "s2s:" + ",".join(str(x) for x in sorted(tg_ids))
    else:
        cache_key = (
            hashlib.sha256(client_token.encode()).hexdigest()[:16]
            + ":"
            + ",".join(str(x) for x in sorted(tg_ids))
        )
    now = time.monotonic()
    hit = _entitlements_cache.get(cache_key)
    if hit and now - hit[0] < _ENTITLEMENTS_TTL:
        return hit[1]

    def _sync_fetch() -> str:
        import json
        import urllib.request

        body = json.dumps({"tg_ids": list(tg_ids)}).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "X-Mgla-Client": "mgla",
        }
        if has_s2s:
            headers["X-Mgla-Sync-Token"] = HUB_ENTITLEMENTS_TOKEN
            headers["X-Mgla-Entitlements-Token"] = HUB_ENTITLEMENTS_TOKEN
        elif client_token:
            # Fallback only when no s2s secret is configured on this node.
            headers["X-Mgla-Token"] = client_token
        req = urllib.request.Request(
            HUB_ENTITLEMENTS_URL,
            data=body,
            method="POST",
            headers=headers,
        )
        try:
            with urllib.request.urlopen(req, timeout=6) as resp:
                payload = json.loads(resp.read().decode("utf-8", errors="replace"))
        except Exception as e:
            LOG.warning("entitlements fetch failed: %s", e)
            return "free"
        plan = "free"
        if isinstance(payload, dict):
            p = payload.get("plan")
            if isinstance(p, str) and p in (
                "free",
                "premium",
                "premium_plus",
                "premium_ultra",
            ):
                plan = p
            plans = payload.get("plans")
            if isinstance(plans, dict) and plans:
                rank = {
                    "free": 0,
                    "premium": 1,
                    "premium_plus": 2,
                    "premium_ultra": 3,
                }
                best, best_r = "free", 0
                for v in plans.values():
                    r = rank.get(str(v), 0)
                    if r > best_r:
                        best, best_r = str(v), r
                plan = best
        return plan

    plan = await asyncio.to_thread(_sync_fetch)
    _entitlements_cache[cache_key] = (now, plan)
    LOG.info("entitlements tg_ids=%s plan=%s s2s=%s", tg_ids, plan, int(has_s2s))
    return plan


async def resolve_verified_plan(headers) -> tuple:
    """Return (plan, ultra) from hub — best plan across all client tg_ids."""
    claimed_plan, wants_ultra = parse_priority(headers)
    tg_ids = parse_tg_ids_from_headers(headers)
    if not tg_ids:
        # No account ids → cannot prove a paid plan.
        if wants_ultra or claimed_plan != "free":
            LOG.info("paid claim without tg ids (claimed=%s)", claimed_plan)
        return "free", False
    client_token = _header_get(headers, "X-Mgla-Token", "x-mgla-token")
    hub_plan = await fetch_hub_plan(tg_ids, client_token=client_token)
    ultra = hub_plan == "premium_ultra"
    if wants_ultra and not ultra:
        LOG.info(
            "ultra claim rejected hub_plan=%s tg_ids=%s",
            hub_plan,
            tg_ids,
        )
    return hub_plan, ultra


async def acquire_slot(ultra: bool) -> bool:
    """Reserve a soft connection slot. Ultra skips the standard cap."""
    global _active_standard, _active_ultra
    deadline = time.monotonic() + (0.0 if ultra else STANDARD_WAIT_SEC)
    while True:
        async with _get_slot_lock():
            standard_limit = max(1, SOFT_CAP - ULTRA_RESERVED)
            if ultra:
                _active_ultra += 1
                return True
            if _active_standard < standard_limit:
                _active_standard += 1
                return True
            if time.monotonic() >= deadline:
                return False
            ev = asyncio.Event()
            _standard_waiters.append(ev)
        try:
            remaining = max(0.05, deadline - time.monotonic())
            await asyncio.wait_for(ev.wait(), timeout=remaining)
        except asyncio.TimeoutError:
            async with _get_slot_lock():
                try:
                    _standard_waiters.remove(ev)
                except ValueError:
                    pass
            return False


async def release_slot(ultra: bool) -> None:
    global _active_standard, _active_ultra
    async with _get_slot_lock():
        if ultra:
            _active_ultra = max(0, _active_ultra - 1)
        else:
            _active_standard = max(0, _active_standard - 1)
        # Wake one waiting standard client when a slot frees.
        while _standard_waiters:
            ev = _standard_waiters.pop(0)
            ev.set()
            break


def _tune_socket(writer: asyncio.StreamWriter, ultra: bool) -> None:
    try:
        sock = writer.get_extra_info("socket")
        if sock is None:
            return
        import socket as _socket
        sock.setsockopt(_socket.IPPROTO_TCP, _socket.TCP_NODELAY, 1)
        buf = TCP_BUF_ULTRA if ultra else TCP_BUF
        try:
            sock.setsockopt(_socket.SOL_SOCKET, _socket.SO_RCVBUF, buf)
            sock.setsockopt(_socket.SOL_SOCKET, _socket.SO_SNDBUF, buf)
        except Exception:
            pass
        if ultra:
            try:
                # Best-effort Linux QoS hint (ignored on other platforms).
                sock.setsockopt(_socket.SOL_SOCKET, _socket.SO_PRIORITY, 6)
            except Exception:
                pass
    except Exception:
        pass


def mark_silent(dc: int, media: bool, ip: str) -> None:
    _silent_until[(dc, int(media), ip)] = time.monotonic() + 180
    LOG.warning("dc %s media=%s %s sent nothing, skipping 3 min", dc, int(media), ip)


def keys_no_secret(init: bytes) -> Tuple[bytes, bytes, bytes, bytes]:
    enc_key, enc_iv = init[8:40], init[40:56]
    dec_mat = init[8:56][::-1]
    return enc_key, enc_iv, dec_mat[:32], dec_mat[32:48]


def keys_with_secret(init: bytes, secret: bytes) -> Tuple[bytes, bytes, bytes, bytes]:
    enc_pre, enc_iv = init[8:40], init[40:56]
    enc_key = hashlib.sha256(enc_pre + secret).digest()
    dec_mat = init[8:56][::-1]
    dec_key = hashlib.sha256(dec_mat[:32] + secret).digest()
    return enc_key, enc_iv, dec_key, dec_mat[32:48]


def make_mtproxy_init(proto_tag: bytes, dc_idx: int, secret: bytes) -> bytes:
    """Obfuscated2 handshake for official MTProxy (-S secret)."""
    while True:
        rnd = bytearray(secrets.token_bytes(64))
        if rnd[0] in _RESERVED_FIRST:
            continue
        if bytes(rnd[:4]) in _RESERVED_STARTS:
            continue
        if rnd[4:8] == b"\x00\x00\x00\x00":
            continue
        break
    enc_pre, enc_iv = bytes(rnd[8:40]), bytes(rnd[40:56])
    enc_key = hashlib.sha256(enc_pre + secret).digest()
    encryptor = Cipher(algorithms.AES(enc_key), modes.CTR(enc_iv)).encryptor()
    encrypted = encryptor.update(bytes(rnd))
    keystream_tail = bytes(a ^ b for a, b in zip(encrypted[56:64], rnd[56:64]))
    tail = bytearray(8)
    tail[:4] = proto_tag[:4] if proto_tag and len(proto_tag) >= 4 else b"\xef\xef\xef\xef"
    tail[4:6] = int(dc_idx).to_bytes(2, "little", signed=True)
    tail[6:8] = secrets.token_bytes(2)
    out = bytearray(rnd)
    for i in range(8):
        out[56 + i] = tail[i] ^ keystream_tail[i]
    return bytes(out)


async def open_dc(dc: int, media: bool, ultra: bool = False):
    ips = list((DC_MEDIA if media else DC_MAIN).get(dc) or DC_MAIN[2])
    now = time.monotonic()
    fresh = [ip for ip in ips if _silent_until.get((dc, int(media), ip), 0) <= now]
    candidates = fresh or ips
    timeout = CONNECT_TIMEOUT_ULTRA if ultra else CONNECT_TIMEOUT

    if ultra and len(candidates) > 1:
        # Race all DC IPs — first successful wins (max speed / priority connect).
        return await _open_dc_race(dc, media, candidates, timeout)

    last = None
    for ip in candidates:
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(ip, 443), timeout=timeout)
            _tune_socket(writer, ultra)
            return reader, writer, ip
        except Exception as e:
            last = e
            LOG.warning("dc %s media=%s %s failed: %s", dc, int(media), ip, e)
    raise last if last is not None else OSError("no dc address")


async def _open_dc_race(dc: int, media: bool, ips: list, timeout: float):
    tasks = [asyncio.create_task(_try_dc_ip(ip, timeout)) for ip in ips]
    last = None
    try:
        while tasks:
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            tasks = list(pending)
            for t in done:
                try:
                    reader, writer, ip = t.result()
                    _tune_socket(writer, True)
                    for p in pending:
                        p.cancel()
                        try:
                            await p
                        except Exception:
                            pass
                    # Close any other successes that finished in the same batch.
                    for other in done:
                        if other is t:
                            continue
                        try:
                            _r, w, _ip = other.result()
                            w.close()
                        except Exception:
                            pass
                    LOG.info("dc %s media=%s ultra race won via %s", dc, int(media), ip)
                    return reader, writer, ip
                except Exception as e:
                    last = e
    finally:
        for t in tasks:
            if not t.done():
                t.cancel()
    raise last if last is not None else OSError("no dc address")


async def _try_dc_ip(ip: str, timeout: float):
    reader, writer = await asyncio.wait_for(asyncio.open_connection(ip, 443), timeout=timeout)
    return reader, writer, ip


async def open_mtproxy(ultra: bool = False):
    timeout = CONNECT_TIMEOUT_ULTRA if ultra else CONNECT_TIMEOUT
    reader, writer = await asyncio.wait_for(
        asyncio.open_connection(MTPROXY_HOST, MTPROXY_PORT), timeout=timeout)
    _tune_socket(writer, ultra)
    return reader, writer


async def _write_tcp(
    writer: asyncio.StreamWriter,
    data: bytes,
    ultra: bool,
    *,
    force_drain: bool = False,
) -> None:
    """Write to Telegram/MTProxy. force_drain=True for media uploads / RPC paths."""
    writer.write(data)
    if force_drain or not ultra:
        await writer.drain()
        return
    watermark = TCP_DRAIN_WATERMARK_ULTRA
    transport = writer.transport
    pending = 0
    try:
        pending = transport.get_write_buffer_size() if transport is not None else 0
    except Exception:
        pending = 0
    if pending >= watermark:
        await writer.drain()


async def pipe_ws_to_tcp(ws, writer: asyncio.StreamWriter, ultra: bool = False) -> None:
    # Main DC is RPC-heavy (sendMessage / sendMedia). Always drain so acks flow.
    try:
        async for message in ws:
            data = message.encode("utf-8") if isinstance(message, str) else message
            if not data:
                continue
            await _write_tcp(writer, data, ultra, force_drain=True)
        try:
            await writer.drain()
        except Exception:
            pass
    except Exception as e:
        LOG.debug("ws->tcp end: %s", e)
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def pipe_tcp_to_ws(reader: asyncio.StreamReader, ws, received: list, ultra: bool = False) -> None:
    buf = TCP_BUF_ULTRA if ultra else TCP_BUF
    try:
        while True:
            data = await reader.read(buf)
            if not data:
                break
            received[0] += len(data)
            await ws.send(data)
    except Exception as e:
        LOG.debug("tcp->ws end: %s", e)
    finally:
        try:
            await ws.close()
        except Exception:
            pass


async def bridge_via_mtproxy(ws, first: bytes, dc: int, media: bool, peer: str, ultra: bool = False) -> bool:
    """Re-encrypt the telegram-facing stream onto local MTProxy. Returns False to fall back."""
    if Cipher is None:
        LOG.warning("%s mtproxy skip: cryptography not installed", peer)
        return False
    if len(MTPROXY_SECRET) != 16:
        LOG.warning("%s mtproxy skip: secret missing/invalid (len=%s)", peer, len(MTPROXY_SECRET))
        return False
    if len(first) < 64:
        LOG.warning("%s mtproxy skip: init packet too short (%s)", peer, len(first))
        return False
    sniffed = dc_index_from_init(first)
    if sniffed is None:
        sniffed = -dc if media else dc
    proto = proto_from_init(first)
    try:
        reader, writer = await open_mtproxy(ultra=ultra)
    except Exception as e:
        LOG.warning("%s mtproxy dial failed: %s", peer, e)
        return False

    c_enc_key, c_enc_iv, c_dec_key, c_dec_iv = keys_no_secret(first)
    # Wire after the first packet is encrypted with the no-secret encode key (skip 64).
    ws_to_plain = AesCtr(c_enc_key, c_enc_iv, skip=64)
    plain_to_ws = AesCtr(c_dec_key, c_dec_iv, skip=0)

    mt_init = make_mtproxy_init(proto, sniffed, MTPROXY_SECRET)
    m_enc_key, m_enc_iv, m_dec_key, m_dec_iv = keys_with_secret(mt_init, MTPROXY_SECRET)
    plain_to_mt = AesCtr(m_enc_key, m_enc_iv, skip=64)
    mt_to_plain = AesCtr(m_dec_key, m_dec_iv, skip=0)

    try:
        writer.write(mt_init)
        await writer.drain()
    except Exception as e:
        LOG.warning("%s mtproxy init failed: %s", peer, e)
        try:
            writer.close()
        except Exception:
            pass
        return False

    LOG.info("%s connected dc=%s media=%s via mtproxy:%s dcIdx=%s",
             peer, dc, int(media), MTPROXY_PORT, sniffed)
    received = [0]

    async def up():
        # Always drain: media uploads are request/response. Coalesced writes
        # leave the last FileSavePart chunk buffered and the send never finishes.
        try:
            async for message in ws:
                data = message.encode("utf-8") if isinstance(message, str) else message
                if not data:
                    continue
                plain = ws_to_plain.update(data)
                out = plain_to_mt.update(plain)
                await _write_tcp(writer, out, ultra, force_drain=True)
            try:
                await writer.drain()
            except Exception:
                pass
        except Exception as e:
            LOG.debug("%s mtproxy up end: %s", peer, e)
        finally:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

    async def down():
        buf = TCP_BUF_ULTRA if ultra else TCP_BUF
        try:
            while True:
                data = await reader.read(buf)
                if not data:
                    break
                plain = mt_to_plain.update(data)
                out = plain_to_ws.update(plain)
                received[0] += len(out)
                await ws.send(out)
        except Exception as e:
            LOG.debug("%s mtproxy down end: %s", peer, e)
        finally:
            try:
                await ws.close()
            except Exception:
                pass

    up_t = asyncio.create_task(up())
    down_t = asyncio.create_task(down())
    done, pending = await asyncio.wait({up_t, down_t}, return_when=asyncio.FIRST_COMPLETED)
    for t in pending:
        t.cancel()
    LOG.info("%s closed dc=%s media=%s via mtproxy bytes=%s", peer, dc, int(media), received[0])
    return True


async def race_kws(dc: int, media: bool):
    hosts = [
        f"kws{dc}-1.web.telegram.org",
        f"kws{dc}.web.telegram.org",
    ]
    if dc not in (2, 203):
        hosts.append("kws4-1.web.telegram.org" if dc == 4 else hosts[0])

    async def one(host: str):
        ws = await websockets.connect(
            f"wss://{host}/apiws",
            subprotocols=["binary"],
            origin="https://web.telegram.org",
            open_timeout=6,
            ping_interval=WS_PING_INTERVAL,
            ping_timeout=WS_PING_TIMEOUT,
            max_size=MAX_WS_SIZE,
            compression=None,
        )
        return ws, host

    tasks = [asyncio.create_task(one(h)) for h in list(dict.fromkeys(hosts))]
    try:
        while tasks:
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for t in done:
                tasks = list(pending)
                try:
                    result = t.result()
                except Exception as e:
                    LOG.debug("kws attempt failed: %s", e)
                    continue
                for p in pending:
                    p.cancel()
                return result
    finally:
        for t in tasks:
            t.cancel()
    raise OSError("all kws failed")


async def pipe_ws(src, dst, received: Optional[list] = None) -> None:
    try:
        async for message in src:
            data = message.encode("utf-8") if isinstance(message, str) else message
            if not data:
                continue
            if received is not None:
                received[0] += len(data)
            await dst.send(data)
    except Exception as e:
        LOG.debug("ws pipe end: %s", e)
    finally:
        try:
            await dst.close()
        except Exception:
            pass


async def handler(ws):
    path = ws_path(ws)
    peer = str(getattr(ws, "remote_address", "?"))
    headers = ws_headers(ws)

    if not token_ok(headers):
        LOG.info("%s reject auth path=%s", peer, path)
        await ws.close(code=1008, reason="unauthorized")
        return
    if not client_ok(headers):
        # Token already proved access; do not drop the call if a proxy stripped the marker.
        LOG.warning("%s missing X-Mgla-Client path=%s (allowing valid token)", peer, path)

    plan, ultra = await resolve_verified_plan(headers)
    if ULTRA_ONLY and not ultra:
        LOG.info("%s reject ultra-only plan=%s path=%s", peer, plan, path)
        await ws.close(code=1008, reason="ultra only")
        return
    if not parse_tg_ids_from_headers(headers) and (ultra or ULTRA_ONLY):
        LOG.info("%s reject missing X-Mgla-Tg-Id for premium path", peer)
        await ws.close(code=1008, reason="tg id required")
        return

    dc = resolve_dc(path)
    if dc is None:
        LOG.info("%s bad path=%s (need /apiws?dc=1..5)", peer, path)
        await ws.close(code=1008, reason="need /apiws?dc=N")
        return

    if not await acquire_slot(ultra):
        LOG.info("%s reject busy plan=%s path=%s (std=%s ultra=%s)",
                 peer, plan, path, _active_standard, _active_ultra)
        await ws.close(code=1013, reason="busy")
        return

    try:
        media = want_media(path)
        try:
            first = await asyncio.wait_for(ws.recv(), timeout=10)
        except Exception as e:
            LOG.info("%s no init: %s", peer, e)
            await ws.close(code=1000)
            return
        if isinstance(first, str):
            first = first.encode("utf-8", "ignore")
        sniffed = dc_index_from_init(first)
        if sniffed is not None:
            media = sniffed < 0
            # Prefer the dc encoded in the handshake when it disagrees with the query.
            abs_dc = abs(sniffed)
            if abs_dc == 203 or 1 <= abs_dc <= 5:
                dc = abs_dc

        LOG.info("%s open dc=%s media=%s plan=%s ultra=%s", peer, dc, int(media), plan, int(ultra))

        # Prefer local MTProxy middle-end:
        # - media=1 (downloads / GenericMedia)
        # - Ultra media=0 too: voice/circles/uploads use ConnectionTypeUpload which
        #   is NOT flagged as media in tgnet, so they hit this path. Raw DC IP
        #   races from the hub are flaky; MTProxy handles proxy_for ±dc.
        prefer_mtproxy = media or ultra
        if prefer_mtproxy:
            if await bridge_via_mtproxy(ws, first, dc, media, peer, ultra=ultra):
                return
            if media:
                # Fallback: race kws hosts, then raw IP.
                try:
                    upstream, host = await race_kws(dc, True)
                    LOG.info("%s connected dc=%s media=1 via %s", peer, dc, host)
                    await upstream.send(first)
                    received = [0]
                    up = asyncio.create_task(pipe_ws(ws, upstream))
                    down = asyncio.create_task(pipe_ws(upstream, ws, received))
                    done, pending = await asyncio.wait(
                        {up, down}, return_when=asyncio.FIRST_COMPLETED
                    )
                    for t in pending:
                        t.cancel()
                    LOG.info(
                        "%s closed dc=%s media=1 via %s bytes=%s",
                        peer, dc, host, received[0],
                    )
                    return
                except Exception as e:
                    LOG.warning("%s kws media failed: %s", peer, e)
            else:
                LOG.warning(
                    "%s ultra mtproxy failed for media=0 — falling back to raw DC",
                    peer,
                )

        try:
            reader, writer, ip = await open_dc(dc, media, ultra=ultra)
        except Exception as e:
            LOG.warning("%s dc connect failed: %s", peer, e)
            await ws.close(code=1011, reason="dc unreachable")
            return
        LOG.info("%s connected dc=%s media=%s via %s plan=%s", peer, dc, int(media), ip, plan)
        try:
            writer.write(first)
            await writer.drain()
        except Exception as e:
            LOG.warning("%s init forward failed: %s", peer, e)
            try:
                writer.close()
            except Exception:
                pass
            await ws.close(code=1011)
            return

        received = [0]
        started = time.monotonic()
        up = asyncio.create_task(pipe_ws_to_tcp(ws, writer, ultra=ultra))
        down = asyncio.create_task(pipe_tcp_to_ws(reader, ws, received, ultra=ultra))
        done, pending = await asyncio.wait({up, down}, return_when=asyncio.FIRST_COMPLETED)
        for t in pending:
            t.cancel()
        if received[0] == 0 and time.monotonic() - started >= 8:
            mark_silent(dc, media, ip)
        LOG.info("%s closed dc=%s media=%s bytes=%s plan=%s", peer, dc, int(media), received[0], plan)
    finally:
        await release_slot(ultra)


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if AUTH_TOKEN:
        LOG.info("auth token enabled (%d chars)", len(AUTH_TOKEN))
    else:
        LOG.warning("MGLA_WS_TOKEN empty ??? anyone can use this relay")
    if len(MTPROXY_SECRET) == 16:
        LOG.info("media via mtproxy %s:%s", MTPROXY_HOST, MTPROXY_PORT)
    else:
        LOG.warning("MGLA_WEB_PROXY_SECRET missing — media falls back to kws/raw")
    LOG.info(
        "qos soft_cap=%s ultra_reserved=%s standard_wait=%.1fs ultra_only=%s "
        "(X-Mgla-Plan / X-Mgla-Priority)",
        SOFT_CAP,
        ULTRA_RESERVED,
        STANDARD_WAIT_SEC,
        int(ULTRA_ONLY),
    )

    # Larger inbound queue on Ultra-only hub so media bursts don't stall the bridge.
    ws_queue = 256 if ULTRA_ONLY else 96
    async with websockets.serve(
        handler,
        LISTEN_HOST,
        LISTEN_PORT,
        subprotocols=["binary"],
        max_size=MAX_WS_SIZE,
        ping_interval=WS_PING_INTERVAL,
        ping_timeout=WS_PING_TIMEOUT,
        compression=None,
        max_queue=ws_queue,
    ):
        LOG.info("listening on %s:%s", LISTEN_HOST, LISTEN_PORT)
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
