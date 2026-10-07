# Mgla Hub — установка на mglahub.mooo.com
#
# Один сервер для всего:
#   - панель статистики (Basic Auth)
#   - приём аналитики `/mgla-stats/`
#   - WS-обход `/apiws`
#   - ИИ-прокси `/mgla-ai/` (ключ OpenRouter только в env)

## Какие файлы перенести

Скопируйте на сервер в `/opt/mgla-ws-relay/`:

```
Tools/mgla-ws-relay/stats_server.py
Tools/mgla-ws-relay/stats_report.py
Tools/mgla-ws-relay/mgla-stats.service
Tools/mgla-ws-relay/nginx-mglahub.conf
Tools/mgla-ws-relay/relay.py
Tools/mgla-ws-relay/mgla-ws-relay.service
Tools/mgla-ws-relay/requirements.txt
Tools/mgla-ws-relay/mgla-stats-report.service   # опционально, Telegram
Tools/mgla-ws-relay/mgla-stats-report.timer     # опционально
```

## DNS

A-запись: `mglahub.mooo.com` → IP этого сервера.

## Команды на сервере

```bash
# 1. Каталог
sudo mkdir -p /opt/mgla-ws-relay
# (залейте файлы выше в /opt/mgla-ws-relay/)

# 2. Секреты — один токен для обхода, stats и ИИ
sudo tee /opt/mgla-ws-relay/env >/dev/null <<'EOF'
MGLA_WS_TOKEN=СГЕНЕРИРУЙТЕ_ДЛИННЫЙ_СЕКРЕТ
MGLA_WS_HOST=127.0.0.1
MGLA_WS_PORT=8766
MGLA_STATS_HOST=127.0.0.1
MGLA_STATS_PORT=8767
MGLA_STATS_DB=/opt/mgla-ws-relay/stats.db
MGLA_STATS_RETENTION_DAYS=400
MGLA_STATS_REPORT_USER=admin
MGLA_STATS_REPORT_PASSWORD=СГЕНЕРИРУЙТЕ_ПАРОЛЬ_ПАНЕЛИ
OPENROUTER_API_KEY=sk-or-v1-ВАШ_КЛЮЧ_OPENROUTER
EOF
sudo chmod 600 /opt/mgla-ws-relay/env

# Токен для клиента (тот же, что MGLA_WS_TOKEN):
openssl rand -hex 24
# Пароль панели:
openssl rand -hex 12

# 3. Python venv (нужен для relay.py)
cd /opt/mgla-ws-relay
sudo apt-get update
sudo apt-get install -y python3 python3-venv python3-pip
python3 -m venv venv
./venv/bin/pip install -r requirements.txt

# 4. Статистика + ИИ-прокси
sudo cp /opt/mgla-ws-relay/mgla-stats.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mgla-stats
sudo journalctl -u mgla-stats -n 20 --no-pager
# Ожидаемо: listening on 127.0.0.1:8767, openrouter=yes

# 5. WS-релей обхода
sudo cp /opt/mgla-ws-relay/mgla-ws-relay.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mgla-ws-relay
sudo journalctl -u mgla-ws-relay -n 20 --no-pager
# Ожидаемо: listening on 127.0.0.1:8766

# 6. Nginx
# В /etc/nginx/nginx.conf внутри http { } один раз:
#   limit_req_zone $binary_remote_addr zone=mgla_stats:10m rate=6r/m;
#   limit_req_zone $binary_remote_addr zone=mgla_hub:10m rate=20r/m;
#   limit_req_zone $binary_remote_addr zone=mgla_ai:10m rate=4r/m;

sudo cp /opt/mgla-ws-relay/nginx-mglahub.conf /etc/nginx/sites-available/mglahub
sudo ln -sf /etc/nginx/sites-available/mglahub /etc/nginx/sites-enabled/mglahub
sudo nginx -t
sudo systemctl reload nginx

# 7. HTTPS
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d mglahub.mooo.com
sudo nginx -t && sudo systemctl reload nginx
```

## Обновление уже установленного хаба

```bash
# залить новые stats_server.py + nginx-mglahub.conf
# в /opt/mgla-ws-relay/env добавить строку:
#   OPENROUTER_API_KEY=sk-or-v1-...

# если relay ещё не крутится — шаги 3 и 5 выше
sudo systemctl restart mgla-stats
sudo systemctl enable --now mgla-ws-relay   # если ещё не был
sudo cp /opt/mgla-ws-relay/nginx-mglahub.conf /etc/nginx/sites-available/mglahub
# не забудьте zone=mgla_ai в nginx.conf
sudo nginx -t && sudo systemctl reload nginx
```

## Проверка

```bash
curl -s https://mglahub.mooo.com/mgla-stats/v1/health
# {"ok":true}

curl -s -X POST https://mglahub.mooo.com/mgla-ai/v1/chat \
  -H "Content-Type: application/json" \
  -H "X-Mgla-Token: ВАШ_ТОКЕН" \
  -d '{"message":"скажи ок","lang":"Russian"}'
# {"content":"..."}

# Панель (спросит логин/пароль):
# https://mglahub.mooo.com/
# логин: admin
# пароль: из MGLA_STATS_REPORT_PASSWORD
```

После переключения клиента на хаб старый релей на **mglabot** можно остановить:
`systemctl stop mgla-ws-relay` (на mglabot).

## Клиент (сборка)

В `local.properties` (не коммитить):

```
MGLA_WS_RELAY_HOST=mglahub.mooo.com
MGLA_WS_RELAY_TOKEN=<тот же, что MGLA_WS_TOKEN на сервере>
```

`OPENROUTER_API_KEY` в клиент **не** кладите — ключ только в `/opt/mgla-ws-relay/env` на хабе.

Пересоберите приложение. Обход: `wss://mglahub.mooo.com/apiws`.  
Статистика: `https://mglahub.mooo.com/mgla-stats/v1/batch`.  
ИИ: `https://mglahub.mooo.com/mgla-ai/v1/chat`.

Клиент пинит SPKI дефолтного хаба (`MglaWsConfig.RELAY_SPKI_SHA256_BASE64`).
После смены ключа сертификата обновите константу:

```bash
openssl x509 -in /etc/letsencrypt/live/mglahub.mooo.com/cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl base64
```

В `nginx-mglahub.conf` для 443 стоит `listen … ssl` **без** `http2` —
сырой WS Upgrade клиента иначе может не дойти до `relay.py`.

## Если переносите старую БД

```bash
# со старого сервера:
scp root@OLD:/opt/mgla-ws-relay/stats.db /opt/mgla-ws-relay/stats.db
sudo systemctl restart mgla-stats
```

## Telegram-отчёт (по желанию)

Как раньше: `MGLA_STATS_BOT_TOKEN` + `MGLA_STATS_CHAT_ID` в `env`, затем
`mgla-stats-report.timer`. Код бота не удалялся.
