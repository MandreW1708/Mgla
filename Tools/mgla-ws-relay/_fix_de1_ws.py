#!/usr/bin/env python3
"""Diagnose/fix DE1: enable ws access log, check relay auth, confirm traffic reaches 8766."""
from __future__ import annotations

import subprocess
from pathlib import Path

PLINK = r"C:\\Program Files\\PuTTY\\plink.exe"
PSCP = r"C:\\Program Files\\PuTTY\\pscp.exe"
IP = "31.77.218.160"
PW = "23Ej12ttgG6Y"
LOCAL_RELAY = Path(r"C:\Glass2\Tools\mgla-ws-relay\relay.py")

REMOTE = r"""
set -e
echo '=== before ==='
ss -tn state established '( sport = :8766 )' | wc -l || true
journalctl -u mgla-ws-relay --since '5 min ago' --no-pager | grep -cE 'open dc=|connection open|rejected|unauthorized' || true

python3 <<'PY'
from pathlib import Path
p = Path('/etc/nginx/sites-enabled/mglabot')
t = p.read_text(encoding='utf-8', errors='ignore')
if 'ws-live.log' not in t:
    old = '    location /ws/v1/live {\n'
    if old in t:
        t = t.replace(old, '    location /ws/v1/live {\n        access_log /var/log/nginx/ws-live.log;\n', 1)
        p.write_text(t, encoding='utf-8')
        print('enabled ws-live access_log')
    else:
        print('WARN: ws/v1/live block not found')
else:
    print('ws-live access_log already on')
PY

# Ensure relay matches local copy if uploaded to /tmp
if [ -f /tmp/relay.py ]; then
  if ! cmp -s /tmp/relay.py /opt/mgla-ws-relay/relay.py; then
    echo 'replacing relay.py from upload'
    cp -a /opt/mgla-ws-relay/relay.py /opt/mgla-ws-relay/relay.py.bak.$(date +%Y%m%d%H%M%S)
    cp /tmp/relay.py /opt/mgla-ws-relay/relay.py
    # strip CRLF
    sed -i 's/\r$//' /opt/mgla-ws-relay/relay.py
    systemctl restart mgla-ws-relay
    sleep 1
  else
    echo 'relay.py identical to upload'
  fi
fi

nginx -t
systemctl reload nginx
systemctl is-active mgla-ws-relay nginx mgla-mtproxy

echo '=== probe ws/v1/live ==='
curl -sS -m 8 -D - -o /dev/null --http1.1 \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  -H 'Sec-WebSocket-Protocol: binary' \
  -H 'Authorization: Bearer '"$(grep '^MGLA_WS_TOKEN=' /opt/mgla-ws-relay/env | cut -d= -f2-)" \
  -H 'X-Mgla-Client: mgla' \
  https://127.0.0.1/ws/v1/live?dc=2 -H 'Host: mglabot.mooo.com' -k 2>&1 | head -20

sleep 2
echo '=== ws-live.log ==='
tail -n 10 /var/log/nginx/ws-live.log 2>/dev/null || echo '(no log yet)'
echo '=== relay journal last ==='
journalctl -u mgla-ws-relay -n 15 --no-pager
echo '=== estab 8766 ==='
ss -tn state established '( sport = :8766 )' | wc -l
"""


def main() -> None:
    # Upload fresh relay.py
    subprocess.run(
        [PSCP, "-batch", "-pw", PW, str(LOCAL_RELAY), f"root@{IP}:/tmp/relay.py"],
        check=False,
    )
    r = subprocess.run(
        [PLINK, "-ssh", "-batch", "-pw", PW, f"root@{IP}", REMOTE],
        capture_output=True,
    )
    print((r.stdout or b"").decode("utf-8", "replace"))
    err = (r.stderr or b"").decode("utf-8", "replace")
    if err.strip():
        print("STDERR:", err)
    raise SystemExit(r.returncode)


if __name__ == "__main__":
    main()
