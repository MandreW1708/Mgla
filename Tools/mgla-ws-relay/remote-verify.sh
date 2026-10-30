#!/usr/bin/env bash
set -euo pipefail
HOST_LABEL="${1:-host}"
TOKEN="${2:-}"
echo "=== verify $HOST_LABEL ==="
systemctl is-active mgla-ws-relay nginx mgla-tproxy mgla-decoy 2>/dev/null || true
mkdir -p /var/www/mgla-decoy/tgweb
if [[ -f /var/www/mgla-decoy/index.html ]]; then
  cp -f /var/www/mgla-decoy/index.html /var/www/mgla-decoy/tgweb/index.html
fi
systemctl restart mgla-decoy 2>/dev/null || true
sleep 1
curl -sS -m 3 -w "decoy_root=%{http_code}\n" -o /dev/null http://127.0.0.1:8088/ || true
curl -sS -m 3 -w "decoy_tgweb=%{http_code}\n" -o /dev/null http://127.0.0.1:8088/tgweb/ || true

# Detect public hostname from nginx
PRIMARY=$(grep -RhoE 'server_name[[:space:]]+[^;]+' /etc/nginx/sites-enabled 2>/dev/null \
  | tr ' ' '\n' | grep -E 'mgla|sslip' | head -1 || true)
PRIMARY=${PRIMARY:-localhost}
curl -sS -m 5 -w "tgweb_nginx=%{http_code}\n" -o /tmp/tgweb_body.html \
  "https://127.0.0.1/tgweb/" -H "Host: $PRIMARY" --insecure || true
head -c 120 /tmp/tgweb_body.html; echo

grep -n "ws/v1/live\|location /apiws" /etc/nginx/sites-enabled/* 2>/dev/null | head -20 || true

python3 - <<PY
import asyncio, ssl, sys
token = """$TOKEN"""
primary = """$PRIMARY"""
try:
    import websockets
except Exception as e:
    print("skip_ws", e)
    sys.exit(0)

async def probe(path):
    ctx = ssl.create_default_context()
    uri = f"wss://127.0.0.1{path}?dc=2"
    headers = {"Host": primary}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        async with websockets.connect(
            uri, ssl=ctx, additional_headers=headers,
            server_hostname=primary, subprotocols=["binary"], open_timeout=8,
        ) as ws:
            if path.startswith("/ws/"):
                import json
                await ws.send(json.dumps({"v": 1, "c": "mgla", "t": token, "p": "free"}))
            print(path, "open_ok")
            await ws.close()
    except Exception as e:
        print(path, "fail", type(e).__name__, e)

async def main():
    await probe("/ws/v1/live")
    await probe("/apiws")
asyncio.run(main())
PY
