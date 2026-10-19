#!/usr/bin/env bash
# Telegram WEB proxy (???????????????? ?????????????? ??????????-??????????????) ???? mglabot.mooo.com/tgweb.
#
#   nginx :443  location ^~ /tgweb/  ->  127.0.0.1:8090 tproxy-server  ->  127.0.0.1:2398 official MTProxy
#   ?????? ?????????????????? ???? mglabot (/, /apiws, /mgla-ai/...) ???? ??????????????????.
#
# ???????????? ???? ?????????????? (root):  bash deploy-web-proxy.sh
# ????????????????????????: ?????????????????? ???????????? ????????????????????????/??????????????????????????????????, ???????????? ??????????????????????.
# ????????????: MGLA_WEB_PROXY_SECRET ?? /opt/mgla-ws-relay/env (32 hex), ???????????????????????? ?????? ????????????????????.
set -euo pipefail

HOSTNAME_PUBLIC="${HOSTNAME_PUBLIC:-mglabot.mooo.com}"
BASE_PATH="${BASE_PATH:-tgweb}"
RELAY_LISTEN="127.0.0.1:8090"
ADMIN_LISTEN="127.0.0.1:8091"
PUBLIC_UPSTREAM="${PUBLIC_UPSTREAM:-http://127.0.0.1:8080}"
CARRIER_MODE="${CARRIER_MODE:-websocket-lanes}"
ENV_FILE=/opt/mgla-ws-relay/env
# mglabot: sites-enabled/mglabot; Ultra hub: sites-enabled/mglahub
NGINX_SITE="${NGINX_SITE:-/etc/nginx/sites-enabled/mglabot}"
SRC=/opt/tproxy-server
GO_VERSION=1.22.12
# Set SKIP_MTPROXY=1 when official MTProxy already runs on :2398 (e.g. mglahub).
SKIP_MTPROXY="${SKIP_MTPROXY:-0}"

if [[ "${EUID}" -ne 0 ]]; then
	echo "run as root" >&2
	exit 1
fi

# ---- secret
secret="$(grep -E '^MGLA_WEB_PROXY_SECRET=' "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
if ! [[ "$secret" =~ ^[0-9a-f]{32}$ ]]; then
	secret="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
	sed -i '/^MGLA_WEB_PROXY_SECRET=/d' "$ENV_FILE"
	echo "MGLA_WEB_PROXY_SECRET=$secret" >> "$ENV_FILE"
	chmod 0600 "$ENV_FILE"
	echo "generated new WEB proxy secret"
fi

# ---- source
if [[ ! -f "$SRC/go.mod" ]]; then
	rm -rf "$SRC"
	mkdir -p "$SRC"
	curl -fsSL https://github.com/telegramdesktop/tproxy-server/archive/refs/heads/master.tar.gz \
		| tar xz -C "$SRC" --strip-components=1
fi

# ---- official MTProxy (build + proxy-secret/proxy-multi.conf)
if [[ "$SKIP_MTPROXY" == "1" ]] && ss -lntp 2>/dev/null | grep -qE ':2398\b'; then
	echo "SKIP_MTPROXY=1 and :2398 already listening — reusing existing MTProxy"
else
	bash "$SRC/deploy/install-mtproxy.sh"
	install -d -o root -g mtproxy -m 0750 /etc/mtproxy
	umask 077
	printf 'MTPROXY_SECRET=%s\n' "$secret" > /etc/mtproxy/mtproxy.env
	chown root:mtproxy /etc/mtproxy/mtproxy.env
	chmod 0640 /etc/mtproxy/mtproxy.env
	umask 022
fi

# ---- Go toolchain
need_go=1
if command -v go >/dev/null 2>&1; then
	if go version | grep -Eq 'go1\.(2[0-9]|[3-9][0-9])'; then need_go=0; fi
elif [[ -x /usr/local/go/bin/go ]]; then
	export PATH="/usr/local/go/bin:$PATH"
	need_go=0
fi
if [[ "$need_go" -eq 1 ]]; then
	curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" -o /tmp/go.tgz
	rm -rf /usr/local/go
	tar -C /usr/local -xzf /tmp/go.tgz
	rm -f /tmp/go.tgz
	export PATH="/usr/local/go/bin:$PATH"
fi

# ---- tproxy-server binary (Mgla-only capability HMAC, stock Telegram Desktop won't auth)
if [[ -f /opt/mgla-ws-relay/patches/tproxy-mgla-capability.patch ]]; then
	(cd "$SRC" && patch -p1 -N -r - -i /opt/mgla-ws-relay/patches/tproxy-mgla-capability.patch >/dev/null 2>&1 || true)
elif [[ -f "$(dirname "$0")/patches/tproxy-mgla-capability.patch" ]]; then
	(cd "$SRC" && patch -p1 -N -r - -i "$(cd "$(dirname "$0")" && pwd)/patches/tproxy-mgla-capability.patch" >/dev/null 2>&1 || true)
else
	# Fallback: inline sed if patch file missing
	sed -i \
		-e 's/"tdesktop-web-proxy-bridge-v1\\n"/"mgla-web-proxy-bridge-v1\\n"/' \
		-e 's/"tdesktop-web-proxy-bridge-v2\\n"/"mgla-web-proxy-bridge-v2\\n"/' \
		"$SRC/internal/config/config.go"
fi
rm -rf /tmp/tproxy-server
(cd "$SRC" && GOFLAGS=-trimpath go build -o /tmp/tproxy-server ./cmd/tproxy-server)
install -m 0755 /tmp/tproxy-server /usr/local/bin/tproxy-server
rm -f /tmp/tproxy-server

# ---- tproxy config
if ! id tproxy >/dev/null 2>&1; then
	useradd --system --home /nonexistent --shell /usr/sbin/nologin tproxy
fi
install -d -o root -g tproxy -m 0750 /etc/tproxy-server
bash "$SRC/deploy/ensure-token-key.sh" /etc/tproxy-server/token.key

cat > /etc/tproxy-server/config.json <<EOF
{
  "public_hostname": "$HOSTNAME_PUBLIC",
  "base_path": "$BASE_PATH",
  "listen": "$RELAY_LISTEN",
  "admin_listen": "$ADMIN_LISTEN",
  "public_upstream": "$PUBLIC_UPSTREAM",
  "token_key_file": "/etc/tproxy-server/token.key",
  "static_routes": "exact",
  "profiles_file": "/run/credentials/mgla-tproxy.service/profiles.json",
  "enable_pprof": false,
  "limits": {
    "max_sessions_global": 128,
    "max_streams_global": 2048
  },
  "timeouts": {
    "backend_dial": "5s",
    "long_poll": "25s",
    "reconnect_grace": "2m",
    "bootstrap_lifetime": "2m",
    "read_header": "10s",
    "idle": "75s",
    "shutdown": "15s"
  }
}
EOF
chown root:tproxy /etc/tproxy-server/config.json
chmod 0640 /etc/tproxy-server/config.json

umask 077
cat > /etc/tproxy-server/profiles.json <<EOF
{
  "profiles": [
    {
      "name": "mgla",
      "secret": "$secret",
      "backend": "127.0.0.1:2398",
      "carrier_mode": "$CARRIER_MODE"
    }
  ]
}
EOF
chmod 0400 /etc/tproxy-server/profiles.json
umask 022

# ---- systemd units (UFW already drops 2398/8888 externally, so the nft firewall unit is not used)
strip_fw() { sed -e 's/ tproxy-firewall\.service//g' -e '/^Requires=tproxy-firewall\.service$/d' -e '/^Requires=$/d'; }
# Do not add --address: MTProxy also binds its outbound middle-end sockets to it,
# so a loopback address breaks every media connection (connect(): Invalid argument).
strip_fw < "$SRC/deploy/mtproxy.service" > /etc/systemd/system/mgla-mtproxy.service
strip_fw < "$SRC/deploy/tproxy-server.service" \
	| sed -e 's/mtproxy\.service/mgla-mtproxy.service/g' \
	> /etc/systemd/system/mgla-tproxy.service
if [[ "$SKIP_MTPROXY" == "1" ]]; then
	# Reuse stock/official mtproxy.service already bound on :2398.
	sed -i 's/mgla-mtproxy\.service/mtproxy.service/g' /etc/systemd/system/mgla-tproxy.service
fi
sed -e 's/mtproxy\.service/mgla-mtproxy.service/g' "$SRC/deploy/refresh-mtproxy-config.sh" \
	> /usr/local/sbin/mgla-refresh-mtproxy-config.sh
chmod 0755 /usr/local/sbin/mgla-refresh-mtproxy-config.sh
cat > /etc/systemd/system/mgla-refresh-mtproxy-config.service <<'EOF'
[Unit]
Description=Refresh official MTProxy routing configuration
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/mgla-refresh-mtproxy-config.sh
EOF
cat > /etc/systemd/system/mgla-refresh-mtproxy-config.timer <<'EOF'
[Unit]
Description=Daily MTProxy routing configuration refresh

[Timer]
OnCalendar=daily
RandomizedDelaySec=1h
Persistent=true

[Install]
WantedBy=timers.target
EOF

ufw status | grep -qE '^(2398|8888|8090|8091)/' && { echo "UFW exposes a backend port, refusing" >&2; exit 1; }

# Stock tproxy-server (random base_path / :8080) conflicts with mgla-tproxy on hub.
if systemctl list-unit-files tproxy-server.service >/dev/null 2>&1; then
	systemctl disable --now tproxy-server.service >/dev/null 2>&1 || true
fi

systemctl daemon-reload
if [[ "$SKIP_MTPROXY" == "1" ]] && ss -lntp 2>/dev/null | grep -qE ':2398\b'; then
	echo "keeping existing MTProxy on :2398"
else
	systemctl enable --now mgla-mtproxy.service
	systemctl restart mgla-mtproxy.service
	systemctl enable --now mgla-refresh-mtproxy-config.timer
fi
systemctl enable --now mgla-tproxy.service
systemctl restart mgla-tproxy.service

# ---- nginx: prefix location inside the existing HTTPS server block
if [[ ! -f "$NGINX_SITE" ]]; then
	echo "nginx site missing: $NGINX_SITE" >&2
	exit 1
fi
if ! grep -q "location \^~ /$BASE_PATH/" "$NGINX_SITE"; then
	cp "$NGINX_SITE" "/root/nginx-webproxy.bak.$(date +%Y%m%d%H%M%S)"
	block="$(mktemp)"
	cat > "$block" <<EOF
    # Telegram WEB proxy (tproxy-server). X-Forwarded-For must be a single address.
    location ^~ /$BASE_PATH/ {
        client_max_body_size 4m;
        proxy_pass http://$RELAY_LISTEN;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$mgla_tgweb_connection;
        proxy_read_timeout 90s;
        proxy_send_timeout 90s;
        proxy_buffering off;
        proxy_request_buffering off;
        access_log off;
    }

EOF
	python3 - "$NGINX_SITE" "$block" <<'PY'
import re, sys
path, block_path = sys.argv[1], sys.argv[2]
text = open(path).read()
block = open(block_path).read()
# Prefer Certbot-marked listen; else first "listen 443 ssl" inside a server block.
markers = [
    "    listen 443 ssl; # managed by Certbot",
    "    listen 443 ssl;",
]
replaced = False
for marker in markers:
    if marker in text:
        text = text.replace(marker, block + marker, 1)
        replaced = True
        break
if not replaced:
    sys.exit("listen 443 ssl marker not found in nginx site")
if "map $http_upgrade $mgla_tgweb_connection" not in text:
    text = ("map $http_upgrade $mgla_tgweb_connection {\n    default upgrade;\n    ''      \"\";\n}\n\n") + text
open(path, "w").write(text)
PY
	rm -f "$block"
fi
nginx -t
systemctl reload nginx

sleep 2
curl -fsS "http://$ADMIN_LISTEN/healthz" && echo
curl -fsS "http://$ADMIN_LISTEN/readyz" && echo || echo "readyz not ready yet"

marked="$({ printf '\x70'; printf "$(printf %s "$secret" | sed 's/../\\x&/g')"; } | base64 | tr '+/' '-_' | tr -d '=\n')"
echo "WEB proxy server: $HOSTNAME_PUBLIC/$BASE_PATH"
echo "WEB proxy secret (hex, for MGLA_WEB_PROXY_SECRET): stored in $ENV_FILE"
echo "Link secret (marked): $marked"
