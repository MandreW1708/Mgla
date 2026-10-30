#!/usr/bin/env bash
# Deploy WS path camouflage + alias SNI + tgweb decoy on a relay/hub host.
# Usage (on the server as root):
#   ALIAS_HOST=31-77-218-160.sslip.io PRIMARY_HOST=mglabot.mooo.com bash deploy-camouflage.sh
#   ALIAS_HOST=194-58-33-22.sslip.io PRIMARY_HOST=mglahub.mooo.com bash deploy-camouflage.sh
set -euo pipefail

PRIMARY_HOST="${PRIMARY_HOST:?set PRIMARY_HOST}"
ALIAS_HOST="${ALIAS_HOST:?set ALIAS_HOST}"
RELAY_DIR="${RELAY_DIR:-/opt/mgla-ws-relay}"
DECOY_ROOT="${DECOY_ROOT:-/var/www/mgla-decoy}"
DECOY_PORT="${DECOY_PORT:-8088}"

install -d -m 0755 "$DECOY_ROOT" "$DECOY_ROOT/tgweb"
if [[ -f ./decoy-tgweb/index.html ]]; then
  install -m 0644 ./decoy-tgweb/index.html "$DECOY_ROOT/index.html"
elif [[ -f "$RELAY_DIR/decoy-tgweb/index.html" ]]; then
  install -m 0644 "$RELAY_DIR/decoy-tgweb/index.html" "$DECOY_ROOT/index.html"
else
  cat > "$DECOY_ROOT/index.html" <<'HTML'
<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>Status</title></head>
<body><main><h1>Service online</h1><p>Runtime endpoint.</p></main></body></html>
HTML
fi
# tproxy forwards the full /tgweb/... path to public_upstream.
install -m 0644 "$DECOY_ROOT/index.html" "$DECOY_ROOT/tgweb/index.html"

# Lightweight decoy for tproxy public_upstream (wrong /tgweb secret → HTML, not 404).
cat > /etc/systemd/system/mgla-decoy.service <<EOF
[Unit]
Description=Mgla tgweb decoy static site
After=network.target

[Service]
Type=simple
WorkingDirectory=$DECOY_ROOT
ExecStart=/usr/bin/python3 -m http.server $DECOY_PORT --bind 127.0.0.1
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now mgla-decoy.service

if [[ -f /etc/tproxy-server/config.json ]]; then
  DECOY_PORT="$DECOY_PORT" python3 - <<'PY'
import json, os
p="/etc/tproxy-server/config.json"
port=os.environ.get("DECOY_PORT","8088")
with open(p) as f:
    cfg=json.load(f)
cfg["public_upstream"]=f"http://127.0.0.1:{port}"
with open(p,"w") as f:
    json.dump(cfg,f,indent=2)
    f.write("\n")
print("tproxy public_upstream ->", cfg["public_upstream"])
PY
  systemctl restart mgla-tproxy.service || true
fi

# Ensure camouflage WS location exists in the primary site config.
SITE=""
for cand in /etc/nginx/sites-enabled/mglabot /etc/nginx/sites-enabled/mglahub /etc/nginx/sites-enabled/fin.mgla; do
  if [[ -f "$cand" ]]; then SITE="$cand"; break; fi
done
# Fallback: first site mentioning PRIMARY_HOST
if [[ -z "$SITE" ]]; then
  SITE=$(grep -rl "server_name.*$PRIMARY_HOST" /etc/nginx/sites-enabled 2>/dev/null | head -1 || true)
fi
[[ -n "$SITE" ]] || { echo "nginx site for $PRIMARY_HOST not found"; exit 1; }

python3 - <<PY
from pathlib import Path
path = Path("$SITE")
text = path.read_text()
alias = "$ALIAS_HOST"
primary = "$PRIMARY_HOST"
changed = False

# Add alias to server_name lines that mention primary.
out = []
for line in text.splitlines(True):
    if "server_name" in line and primary in line and alias not in line:
        line = line.replace(primary, f"{primary} {alias}", 1)
        changed = True
    out.append(line)
text = "".join(out)

block = '''
    location /ws/v1/live {
        proxy_pass http://127.0.0.1:8766;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Authorization \$http_authorization;
        proxy_set_header X-Mgla-Token \$http_x_mgla_token;
        proxy_set_header X-Mgla-Plan \$http_x_mgla_plan;
        proxy_set_header X-Mgla-Priority \$http_x_mgla_priority;
        proxy_set_header X-Mgla-Client \$http_x_mgla_client;
        proxy_set_header X-Mgla-Tg-Id \$http_x_mgla_tg_id;
        proxy_set_header X-Mgla-Tg-Ids \$http_x_mgla_tg_ids;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
        proxy_request_buffering off;
        access_log off;
    }
'''
if "location /ws/v1/live" not in text:
    # Insert before location /apiws if present, else before last closing brace of ssl server.
    if "location /apiws" in text:
        text = text.replace("location /apiws", block + "\n    location /apiws", 1)
    else:
        idx = text.rfind("}")
        text = text[:idx] + block + "\n" + text[idx:]
    changed = True

# Forward Authorization on existing /apiws if missing.
if "location /apiws" in text and "proxy_set_header Authorization" not in text.split("location /apiws", 1)[1].split("location ", 1)[0]:
    text = text.replace(
        "location /apiws {",
        "location /apiws {\n        proxy_set_header Authorization \$http_authorization;",
        1,
    )
    changed = True

if changed:
    path.write_text(text)
    print("updated", path)
else:
    print("nginx site already up to date:", path)
PY

# HTTP-80 server for ACME on alias if missing.
if ! grep -Rqs "server_name.*$ALIAS_HOST" /etc/nginx/sites-enabled; then
  cat > /etc/nginx/sites-available/mgla-alias-acme.conf <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $ALIAS_HOST;
    location /.well-known/acme-challenge/ { root /var/www/html; }
    location / { return 301 https://\$host\$request_uri; }
}
EOF
  ln -sfn /etc/nginx/sites-available/mgla-alias-acme.conf /etc/nginx/sites-enabled/mgla-alias-acme.conf
fi

nginx -t
systemctl reload nginx

# Expand LE cert with alias, reuse key so SPKI pin stays valid.
certbot certonly --nginx --non-interactive --agree-tos --keep-until-expiring \
  --cert-name "$PRIMARY_HOST" --expand --reuse-key \
  -d "$PRIMARY_HOST" -d "$ALIAS_HOST" || \
certbot certonly --nginx --non-interactive --agree-tos \
  --cert-name "$ALIAS_HOST" \
  -d "$ALIAS_HOST"

nginx -t
systemctl reload nginx

# Deploy relay.py if present next to this script / in RELAY_DIR.
if [[ -f ./relay.py ]]; then
  install -m 0644 ./relay.py "$RELAY_DIR/relay.py"
  systemctl restart mgla-ws-relay.service || systemctl restart mgla-ws-relay || true
fi

echo "OK primary=$PRIMARY_HOST alias=$ALIAS_HOST decoy=:$DECOY_PORT"
curl -sS -m 5 -o /dev/null -w "decoy=%{http_code}\n" "http://127.0.0.1:$DECOY_PORT/" || true
curl -sS -m 8 -o /dev/null -w "alias_tls=%{http_code}\n" --resolve "$ALIAS_HOST:443:127.0.0.1" "https://$ALIAS_HOST/" || true
