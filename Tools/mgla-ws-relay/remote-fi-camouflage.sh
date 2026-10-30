#!/usr/bin/env bash
set -euo pipefail
install -m 0644 /tmp/mgla-camouflage/relay.py /opt/mgla-ws-relay/relay.py
systemctl restart mgla-ws-relay
python3 - <<'PY'
from pathlib import Path
path = Path("/etc/nginx/sites-enabled/fin.mgla")
text = path.read_text()
block = """
    location /ws/v1/live {
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
    }
"""
changed = False
if "location /ws/v1/live" not in text:
    text = text.replace("location /apiws", block + "\n    location /apiws", 1)
    changed = True
api_block = text.split("location /apiws", 1)[1].split("location ", 1)[0] if "location /apiws" in text else ""
if "proxy_set_header Authorization" not in api_block:
    text = text.replace(
        "location /apiws {",
        "location /apiws {\n        proxy_set_header Authorization $http_authorization;",
        1,
    )
    changed = True
if changed:
    path.write_text(text)
    print("nginx updated")
else:
    print("nginx already ok")
PY
nginx -t
systemctl reload nginx
systemctl is-active mgla-ws-relay
grep -n "ws/v1/live" /etc/nginx/sites-enabled/fin.mgla | head
