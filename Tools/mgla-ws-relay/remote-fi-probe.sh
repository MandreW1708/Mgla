#!/usr/bin/env bash
set -euo pipefail
TOK=$(grep '^MGLA_WS_TOKEN=' /opt/mgla-ws-relay/env | cut -d= -f2- | tr -d '"' | tr -d "'")
/opt/mgla-ws-relay/venv/bin/python /tmp/remote-ws-probe.py "$TOK" fin.mgla.mooo.com
