#!/bin/bash
python3 <<'PY'
from pathlib import Path
import json
print('=== env ===')
for line in Path('/opt/mgla-ws-relay/env').read_text().splitlines():
    if any(x in line for x in ('WEB', 'SECRET', 'TOKEN', 'MTPROXY')) and '=' in line and not line.strip().startswith('#'):
        k,v=line.split('=',1)
        v=v.strip().strip('"').strip("'")
        print(f'{k}: len={len(v)} tail={v[-8:]}')
print('=== profile secrets ===')
p=json.loads(Path('/etc/tproxy-server/profiles.json').read_text())
for prof in p.get('profiles',[]):
    s=prof.get('secret','')
    print(prof.get('name'), 'len', len(s), 'tail', s[-8:], 'full_eq_mtproxy', s=='30b1f4e7f03924fa397cae58b7b6e9f3')
cred=Path('/run/credentials/mgla-tproxy.service/profiles.json')
if cred.exists():
    p2=json.loads(cred.read_text())
    print('cred same', p2==p)
print('=== config ===')
print(Path('/etc/tproxy-server/config.json').read_text())
PY
# Compare whether mgla web secret in env matches profile
python3 <<'PY'
from pathlib import Path
import json
env={}
for line in Path('/opt/mgla-ws-relay/env').read_text().splitlines():
    if '=' in line and not line.strip().startswith('#'):
        k,v=line.split('=',1); env[k]=v.strip().strip('"')
prof=json.loads(Path('/etc/tproxy-server/profiles.json').read_text())['profiles'][0]['secret']
web=env.get('MGLA_WEB_PROXY_SECRET','')
print('env_web_eq_profile', web.lower()==prof.lower(), 'env_len', len(web), 'prof_len', len(prof))
print('env_web', web)
print('profile', prof)
PY
# tproxy admin if any
curl -sS -m 3 http://127.0.0.1:8091/ 2>/dev/null | head -c 200; echo
