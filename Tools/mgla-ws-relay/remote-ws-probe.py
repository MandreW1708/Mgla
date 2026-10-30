#!/usr/bin/env python3
import asyncio
import json
import ssl
import sys

from websockets.legacy.client import connect

token = sys.argv[1] if len(sys.argv) > 1 else ""
primary = sys.argv[2] if len(sys.argv) > 2 else "localhost"


async def probe_direct(path: str, meta: bool) -> None:
    headers = [
        ("Authorization", "Bearer " + token),
        ("X-Mgla-Token", token),
        ("X-Mgla-Client", "mgla"),
    ]
    try:
        async with connect(
            f"ws://127.0.0.1:8766{path}",
            extra_headers=headers,
            subprotocols=["binary"],
            open_timeout=8,
        ) as ws:
            if meta:
                await ws.send(json.dumps({"v": 1, "c": "mgla", "t": token, "p": "free"}))
            print(path, "direct_ok")
            await ws.close()
    except Exception as e:
        print(path, "direct_fail", type(e).__name__, e)


async def probe_nginx(path: str, meta: bool, sni: str) -> None:
    ctx = ssl.create_default_context()
    headers = [
        ("Host", sni),
        ("Authorization", "Bearer " + token),
        ("X-Mgla-Token", token),
        ("X-Mgla-Client", "mgla"),
    ]
    try:
        async with connect(
            f"wss://127.0.0.1{path}",
            ssl=ctx,
            extra_headers=headers,
            server_hostname=sni if sni == primary else primary,
            subprotocols=["binary"],
            open_timeout=8,
        ) as ws:
            if meta:
                await ws.send(json.dumps({"v": 1, "c": "mgla", "t": token, "p": "free"}))
            print(path, "nginx_ok", "sni=" + sni)
            await ws.close()
    except Exception as e:
        # Retry pin-style: verify against primary hostname for alias SNI without SAN.
        if sni != primary:
            try:
                async with connect(
                    f"wss://127.0.0.1{path}",
                    ssl=ctx,
                    extra_headers=headers,
                    server_hostname=primary,
                    subprotocols=["binary"],
                    open_timeout=8,
                ) as ws:
                    if meta:
                        await ws.send(json.dumps({"v": 1, "c": "mgla", "t": token, "p": "free"}))
                    print(path, "nginx_ok_pinstyle", "sni_hdr=" + sni)
                    await ws.close()
                    return
            except Exception as e2:
                print(path, "nginx_fail", type(e).__name__, e, "| pinstyle", e2)
                return
        print(path, "nginx_fail", type(e).__name__, e)


async def main() -> None:
    print("primary", primary, "token_len", len(token))
    await probe_direct("/apiws?dc=2", False)
    await probe_direct("/ws/v1/live?dc=2", True)
    await probe_nginx("/apiws?dc=2", False, primary)
    await probe_nginx("/ws/v1/live?dc=2", True, primary)


if __name__ == "__main__":
    asyncio.run(main())
