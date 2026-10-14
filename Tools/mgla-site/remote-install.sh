#!/usr/bin/env bash
set -euo pipefail
rm -rf /tmp/mgla-site-src
mkdir -p /tmp/mgla-site-src /opt/mgla-site/uploads
tar -xzf /tmp/mgla-site.tgz -C /tmp/mgla-site-src

# Copy sources without wiping DB/env/venv/uploads
SRC=/tmp/mgla-site-src/mgla-site
DEST=/opt/mgla-site
for item in "$SRC"/*; do
  base="$(basename "$item")"
  case "$base" in
    venv|site.db|secret.key|uploads|env|__pycache__|remote-install.sh) continue ;;
  esac
  if [[ -d "$item" ]]; then
    rm -rf "$DEST/$base"
    cp -a "$item" "$DEST/$base"
  else
    cp -a "$item" "$DEST/$base"
  fi
done

if [[ ! -d "$DEST/venv" ]]; then
  python3 -m venv "$DEST/venv"
fi
"$DEST/venv/bin/pip" install -q -r "$DEST/requirements.txt"

if [[ ! -f "$DEST/env" ]]; then
  PASS="$(openssl rand -hex 12)"
  cat >"$DEST/env" <<ENV
MGLA_SITE_DATA=/opt/mgla-site
MGLA_SITE_ADMIN_USER=admin
MGLA_SITE_ADMIN_PASSWORD=$PASS
ENV
  echo "ADMIN_PASSWORD=$PASS"
fi

chmod 640 "$DEST/env"
chown root:www-data "$DEST/env"
chown -R www-data:www-data "$DEST"
# env must stay group-readable by www-data
chmod 640 "$DEST/env"
chown root:www-data "$DEST/env"

cp "$DEST/mgla-site.service" /etc/systemd/system/mgla-site.service
systemctl daemon-reload
systemctl enable --now mgla-site
systemctl restart mgla-site

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

echo "=== health ==="
curl -sS -H 'Host: mgla.home.mooo.com' http://127.0.0.1/healthz
echo
echo "=== critical services (must stay active) ==="
systemctl is-active mgla-ws-relay mgla-stats nginx mgla-site mtproxy.service tproxy-server.service
echo "=== hub still ok ==="
curl -sS https://127.0.0.1/mgla-stats/v1/health -H 'Host: mglahub.mooo.com' --insecure
echo
grep -n 'server_name\|location /apiws' /etc/nginx/sites-enabled/mglahub | head -20
echo "=== admin password (from env) ==="
grep MGLA_SITE_ADMIN_PASSWORD "$DEST/env"
