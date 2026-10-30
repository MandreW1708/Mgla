#!/bin/bash
set -e
echo "=== services ==="
systemctl is-active mgla-ws-relay mgla-ai-proxy mgla-mtproxy nginx || true
echo "=== ports ==="
ss -lntp | grep -E '443|8766|8768|2398|8080' || true
echo "=== 443 peer top ==="
ss -tn state established '( sport = :443 )' | awk 'NR>1{split($5,a,":"); print a[1]}' | sort | uniq -c | sort -rn | head -15
echo "=== 8766 count ==="
ss -tn state established '( sport = :8766 )' | wc -l
echo "=== ws-live.log ==="
wc -l /var/log/nginx/ws-live.log 2>/dev/null || true
tail -n 20 /var/log/nginx/ws-live.log 2>/dev/null || true
echo "=== apiws last android ==="
grep 'Android' /var/log/nginx/access.log | grep -E 'apiws|ws/v1' | tail -n 15 || true
echo "=== mtproxy to DC2 ==="
timeout 5 bash -c 'echo >/dev/tcp/149.154.167.51/443' && echo dc2_tcp_ok || echo dc2_tcp_FAIL
timeout 5 bash -c 'echo >/dev/tcp/127.0.0.1/2398' && echo mtproxy_tcp_ok || echo mtproxy_tcp_FAIL
echo "=== ai_proxy auth local ==="
TOK=$(grep '^MGLA_WS_TOKEN=' /opt/mgla-ws-relay/env | cut -d= -f2-)
curl -sS -m 8 -o /tmp/ar.out -w "auth_reg:%{http_code}\n" -X POST http://127.0.0.1:8768/mgla-ai/v1/auth/register \
  -H "Content-Type: application/json" -H "X-Mgla-Token: $TOK" -H "X-Mgla-Client: mgla" \
  -d '{"device_id":"diag-de1","app":"mgla"}' || true
head -c 200 /tmp/ar.out; echo
echo "=== hub entitlements from de1 ==="
curl -sS -m 8 -o /tmp/en.out -w "ent:%{http_code}\n" -X POST https://mglahub.mooo.com/mgla-config/v1/entitlements \
  -H "Content-Type: application/json" -H "X-Mgla-Token: bad" -d '{"tg_id":1}' || true
head -c 120 /tmp/en.out; echo
echo "=== restart ai_proxy + relay for clean state ==="
systemctl restart mgla-ai-proxy
systemctl restart mgla-ws-relay
sleep 2
systemctl is-active mgla-ai-proxy mgla-ws-relay
journalctl -u mgla-ws-relay -n 8 --no-pager
echo "=== probe both paths ==="
curl -sS -m 6 -o /dev/null -w "apiws:%{http_code}\n" --http1.1 \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Sec-WebSocket-Protocol: binary" \
  -H "Authorization: Bearer $TOK" -H "X-Mgla-Client: mgla" \
  "https://mglabot.mooo.com/apiws?dc=2" || true
curl -sS -m 6 -o /dev/null -w "live:%{http_code}\n" --http1.1 \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Sec-WebSocket-Protocol: binary" \
  -H "Authorization: Bearer $TOK" -H "X-Mgla-Client: mgla" \
  "https://mglabot.mooo.com/ws/v1/live?dc=2" || true
sleep 1
journalctl -u mgla-ws-relay --since "30 sec ago" --no-pager | tail -n 20
