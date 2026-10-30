#!/usr/bin/env python3
"""Remove limit_req from /mgla-settings/ and /mgla-tasks/ on mglahub (shared relay IP → 503)."""
from __future__ import annotations

import subprocess
from pathlib import Path

PLINK = r"C:\Program Files\PuTTY\plink.exe"
IP = "194.58.33.22"
PW = "d3i963xzV2cp"

REMOTE = r"""
set -e
python3 - <<'PY'
from pathlib import Path
import re

paths = [
    Path('/etc/nginx/sites-available/mglahub'),
    Path('/etc/nginx/sites-enabled/mglahub'),
]
# Also catch any conf that defines these locations.
extra = list(Path('/etc/nginx').rglob('*'))
for p in extra:
    if p.is_file() and p.suffix in ('', '.conf') and 'mgla' in p.name.lower():
        if p not in paths:
            paths.append(p)

changed = []
for path in paths:
    if not path.is_file():
        continue
    try:
        text = path.read_text(encoding='utf-8', errors='ignore')
    except Exception:
        continue
    if '/mgla-settings/' not in text and '/mgla-tasks/' not in text:
        continue
    orig = text

    def strip_limit(block_name: str, src: str) -> str:
        # Remove limit_req lines that sit inside the named location block.
        pattern = rf'(location\s+{re.escape(block_name)}\s*\{{)(.*?)(\n\s*\}})'
        def repl(m):
            head, body, tail = m.group(1), m.group(2), m.group(3)
            body2 = re.sub(r'\n[ \t]*limit_req[^\n]*', '', body)
            return head + body2 + tail
        return re.sub(pattern, repl, src, flags=re.S)

    text = strip_limit('/mgla-settings/', text)
    text = strip_limit('/mgla-tasks/', text)
    if text != orig:
        path.write_text(text, encoding='utf-8')
        changed.append(str(path))
        print('patched', path)
    else:
        print('unchanged', path)

if not changed:
    print('no files changed (maybe already patched)')
PY
nginx -t
systemctl reload nginx
echo '=== verify ==='
curl -sS -m 8 -o /tmp/ss.out -w 'settings:%{http_code}\n' -X POST https://mglahub.mooo.com/mgla-settings/v1/sync \
  -H 'Content-Type: application/json' -H 'X-Mgla-Token: bad' \
  -d '{"tg_id":1,"v":1,"updated_at":0,"settings":{}}'
head -c 160 /tmp/ss.out; echo
curl -sS -m 8 -o /tmp/ts.out -w 'tasks:%{http_code}\n' -X POST https://mglahub.mooo.com/mgla-tasks/v1/sync \
  -H 'Content-Type: application/json' -H 'X-Mgla-Token: bad' \
  -d '{"tg_id":1,"v":1,"updated_at":0,"settings":{}}'
head -c 160 /tmp/ts.out; echo
for i in 1 2 3 4 5; do
  curl -sS -m 5 -o /dev/null -w "burst$i:%{http_code} " -X POST https://mglahub.mooo.com/mgla-settings/v1/sync \
    -H 'Content-Type: application/json' -H 'X-Mgla-Token: bad' \
    -d '{"tg_id":1,"v":1,"updated_at":0,"settings":{}}'
done
echo
"""

def main() -> None:
    r = subprocess.run(
        [PLINK, "-ssh", "-batch", "-pw", PW, f"root@{IP}", REMOTE],
        capture_output=True,
    )
    out = (r.stdout or b"") + (r.stderr or b"")
    print(out.decode("utf-8", "replace"))
    raise SystemExit(r.returncode)


if __name__ == "__main__":
    main()
