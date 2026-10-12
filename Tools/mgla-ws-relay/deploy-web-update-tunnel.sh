#!/usr/bin/env bash
# Канал обновлений клиента через WEB proxy (когда «Авто-обход» ушёл на резервный маршрут).
#
#   WebView -> nginx :443 /tgweb/ -> tproxy-server (профиль mgla-upd, свой секрет)
#           -> 127.0.0.1:8769 nginx (только update-check / update-apk) -> 127.0.0.1:8768 ai_proxy
#
# Требует уже развёрнутого deploy-web-proxy.sh. Идемпотентен, секрет сохраняется.
# Секрет: MGLA_WEB_UPDATE_SECRET в /opt/mgla-ws-relay/env (32 hex) — его же кладём в local.properties.
set -euo pipefail

ENV_FILE=/opt/mgla-ws-relay/env
PROFILES=/etc/tproxy-server/profiles.json
TUNNEL_LISTEN="127.0.0.1:8769"
AI_PROXY="127.0.0.1:8768"
NGINX_CONF=/etc/nginx/conf.d/mgla-update-tunnel.conf
CARRIER_MODE="${CARRIER_MODE:-websocket-lanes}"

if [[ "${EUID}" -ne 0 ]]; then
	echo "run as root" >&2
	exit 1
fi

secret="$(grep -E '^MGLA_WEB_UPDATE_SECRET=' "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
if ! [[ "$secret" =~ ^[0-9a-f]{32}$ ]]; then
	secret="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
	sed -i '/^MGLA_WEB_UPDATE_SECRET=/d' "$ENV_FILE"
	echo "MGLA_WEB_UPDATE_SECRET=$secret" >> "$ENV_FILE"
	chmod 0600 "$ENV_FILE"
	echo "generated new WEB update secret"
fi

python3 - "$PROFILES" "$secret" "$TUNNEL_LISTEN" "$CARRIER_MODE" <<'PY'
import json, os, sys
path, secret, backend, mode = sys.argv[1:5]
data = json.load(open(path))
profiles = [p for p in data.get("profiles", []) if p.get("name") != "mgla-upd"]
profiles.append({
    "name": "mgla-upd",
    "secret": secret,
    "backend": backend,
    "carrier_mode": mode,
    "limits": {"max_sessions": 64, "max_streams": 128},
})
data["profiles"] = profiles
os.chmod(path, 0o600)
with open(path, "w") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
os.chmod(path, 0o400)
PY

cat > "$NGINX_CONF" <<EOF
# Backend профиля mgla-upd tproxy-server: только эндпоинты обновлений, только loopback.
server {
    listen $TUNNEL_LISTEN;
    server_name _;
    access_log off;

    location = /mgla-ai/v1/update-check {
        client_max_body_size 8k;
        proxy_pass http://$AI_PROXY;
        proxy_http_version 1.1;
        proxy_set_header Host mglabot.mooo.com;
        proxy_read_timeout 60s;
    }

    location = /mgla-ai/v1/update-apk {
        proxy_pass http://$AI_PROXY;
        proxy_http_version 1.1;
        proxy_set_header Host mglabot.mooo.com;
        proxy_buffering off;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
        send_timeout 300s;
    }

    location / {
        return 404;
    }
}
EOF

ufw status | grep -qE '^8769/' && { echo "UFW exposes 8769, refusing" >&2; exit 1; }

nginx -t
systemctl reload nginx
systemctl restart mgla-tproxy.service
sleep 2
systemctl is-active mgla-tproxy.service
curl -fsS "http://127.0.0.1:8091/readyz" && echo || echo "readyz not ready yet"
echo "update tunnel probe: $(curl -s -o /dev/null -w '%{http_code}' http://$TUNNEL_LISTEN/mgla-ai/v1/health) (404 expected)"
echo "WEB update secret stored in $ENV_FILE (MGLA_WEB_UPDATE_SECRET)"
