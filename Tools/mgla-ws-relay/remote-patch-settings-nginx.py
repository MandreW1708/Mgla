#!/usr/bin/env python3
"""Insert /mgla-settings/ or /mgla-ai/v1/settings-sync nginx locations if missing."""
from pathlib import Path
import sys

mode = sys.argv[1] if len(sys.argv) > 1 else "hub"

HUB_BLOCK = """
    location /mgla-settings/ {
        limit_req zone=mgla_hub burst=40 nodelay;
        client_max_body_size 512k;
        proxy_pass http://127.0.0.1:8767;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Mgla-Token $http_x_mgla_token;
        proxy_set_header X-Mgla-Client $http_x_mgla_client;
        access_log off;
    }
"""

RELAY_BLOCK = """
location = /mgla-ai/v1/settings-sync {
    client_max_body_size 512k;
    proxy_pass http://127.0.0.1:8768;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Mgla-Token $http_x_mgla_token;
    proxy_set_header X-Mgla-Client $http_x_mgla_client;
    proxy_read_timeout 60s;
    access_log off;
}
"""


def fix_broken_settings_blocks(text: str) -> str:
    # Remove previously broken inserts where $host was stripped.
    lines = text.splitlines(True)
    out = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if "location /mgla-settings/" in line or "location = /mgla-ai/v1/settings-sync" in line:
            # skip until closing brace of this location
            depth = 0
            while i < len(lines):
                if "{" in lines[i]:
                    depth += lines[i].count("{")
                if "}" in lines[i]:
                    depth -= lines[i].count("}")
                i += 1
                if depth <= 0:
                    break
            continue
        out.append(line)
        i += 1
    return "".join(out)


def main() -> None:
    if mode == "hub":
        path = Path("/etc/nginx/sites-enabled/mglahub")
        text = fix_broken_settings_blocks(path.read_text())
        if "location /mgla-settings/" not in text:
            if "location /mgla-tasks/" in text:
                text = text.replace("location /mgla-tasks/ {", HUB_BLOCK + "\n    location /mgla-tasks/ {", 1)
            else:
                text = text.replace("location /mgla-ai/ {", HUB_BLOCK + "\n    location /mgla-ai/ {", 1)
            path.write_text(text)
            print("hub: inserted /mgla-settings/")
        else:
            path.write_text(text)
            print("hub: already ok")
        return

    for cand in Path("/etc/nginx/sites-enabled").iterdir():
        text = cand.read_text(errors="ignore")
        if "mgla-ai" not in text and "apiws" not in text:
            continue
        text = fix_broken_settings_blocks(text)
        if "location = /mgla-ai/v1/settings-sync" in text:
            cand.write_text(text)
            print(cand, "already ok")
            continue
        if "location = /mgla-ai/v1/tasks-sync" in text:
            text = text.replace(
                "location = /mgla-ai/v1/tasks-sync",
                RELAY_BLOCK + "\nlocation = /mgla-ai/v1/tasks-sync",
                1,
            )
        elif "location /mgla-ai/" in text:
            text = text.replace("location /mgla-ai/", RELAY_BLOCK + "\nlocation /mgla-ai/", 1)
        else:
            continue
        cand.write_text(text)
        print("patched", cand)


if __name__ == "__main__":
    main()
