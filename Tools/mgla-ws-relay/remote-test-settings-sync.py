#!/usr/bin/env python3
import json
import ssl
import urllib.error
import urllib.request

body = json.dumps(
    {"tg_id": 1, "v": 1, "updated_at": 1, "settings": {"md3_switches": False}}
).encode()
urls = [
    "http://127.0.0.1:8767/mgla-settings/v1/sync",
    "http://127.0.0.1:8768/mgla-ai/v1/settings-sync",
    "https://127.0.0.1/mgla-settings/v1/sync",
    "https://127.0.0.1/mgla-ai/v1/settings-sync",
]
ctx = ssl._create_unverified_context()
token = "29df3dc414420d5ee84fc16f33ddfcc27454f1f2f4818192"
for url in urls:
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Mgla-Token": token,
            "X-Mgla-Client": "mgla",
            "Host": "mglahub.mooo.com",
        },
    )
    try:
        with urllib.request.urlopen(
            req, timeout=12, context=ctx if url.startswith("https") else None
        ) as r:
            print(url, r.status, r.read()[:160])
    except urllib.error.HTTPError as e:
        print(url, e.code, e.read()[:200])
    except Exception as e:
        print(url, type(e).__name__, e)
