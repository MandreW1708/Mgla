#!/bin/bash
set -e
echo "=== who owns 8090/8088 ==="
ss -lntp | grep -E '8090|8088' || true
echo "=== nginx refs ==="
grep -rn '8088\|tgweb\|8090\|decoy' /etc/nginx/sites-enabled/ /etc/nginx/sites-available/ 2>/dev/null || true
echo "=== tproxy unit ==="
systemctl cat mgla-tproxy --no-pager | head -50
echo "=== tproxy process ==="
ps aux | grep tproxy | grep -v grep || true
TPID=$(pgrep -n tproxy-server || true)
if [ -n "$TPID" ]; then
  echo "pid=$TPID cwd=$(readlink -f /proc/$TPID/cwd)"
  ls -la "$(readlink -f /proc/$TPID/cwd)" | head
  tr '\0' '\n' < /proc/$TPID/cmdline; echo
fi
echo "=== profiles ==="
find /opt /etc /home -name 'profiles.json' 2>/dev/null | head
echo "=== bodies ==="
echo -n "8088_root "; curl -sS http://127.0.0.1:8088/ | md5sum
echo -n "8088_tgweb "; curl -sS http://127.0.0.1:8088/tgweb/ | md5sum
echo -n "8090_tgweb "; curl -sS http://127.0.0.1:8090/tgweb/ | md5sum
echo "=== 8090 headers bridge ==="
curl -sS -m 5 -D - -o /tmp/b.out "http://127.0.0.1:8090/tgweb/?bridge=test" -H "Host: mglabot.mooo.com" | head -20
head -c 220 /tmp/b.out; echo
echo "=== 8090 websocket upgrade attempt ==="
curl -sS -m 5 -D - -o /tmp/b2.out "http://127.0.0.1:8090/tgweb/" \
  -H "Host: mglabot.mooo.com" \
  -H "Upgrade: websocket" -H "Connection: Upgrade" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" -H "Sec-WebSocket-Version: 13" | head -25
echo "=== decoy journal bridge hits ==="
journalctl -u mgla-decoy --since "today" --no-pager | grep -c bridge || true
journalctl -u mgla-decoy --since "today" --no-pager | grep bridge | tail -10
echo "=== disable crashloop stats on de1 ==="
systemctl disable --now mgla-stats 2>/dev/null || true
systemctl reset-failed mgla-stats 2>/dev/null || true
echo "stats stopped"
