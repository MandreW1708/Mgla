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
CONNECT_TIMEOUT = 4.0
TCP_BUF = 512 * 1024
MAX_WS_SIZE = 32 * 1024 * 1024
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


def token_ok(headers) -> bool:
    try:
        got = headers.get("X-Mgla-Token") or headers.get("x-mgla-token") or ""
    except Exception:
        got = ""
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
    """Только клиент Mgla шлёт X-Mgla-Client: mgla[...]. Stock Telegram — нет."""
    try:
        import mgla_token
        return mgla_token.client_header_ok(headers)
    except Exception:
        pass
    try:
        got = headers.get("X-Mgla-Client") or headers.get("x-mgla-client") or ""
    except Exception:
        got = ""
    got = (got or "").strip().lower()
    return got == "mgla" or got.startswith("mgla/")


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


async def open_dc(dc: int, media: bool):
    ips = list((DC_MEDIA if media else DC_MAIN).get(dc) or DC_MAIN[2])
    now = time.monotonic()
    fresh = [ip for ip in ips if _silent_until.get((dc, int(media), ip), 0) <= now]
    last = None
    for ip in fresh or ips:
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(ip, 443), timeout=CONNECT_TIMEOUT)
            try:
                sock = writer.get_extra_info("socket")
                if sock is not None:
                    sock.setsockopt(__import__("socket").IPPROTO_TCP, __import__("socket").TCP_NODELAY, 1)
            except Exception:
                pass
            return reader, writer, ip
        except Exception as e:
            last = e
            LOG.warning("dc %s media=%s %s failed: %s", dc, int(media), ip, e)
    raise last if last is not None else OSError("no dc address")


async def open_mtproxy():
    reader, writer = await asyncio.wait_for(
        asyncio.open_connection(MTPROXY_HOST, MTPROXY_PORT), timeout=CONNECT_TIMEOUT)
    try:
        sock = writer.get_extra_info("socket")
        if sock is not None:
            sock.setsockopt(__import__("socket").IPPROTO_TCP, __import__("socket").TCP_NODELAY, 1)
    except Exception:
        pass
    return reader, writer


async def pipe_ws_to_tcp(ws, writer: asyncio.StreamWriter) -> None:
    try:
        async for message in ws:
            data = message.encode("utf-8") if isinstance(message, str) else message
            if not data:
                continue
            writer.write(data)
            await writer.drain()
    except Exception as e:
        LOG.debug("ws->tcp end: %s", e)
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def pipe_tcp_to_ws(reader: asyncio.StreamReader, ws, received: list) -> None:
    try:
        while True:
            data = await reader.read(TCP_BUF)
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


async def bridge_via_mtproxy(ws, first: bytes, dc: int, media: bool, peer: str) -> bool:
    """Re-encrypt the telegram-facing stream onto local MTProxy. Returns False to fall back."""
    if Cipher is None or len(MTPROXY_SECRET) != 16 or len(first) < 64:
        return False
    sniffed = dc_index_from_init(first)
    if sniffed is None:
        sniffed = -dc if media else dc
    proto = proto_from_init(first)
    try:
        reader, writer = await open_mtproxy()
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
        try:
            async for message in ws:
                data = message.encode("utf-8") if isinstance(message, str) else message
                if not data:
                    continue
                plain = ws_to_plain.update(data)
                out = plain_to_mt.update(plain)
                writer.write(out)
                await writer.drain()
        except Exception as e:
            LOG.debug("%s mtproxy up end: %s", peer, e)
        finally:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

    async def down():
        try:
            while True:
                data = await reader.read(TCP_BUF)
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
            open_timeout=5,
            ping_interval=20,
            ping_timeout=20,
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
        LOG.info("%s reject client path=%s", peer, path)
        await ws.close(code=1008, reason="client")
        return

    dc = resolve_dc(path)
    if dc is None:
        LOG.info("%s bad path=%s (need /apiws?dc=1..5)", peer, path)
        await ws.close(code=1008, reason="need /apiws?dc=N")
        return

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

    LOG.info("%s open dc=%s media=%s", peer, dc, int(media))

    # Media: official MTProxy middle-end routing (strong path).
    if media:
        if await bridge_via_mtproxy(ws, first, dc, True, peer):
            return
        # Fallback: race kws hosts, then raw IP.
        try:
            upstream, host = await race_kws(dc, True)
            LOG.info("%s connected dc=%s media=1 via %s", peer, dc, host)
            await upstream.send(first)
            received = [0]
            up = asyncio.create_task(pipe_ws(ws, upstream))
            down = asyncio.create_task(pipe_ws(upstream, ws, received))
            done, pending = await asyncio.wait({up, down}, return_when=asyncio.FIRST_COMPLETED)
            for t in pending:
                t.cancel()
            LOG.info("%s closed dc=%s media=1 via %s bytes=%s", peer, dc, host, received[0])
            return
        except Exception as e:
            LOG.warning("%s kws media failed: %s", peer, e)

    try:
        reader, writer, ip = await open_dc(dc, media)
    except Exception as e:
        LOG.warning("%s dc connect failed: %s", peer, e)
        await ws.close(code=1011, reason="dc unreachable")
        return
    LOG.info("%s connected dc=%s media=%s via %s", peer, dc, int(media), ip)
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
    up = asyncio.create_task(pipe_ws_to_tcp(ws, writer))
    down = asyncio.create_task(pipe_tcp_to_ws(reader, ws, received))
    done, pending = await asyncio.wait({up, down}, return_when=asyncio.FIRST_COMPLETED)
    for t in pending:
        t.cancel()
    if received[0] == 0 and time.monotonic() - started >= 8:
        mark_silent(dc, media, ip)
    LOG.info("%s closed dc=%s media=%s bytes=%s", peer, dc, int(media), received[0])


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if AUTH_TOKEN:
        LOG.info("auth token enabled (%d chars)", len(AUTH_TOKEN))
    else:
        LOG.warning("MGLA_WS_TOKEN empty ??? anyone can use this relay")
    if len(MTPROXY_SECRET) == 16:
        LOG.info("media via mtproxy %s:%s", MTPROXY_HOST, MTPROXY_PORT)
    else:
        LOG.warning("MGLA_WEB_PROXY_SECRET missing ??? media falls back to kws/raw")

    async with websockets.serve(
        handler,
        LISTEN_HOST,
        LISTEN_PORT,
        subprotocols=["binary"],
        max_size=MAX_WS_SIZE,
        ping_interval=20,
        ping_timeout=20,
        compression=None,
        max_queue=64,
    ):
        LOG.info("listening on %s:%s", LISTEN_HOST, LISTEN_PORT)
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
