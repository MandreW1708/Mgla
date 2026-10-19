# Mgla Hub — установка на mglahub.mooo.com
#
# Хаб (этот хост):
#   - панель статистики + управление (Basic Auth): `/`, `/admin`
#   - приём аналитики `/mgla-stats/`
#   - feature flags для клиента `/mgla-config/v1/features`
#   - источник правды для списка OpenRouter-моделей (push на mglabot)
#
# Рабочий ИИ и WS-обход — на **mglabot** (`ai_proxy.py`, `relay.py`).
# Ключ OpenRouter на хабе не нужен.

## Какие файлы перенести

Скопируйте на сервер в `/opt/mgla-ws-relay/`:

```
Tools/mgla-ws-relay/stats_server.py
Tools/mgla-ws-relay/stats_report.py
Tools/mgla-ws-relay/hub_admin.py
Tools/mgla-ws-relay/mgla-stats.service
Tools/mgla-ws-relay/nginx-mglahub.conf
Tools/mgla-ws-relay/mgla-stats-report.service   # опционально, Telegram
Tools/mgla-ws-relay/mgla-stats-report.timer     # опционально
```

На **mglabot** отдельно: `ai_proxy.py`, `relay.py`, `mgla-ai-proxy.service`,
`mgla-ws-relay.service`, `nginx-apiws.conf` (см. README.md).

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
MGLA_STATS_HOST=127.0.0.1
MGLA_STATS_PORT=8767
MGLA_STATS_DB=/opt/mgla-ws-relay/stats.db
MGLA_STATS_RETENTION_DAYS=400
MGLA_STATS_REPORT_USER=admin
MGLA_STATS_REPORT_PASSWORD=СГЕНЕРИРУЙТЕ_ПАРОЛЬ_ПАНЕЛИ
MGLA_HUB_SYNC_TOKEN=СГЕНЕРИРУЙТЕ_СЕКРЕТ_HUB_BOT
MGLA_BOT_AI_URL=https://mglabot.mooo.com
EOF
sudo chmod 600 /opt/mgla-ws-relay/env

# Токен для клиента (тот же, что MGLA_WS_TOKEN):
openssl rand -hex 24
# Пароль панели:
openssl rand -hex 12
# Sync-токен hub→bot (тот же в env на mglabot):
openssl rand -hex 24

# 3. Статистика + панель управления (stdlib Python, venv не обязателен)
sudo cp /opt/mgla-ws-relay/mgla-stats.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mgla-stats
sudo journalctl -u mgla-stats -n 20 --no-pager
# Ожидаемо: listening on 127.0.0.1:8767

# 4. Nginx
# В /etc/nginx/nginx.conf внутри http { } один раз:
#   limit_req_zone $binary_remote_addr zone=mgla_stats:10m rate=6r/m;
#   limit_req_zone $binary_remote_addr zone=mgla_hub:10m rate=20r/m;
#   limit_req_zone $binary_remote_addr zone=mgla_ai:10m rate=4r/m;

sudo cp /opt/mgla-ws-relay/nginx-mglahub.conf /etc/nginx/sites-available/mglahub
sudo ln -sf /etc/nginx/sites-available/mglahub /etc/nginx/sites-enabled/mglahub
sudo nginx -t
sudo systemctl reload nginx

# 5. HTTPS
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d mglahub.mooo.com
sudo nginx -t && sudo systemctl reload nginx
```

## Обновление уже установленного хаба

```bash
# залить: stats_server.py hub_admin.py stats_report.py nginx-mglahub.conf
# в /opt/mgla-ws-relay/env добавить:
#   MGLA_HUB_SYNC_TOKEN=...
#   MGLA_BOT_AI_URL=https://mglabot.mooo.com
# на mglabot в env — тот же MGLA_HUB_SYNC_TOKEN, плюс прокси флагов:
#   MGLA_HUB_FEATURES_TOKEN=<тот же, что MGLA_WS_TOKEN / клиентский токен на хабе>
#   MGLA_HUB_FEATURES_URL=https://mglahub.mooo.com/mgla-config/v1/features   # опционально
# затем:
#   systemctl restart mgla-ai-proxy

sudo systemctl restart mgla-stats
sudo cp /opt/mgla-ws-relay/nginx-mglahub.conf /etc/nginx/sites-available/mglahub
sudo nginx -t && sudo systemctl reload nginx
```

## Ultra-обход: ИИ напрямую на хабе

Клиент с включённым Ultra ходит на `mglahub` не только за `/apiws`, но и за
`/mgla-ai/*` (модели, OpenRouter, Gemini), а features/stats/updates/auth —
нативными путями хаба (`/mgla-config`, `/mgla-stats`, …).

На хабе должен работать тот же `ai_proxy.py`, что на боте:

```bash
# ключи Plus/Ultra + entitlements localhost
# openrouter_models.json из админки
systemctl enable --now mgla-ai-proxy   # :8768
# nginx location /mgla-ai/ → 127.0.0.1:8768
sudo systemctl restart mgla-stats      # dual-push моделей: local + mglabot
# mgla-ws-relay (:8766) не трогать без нужды
```

Обычный обход по-прежнему: Telegram + `/mgla-ai/*` через **mglabot**.

## Подписки (Premium / Premium+ / Ultra) — только mglahub

База подписчиков живёт **только на mglahub** в SQLite `stats.db`, таблица `subscriptions`.
Авто-обход на **mglabot** (`mgla-ws-relay`, mtproxy, tproxy) **не трогать**.

Длительность — **любое целое число дней** (1…3650). По `expires_at` подписка
снимается сама (и в админке, и в `POST /features`). Клиент показывает окончание
с точностью до минуты и отключает premium-функции.

### Что сделать на mglahub

```bash
# 1) Бэкап БД (обязательно)
sudo cp -a /opt/mgla-ws-relay/stats.db /opt/mgla-ws-relay/stats.db.bak.$(date +%Y%m%d%H%M%S)

# 2) Залить новые файлы хаба (НЕ relay.py / НЕ mtproxy)
sudo cp stats_server.py hub_admin.py hub_updates.py /opt/mgla-ws-relay/

# 3) Рестарт ТОЛЬКО stats (подписки + /admin + /features)
sudo systemctl restart mgla-stats
sudo systemctl status mgla-stats --no-pager

# Таблица (миграция колонок — при старте):
#   subscriptions(
#     tg_id, plan, duration_days, starts_at, expires_at, updated_at
#   )
```

### Выдать себе Ultra

Админка: `https://mglahub.mooo.com/admin/subscriptions`  
→ Telegram ID → Premium Ultra → длительность → Выдать.

### Проверка ответа features

```bash
curl -s -X POST https://mglahub.mooo.com/mgla-config/v1/features \
  -H "Content-Type: application/json" \
  -H "X-Mgla-Token: ТОКЕН_ХАБА" \
  -d '{"tg_id":1004685048}'
# ожидаемо: plans + expires (unix ms) + durations
```

Клиент с включённым обходом ходит через релей-прокси features — релей **только проксирует** тело, таблицу подписок не хранит. Рестарт `mgla-ws-relay` / `mgla-ai-proxy` для подписок **не нужен**.

### Чего НЕ делать

- не рестартить `mgla-ws-relay`, `mgla-mtproxy`, `mgla-tproxy`
- не менять `env` релея ради подписок
- не путать `update_supporters` (ранние APK) с `subscriptions` (тарифы)
- не выдавать план без `expires_at` — клиент отклонит платный доступ без срока

## Проверка

```bash
curl -s https://mglahub.mooo.com/mgla-stats/v1/health
# {"ok":true}

# Страница обновлений APK (Basic Auth, как /admin):
#   https://mglahub.mooo.com/admin/updates
# Клиент: POST /mgla-updates/v1/check , GET /mgla-updates/v1/apk
# Через релей: POST /mgla-ai/v1/update-check , GET /mgla-ai/v1/update-apk
# Статистика напрямую: POST /mgla-stats/v1/batch
# Через релей: POST /mgla-ai/v1/stats-batch

# Feature flags + plan напрямую на хаб:
curl -s -X POST https://mglahub.mooo.com/mgla-config/v1/features \
  -H "Content-Type: application/json" \
  -H "X-Mgla-Token: ТОКЕН_ХАБА" \
  -d '{"tg_id":123456789}'
# {"v":1,"disabled":[...],"plan":"free"|"premium"|"premium_plus"|"premium_ultra"}

# Тот же ответ через релей (когда клиент сидит на WS-обходе):
curl -s -X POST https://mglabot.mooo.com/mgla-ai/v1/features \
  -H "Content-Type: application/json" \
  -H "X-Mgla-Token: ТОКЕН_РЕЛЕЯ" \
  -d '{"tg_id":123456789}'

# Панель статистики: https://mglahub.mooo.com/
# Управление (flags + модели + подписки): https://mglahub.mooo.com/admin
# логин: admin / пароль: MGLA_STATS_REPORT_PASSWORD
```

Ручной push моделей на mglabot (хаб делает это сам при сохранении в `/admin`):

```bash
curl -s -X POST https://mglabot.mooo.com/mgla-ai/v1/admin/models \
  -H "Content-Type: application/json" \
  -H "X-Mgla-Sync-Token: ВАШ_SYNC_ТОКЕН" \
  -d '{"models":["nvidia/nemotron-3-super-120b-a12b:free"]}'
```

## Клиент (сборка)

В `local.properties` (не коммитить):

```
MGLA_WS_RELAY_HOST=mglabot.mooo.com
MGLA_WS_RELAY_TOKEN=<токен mglabot>
MGLA_HUB_HOST=mglahub.mooo.com
MGLA_HUB_TOKEN=<токен хаба; может совпадать с релеем>
```

`OPENROUTER_API_KEY` в клиент **не** кладите — ключ только на mglabot.

Пересоберите приложение (нужна одна сборка с poll feature flags).  
Обход + ИИ: `mglabot`. Статистика + flags: `mglahub`.

Клиент пинит SPKI хаба (`MglaWsConfig.HUB_SPKI_SHA256_BASE64`) и релея
(`RELAY_SPKI_SHA256_BASE64`). После смены ключа сертификата обновите константу:

```bash
openssl x509 -in /etc/letsencrypt/live/mglahub.mooo.com/cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl base64
```

## Если переносите старую БД

```bash
# со старого сервера:
scp root@OLD:/opt/mgla-ws-relay/stats.db /opt/mgla-ws-relay/stats.db
sudo systemctl restart mgla-stats
```

## Telegram-отчёт (по желанию)

Как раньше: `MGLA_STATS_BOT_TOKEN` + `MGLA_STATS_CHAT_ID` в `env`, затем
`mgla-stats-report.timer`. Код бота не удалялся.
