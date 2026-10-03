#!/usr/bin/env python3
"""
Mgla WS relay: WebSocket /apiws?dc=N  →  TCP Telegram DC:443

  pip3 install 'websockets>=12,<16'
  MGLA_WS_TOKEN='...' python3 relay.py

Слушает 127.0.0.1:8766 (перед nginx с TLS на mglabot.mooo.com).
"""

from __future__ import annotations

import asyncio
import logging
import os
from typing import Dict, Optional
from urllib.parse import parse_qs, urlparse

import websockets

LOG = logging.getLogger("mgla-ws-relay")

LISTEN_HOST = os.environ.get("MGLA_WS_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("MGLA_WS_PORT", "8766"))
AUTH_TOKEN = os.environ.get("MGLA_WS_TOKEN", "").strip()

DC_IP: Dict[int, str] = {
    1: "149.154.175.50",
    2: "149.154.167.51",
    3: "149.154.175.100",
    4: "149.154.167.91",
    5: "149.154.171.5",
    203: "91.105.192.100",
}

CONNECT_TIMEOUT = 10.0
TCP_BUF = 256 * 1024


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
    if not AUTH_TOKEN:
        return True
    try:
        got = headers.get("X-Mgla-Token") or headers.get("x-mgla-token") or ""
    except Exception:
        got = ""
    return got == AUTH_TOKEN


async def open_dc(dc: int):
    ip = DC_IP.get(dc) or DC_IP[2]
    return await asyncio.wait_for(asyncio.open_connection(ip, 443), timeout=CONNECT_TIMEOUT)


async def pipe_ws_to_tcp(ws, writer: asyncio.StreamWriter, peer: str) -> None:
    try:
        async for message in ws:
            data = message.encode("utf-8") if isinstance(message, str) else message
            if not data:
                continue
            writer.write(data)
            await writer.drain()
    except Exception as e:
        LOG.debug("%s ws->tcp end: %s", peer, e)
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def pipe_tcp_to_ws(reader: asyncio.StreamReader, ws, peer: str) -> None:
    try:
        while True:
            data = await reader.read(TCP_BUF)
            if not data:
                break
            await ws.send(data)
    except Exception as e:
        LOG.debug("%s tcp->ws end: %s", peer, e)
    finally:
        try:
            await ws.close()
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

    dc = resolve_dc(path)
    if dc is None:
        LOG.info("%s bad path=%s (need /apiws?dc=1..5)", peer, path)
        await ws.close(code=1008, reason="need /apiws?dc=N")
        return

    LOG.info("%s open dc=%s (%s)", peer, dc, DC_IP.get(dc))
    try:
        reader, writer = await open_dc(dc)
    except Exception as e:
        LOG.warning("%s dc connect failed: %s", peer, e)
        await ws.close(code=1011, reason="dc unreachable")
        return

    up = asyncio.create_task(pipe_ws_to_tcp(ws, writer, peer))
    down = asyncio.create_task(pipe_tcp_to_ws(reader, ws, peer))
    done, pending = await asyncio.wait({up, down}, return_when=asyncio.FIRST_COMPLETED)
    for t in pending:
        t.cancel()
    LOG.info("%s closed dc=%s", peer, dc)


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if AUTH_TOKEN:
        LOG.info("auth token enabled (%d chars)", len(AUTH_TOKEN))
    else:
        LOG.warning("MGLA_WS_TOKEN empty — anyone can use this relay")

    async with websockets.serve(
        handler,
        LISTEN_HOST,
        LISTEN_PORT,
        subprotocols=["binary"],
        max_size=16 * 1024 * 1024,
        ping_interval=20,
        ping_timeout=20,
        compression=None,
    ):
        LOG.info("listening on %s:%s", LISTEN_HOST, LISTEN_PORT)
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
