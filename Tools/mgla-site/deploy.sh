#!/usr/bin/env bash
# Deploy mgla-site to this host without touching mglahub nginx / relay / tproxy.
set -euo pipefail

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
DEST=/opt/mgla-site

if [[ "$(id -u)" -ne 0 ]]; then
  echo "run as root" >&2
  exit 1
fi

mkdir -p "$DEST" "$DEST/uploads"
# Prefer rsync; fall back to cp (some minimal images lack rsync).
if command -v rsync >/dev/null 2>&1; then
  rsync -a \
    --exclude 'venv/' \
    --exclude 'site.db' \
    --exclude 'secret.key' \
    --exclude 'uploads/' \
    --exclude 'env' \
    --exclude '__pycache__/' \
    "$SRC_DIR/" "$DEST/"
else
  # Preserve DB/env/uploads/venv if already present.
  shopt -s dotglob nullglob
  for item in "$SRC_DIR"/*; do
    base="$(basename "$item")"
    case "$base" in
      venv|site.db|secret.key|uploads|env|__pycache__) continue ;;
    esac
    if [[ -d "$item" ]]; then
      rm -rf "$DEST/$base"
      cp -a "$item" "$DEST/$base"
    else
      cp -a "$item" "$DEST/$base"
    fi
  done
fi

if [[ ! -d "$DEST/venv" ]]; then
  python3 -m venv "$DEST/venv"
fi
"$DEST/venv/bin/pip" install -q -r "$DEST/requirements.txt"

if [[ ! -f "$DEST/env" ]]; then
  PASS="$(openssl rand -hex 12)"
  cat >"$DEST/env" <<EOF
MGLA_SITE_DATA=/opt/mgla-site
MGLA_SITE_ADMIN_USER=admin
MGLA_SITE_ADMIN_PASSWORD=$PASS
EOF
  chmod 600 "$DEST/env"
  echo "Created /opt/mgla-site/env — admin password: $PASS"
fi

chown -R www-data:www-data "$DEST"
# keep env root-readable by service via EnvironmentFile (root) — gunicorn runs as www-data
# so password must be readable: grant www-data read on env + data
chmod 640 "$DEST/env"
chown root:www-data "$DEST/env"

cp "$DEST/mgla-site.service" /etc/systemd/system/mgla-site.service
systemctl daemon-reload
systemctl enable --now mgla-site
systemctl restart mgla-site

# HTTP-only bootstrap config until cert exists (never edits mglahub)
BOOT=/etc/nginx/sites-available/mgla-home
if [[ ! -f /etc/letsencrypt/live/mgla.home.mooo.com/fullchain.pem ]]; then
  cat >"$BOOT" <<'NGX'
server {
    listen 80;
    listen [::]:80;
    server_name mgla.home.mooo.com;

    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        proxy_pass http://127.0.0.1:8770;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 12m;
    }
}
NGX
else
  cp "$DEST/nginx-mgla-home.conf" "$BOOT"
fi

ln -sf "$BOOT" /etc/nginx/sites-enabled/mgla-home
nginx -t
systemctl reload nginx

echo "OK: site service + nginx vhost ready"
curl -sS -H 'Host: mgla.home.mooo.com' http://127.0.0.1/healthz || true
echo
systemctl is-active mgla-ws-relay mgla-stats nginx mgla-site || true
