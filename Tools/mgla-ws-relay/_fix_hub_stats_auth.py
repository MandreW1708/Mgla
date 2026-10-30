#!/usr/bin/env python3
"""Fix mglahub 503 on stats/admin: restore /mgla-auth/, drop admin rate limits, whitelist hub IP."""
from __future__ import annotations

import subprocess

PLINK = r"C:\Program Files\PuTTY\plink.exe"
IP = "194.58.33.22"
PW = "d3i963xzV2cp"

REMOTE = r"""
set -e
python3 <<'PY'
from pathlib import Path
import re

nginx_conf = Path("/etc/nginx/nginx.conf")
nc = nginx_conf.read_text(encoding="utf-8", errors="ignore")
if "194.58.33.22" not in nc:
    nc2, n = re.subn(
        r"(geo \$mgla_limit_key \{[^}]*?31\.77\.218\.160 \"\";)",
        r"\1\n        194.58.33.22 \"\";    # mglahub self / hairpin",
        nc,
        count=1,
        flags=re.S,
    )
    if n:
        nginx_conf.write_text(nc2, encoding="utf-8")
        print("whitelisted hub IP in geo")
    else:
        print("WARN: could not patch geo block")
else:
    print("hub IP already in geo")

site = Path("/etc/nginx/sites-available/mglahub")
t = site.read_text(encoding="utf-8", errors="ignore")
orig = t

AUTH_BLOCK = """
    # Client install auth (JWT). Must NOT sit under location / (Basic Auth -> 401/503).
    location /mgla-auth/ {
        client_max_body_size 32k;
        proxy_pass http://127.0.0.1:8767;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Mgla-Token $http_x_mgla_token;
        proxy_set_header X-Mgla-Client $http_x_mgla_client;
        access_log off;
    }
"""

if "location /mgla-auth/" not in t:
    anchor = "    location /mgla-updates/"
    if anchor in t:
        t = t.replace(anchor, AUTH_BLOCK + "\n" + anchor, 1)
        print("inserted /mgla-auth/")
    else:
        print("ERROR: no anchor for mgla-auth")
else:
    print("/mgla-auth/ already present")

def strip_limit_in_location(src: str, loc: str) -> str:
    pattern = rf"(location\s+{loc}\s*\{{)(.*?)(\n\s*\}})"
    def repl(m):
        head, body, tail = m.group(1), m.group(2), m.group(3)
        body2 = re.sub(r"\n[ \t]*limit_req[^\n]*", "", body)
        return head + body2 + tail
    return re.sub(pattern, repl, src, count=1, flags=re.S)

for loc in (r"\^~ /admin", r"\^~ /mgla-admin", r"\^~ /control", r"/"):
    t2 = strip_limit_in_location(t, loc)
    if t2 != t:
        print("stripped limit_req from", loc)
        t = t2

if t != orig:
    site.write_text(t, encoding="utf-8")
    print("wrote", site)
else:
    print("site unchanged")

env = Path("/opt/mgla-ws-relay/env")
if env.is_file():
    lines = env.read_text(encoding="utf-8", errors="ignore").splitlines()
    want = {
        "MGLA_HUB_AUTH_REGISTER_URL": "http://127.0.0.1:8767/mgla-auth/v1/register",
        "MGLA_HUB_AUTH_TOKEN_URL": "http://127.0.0.1:8767/mgla-auth/v1/token",
    }
    keys = set()
    out = []
    for line in lines:
        if "=" in line and not line.strip().startswith("#"):
            k = line.split("=", 1)[0].strip()
            if k in want:
                out.append(f"{k}={want[k]}")
                keys.add(k)
                continue
        out.append(line)
    for k, v in want.items():
        if k not in keys:
            out.append(f"{k}={v}")
    env.write_text("\n".join(out) + "\n", encoding="utf-8")
    print("env auth URLs -> 127.0.0.1")
PY

nginx -t
systemctl reload nginx
systemctl restart mgla-ai-proxy 2>/dev/null || true

echo "=== verify ==="
curl -sS -m 8 -o /tmp/a.out -w "admin:%{http_code}\n" https://mglahub.mooo.com/admin
head -c 100 /tmp/a.out; echo
curl -sS -m 8 -o /tmp/r.out -w "report:%{http_code}\n" https://mglahub.mooo.com/report
head -c 100 /tmp/r.out; echo
curl -sS -m 8 -o /tmp/au.out -w "auth:%{http_code}\n" -X POST https://mglahub.mooo.com/mgla-auth/v1/register \
  -H "Content-Type: application/json" -H "X-Mgla-Token: bad" -d "{}"
head -c 160 /tmp/au.out; echo
for i in 1 2 3 4 5 6 7 8; do
  curl -sS -m 4 -o /dev/null -w "%{http_code} " https://mglahub.mooo.com/admin
done
echo
"""


def main() -> None:
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
