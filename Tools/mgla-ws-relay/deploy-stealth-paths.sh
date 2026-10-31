#!/bin/bash
# Install camouflage WS path pool into nginx site + restart relay.
# Usage: bash deploy-stealth-paths.sh [site-file]
set -euo pipefail
SITE="${1:-}"
if [[ -z "$SITE" ]]; then
  for cand in /etc/nginx/sites-enabled/mglabot /etc/nginx/sites-enabled/mglahub /etc/nginx/sites-enabled/fin.mgla; do
    if [[ -f "$cand" ]]; then SITE="$cand"; break; fi
  done
fi
[[ -n "$SITE" && -f "$SITE" ]] || { echo "nginx site not found"; exit 1; }

RELAY_DIR="${RELAY_DIR:-/opt/mgla-ws-relay}"
if [[ -f ./relay.py ]]; then
  install -m 0644 ./relay.py "$RELAY_DIR/relay.py"
  sed -i 's/\r$//' "$RELAY_DIR/relay.py" || true
fi

export MGLA_STEALTH_SITE="$SITE"
python3 - <<'PY'
import os
from pathlib import Path
path = Path(os.environ["MGLA_STEALTH_SITE"])
text = path.read_text(encoding="utf-8", errors="ignore")

def loc(name: str) -> str:
    return f"""
    location {name} {{
        proxy_pass http://127.0.0.1:8766;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Authorization $http_authorization;
        proxy_set_header X-Mgla-Token $http_x_mgla_token;
        proxy_set_header X-Mgla-Plan $http_x_mgla_plan;
        proxy_set_header X-Mgla-Priority $http_x_mgla_priority;
        proxy_set_header X-Mgla-Client $http_x_mgla_client;
        proxy_set_header X-Mgla-Tg-Id $http_x_mgla_tg_id;
        proxy_set_header X-Mgla-Tg-Ids $http_x_mgla_tg_ids;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
        proxy_request_buffering off;
        access_log off;
    }}"""

paths = [
    "/cdn/v3/stream",
    "/api/realtime",
    "/_next/webpack-hmr",
    "/socket/websocket",
    "/ws/v1/live",
]
missing = [p for p in paths if f"location {p}" not in text]
changed = False
if missing:
    block = "\n    # Camouflage WS path pool (CDN/SPA-looking).\n" + "".join(loc(p) for p in missing)
    if "location /apiws" in text:
        text = text.replace("location /apiws", block + "\n    location /apiws", 1)
    else:
        idx = text.rfind("}")
        text = text[:idx] + block + "\n" + text[idx:]
    changed = True
    print("inserted missing locations", missing, "into", path)
else:
    print("all camouflage paths present in", path)
if changed:
    path.write_text(text, encoding="utf-8")
PY

DECOY_ROOT="${DECOY_ROOT:-/var/www/mgla-decoy}"
if [[ -f ./decoy-tgweb/index.html ]]; then
  install -d -m 0755 "$DECOY_ROOT" "$DECOY_ROOT/tgweb"
  install -m 0644 ./decoy-tgweb/index.html "$DECOY_ROOT/index.html"
  install -m 0644 ./decoy-tgweb/index.html "$DECOY_ROOT/tgweb/index.html"
  systemctl restart mgla-decoy 2>/dev/null || true
fi

nginx -t
systemctl reload nginx
systemctl restart mgla-ws-relay
systemctl is-active mgla-ws-relay nginx
echo "OK stealth paths on $SITE"
