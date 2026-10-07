# Mgla WS-релей + ИИ-прокси (mglabot)

На **mglabot.mooo.com**:
- `wss://…/apiws` — обход Telegram
- `https://…/mgla-ai/…` — прокси OpenRouter/Gemini (без VPN у клиента)
- `POST /mgla-ai/v1/admin/models` — приём списка моделей с mglahub (`X-Mgla-Sync-Token`)

**Статистика / панель / feature flags** — на `mglahub.mooo.com`
(`stats_server.py`, `hub_admin.py`). Список OpenRouter-моделей правится там и
пушится на mglabot; ключ `OPENROUTER_API_KEY` остаётся только на mglabot.

Клиент (`local.properties`):

```
MGLA_WS_RELAY_HOST=mglabot.mooo.com
MGLA_WS_RELAY_TOKEN=<тот же, что MGLA_WS_TOKEN в /opt/mgla-ws-relay/env>
MGLA_HUB_HOST=mglahub.mooo.com
MGLA_HUB_TOKEN=<тот же или отдельный токен хаба>
```

ИИ использует тот же хост/токен, что и обход. OpenRouter-ключ — только в `env` на mglabot.

---

## Шаг 1. SSH на сервер

```bash
ssh root@ВАШ_IP_ИЛИ_ХОСТ
```

---

## Шаг 2. Положить файлы релея

На своём ПК (из корня репозитория), подставьте хост:

```bash
scp -r Tools/mgla-ws-relay root@ВАШ_ХОСТ:/opt/mgla-ws-relay
```

Или на сервере вручную создайте `/opt/mgla-ws-relay/` и скопируйте туда:
- `relay.py`
- `requirements.txt`
- `mgla-ws-relay.service`
- `nginx-apiws.conf`

---

## Шаг 3. Python venv и зависимости

На сервере:

```bash
cd /opt/mgla-ws-relay
apt-get update
apt-get install -y python3 python3-venv python3-pip
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
```

---

## Шаг 4. Токен и systemd

```bash
# Сгенерируйте свой токен, например:
# openssl rand -hex 24

cat >/opt/mgla-ws-relay/env <<'EOF'
MGLA_WS_HOST=127.0.0.1
MGLA_WS_PORT=8766
MGLA_WS_TOKEN=<ваш-секретный-токен>
MGLA_AI_HOST=127.0.0.1
MGLA_AI_PORT=8768
OPENROUTER_API_KEY=sk-or-v1-ВАШ_КЛЮЧ
MGLA_HUB_SYNC_TOKEN=<тот же секрет, что на mglahub>
# Прокси feature flags (клиент → mglabot → mglahub), пока включён WS-релей:
MGLA_HUB_FEATURES_TOKEN=<токен клиента хаба, MGLA_WS_TOKEN на mglahub>
# MGLA_HUB_FEATURES_URL=https://mglahub.mooo.com/mgla-config/v1/features
EOF

cp /opt/mgla-ws-relay/mgla-ws-relay.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now mgla-ws-relay
systemctl status mgla-ws-relay --no-pager
```

В статусе должно быть `active (running)`, в логе — `listening on 127.0.0.1:8766`.

Логи:

```bash
journalctl -u mgla-ws-relay -f
```

---

## Шаг 5. Nginx: путь /apiws рядом с mini app

Найдите конфиг своего сайта и **внутрь** `server { ... }` вставьте содержимое `nginx-apiws.conf`
(или эквивалент с `proxy_pass http://127.0.0.1:8766` для `/apiws`).

```bash
nginx -t && systemctl reload nginx
```

---

## Шаг 6. Проверка с сервера

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Sec-WebSocket-Protocol: binary" \
  -H "X-Mgla-Token: <ваш-секретный-токен>" \
  "http://127.0.0.1:8766/apiws?dc=2"
```

Ожидается `101` (или хотя бы не `401`/`404`).

---

## Шаг 7. В клиенте Mgla

1. Пропишите `MGLA_WS_RELAY_HOST` / `MGLA_WS_RELAY_TOKEN` в `local.properties`.
2. Соберите APK.
3. **Настройки Mgla → Подключение**.
4. Включите **«Обход через WebSocket»**.

---

## Если не работает

| Симптом | Что проверить |
|--------|----------------|
| `401` / unauthorized | Токен на сервере ≠ токену в `local.properties` |
| `404` на /apiws | Не добавили location в nginx или reload не сделали |
| Релей не стартует | `journalctl -u mgla-ws-relay -e` |
| `dc connect failed` | С сервера нет доступа к `149.154.*:443` |
| Клиент «Нет токена релея» | Не задан `MGLA_WS_RELAY_TOKEN` при сборке |

Исходящий доступ с сервера к Telegram:

```bash
nc -vz 149.154.167.51 443
nc -vz 149.154.175.50 443
```

---

# Анонимная статистика клиента

Клиент раз в 6 часов отправляет `POST https://mglahub…/mgla-stats/v1/batch` с
`X-Mgla-Token` хаба. Приёмник — `stats_server.py` (stdlib Python, SQLite),
слушает `127.0.0.1:8767` на **mglahub**.

Feature flags: клиент периодически опрашивает флаги с `tg_id` / `tg_ids`
(не в stats batch). Маршрут зависит от обхода:
- WS-релей **включён** и не на паузе из‑за VPN → `POST https://mglabot…/mgla-ai/v1/features`
  (токен релея); `ai_proxy` проксирует на хаб.
- релей **выключен** или на паузе (VPN) → `POST https://mglahub…/mgla-config/v1/features`
  напрямую (токен хаба).

На mglabot в `env` нужны:
`MGLA_HUB_FEATURES_TOKEN=<токен клиента хаба>` и опционально
`MGLA_HUB_FEATURES_URL=https://mglahub.mooo.com/mgla-config/v1/features`.

Управление: `https://mglahub…/admin` (Basic Auth). Модели OpenRouter сохраняются
на хабе и пушатся на mglabot (`MGLA_BOT_AI_URL` + `MGLA_HUB_SYNC_TOKEN`).

Что приходит: случайный id установки (не связан с аккаунтом), версия Mgla/Telegram, модель
устройства, версия Android, язык; по дням — счётчики открытых экранов, изменённых настроек Mgla,
использования ИИ-функций (и упоров в дневной лимит), переходов из поиска/по ссылкам, запросы в
поиске по настройкам, не давшие результатов; снимок настроек Mgla (без токенов, хостов, стратегий
и прочих значений, которые могут идентифицировать). Не приходит: Telegram id, номер, контакты,
чаты, тексты сообщений. IP не пишется ни приёмником, ни nginx (`access_log off`).

Статистика обязательна и в клиенте не отключается.

## Установка

Файлы уже лежат в `/opt/mgla-ws-relay` после шага 2 (докопируйте `stats_server.py`,
`stats_report.py`, `mgla-stats.service`, `nginx-stats.conf`). Токен берётся из того же `env`.

```bash
cp /opt/mgla-ws-relay/mgla-stats.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now mgla-stats
journalctl -u mgla-stats -n 5 --no-pager   # listening on 127.0.0.1:8767
```

Nginx: строку `limit_req_zone` из `nginx-stats.conf` — в http-контекст, `location /mgla-stats/` —
внутрь `server { ... }` рядом с `/apiws`. Затем `nginx -t && systemctl reload nginx`.

Проверка: `curl -s https://<RELAY_HOST>/mgla-stats/v1/health` → `{"ok":true}`.

Необязательные переменные в `env`: `MGLA_STATS_PORT` (8767), `MGLA_STATS_DB`
(`/opt/mgla-ws-relay/stats.db`), `MGLA_STATS_RETENTION_DAYS` (400).

## Отчёт

```bash
python3 /opt/mgla-ws-relay/stats_report.py             # за 30 дней
python3 /opt/mgla-ws-relay/stats_report.py --days 7
python3 /opt/mgla-ws-relay/stats_report.py --name ai:  # все ИИ-счётчики
python3 /opt/mgla-ws-relay/stats_report.py --html /opt/mgla-ws-relay/report.html  # HTML-страница
```

HTML-отчёт — один самодостаточный файл (график активности, карточки, таблицы).

## Отчёт на сайте

Добавьте в `/opt/mgla-ws-relay/env` пароль и перезапустите приёмник:

```bash
echo "MGLA_STATS_REPORT_PASSWORD=$(openssl rand -hex 12)" >> /opt/mgla-ws-relay/env
grep REPORT_PASSWORD /opt/mgla-ws-relay/env   # запомните пароль
systemctl restart mgla-stats
```

Страница: `https://<RELAY_HOST>/mgla-stats/report` (логин `admin`, сменить —
`MGLA_STATS_REPORT_USER`). Строится заново при каждом открытии; вверху переключатель 1/7/30/90 дней.
Без пароля в `env` страница отключена (404).

## Ежедневный отчёт в Telegram

1. Создайте бота у @BotFather (или возьмите существующего), получите токен.
2. Напишите боту `/start`, затем узнайте свой chat id:
   `curl -s https://api.telegram.org/bot<ТОКЕН>/getUpdates` → `"chat":{"id":...}`.
3. Добавьте в `/opt/mgla-ws-relay/env`:

```
MGLA_STATS_BOT_TOKEN=<токен бота>
MGLA_STATS_CHAT_ID=<ваш chat id>
```

4. Проверка и включение (каждый день в 09:00 по времени сервера):

```bash
python3 /opt/mgla-ws-relay/stats_report.py --telegram   # должен прийти файл
cp /opt/mgla-ws-relay/mgla-stats-report.service /opt/mgla-ws-relay/mgla-stats-report.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now mgla-stats-report.timer
```

(`python3 ... --telegram` вручную не видит `env`; для ручной проверки выполните перед ней
`set -a; . /opt/mgla-ws-relay/env; set +a`.)

Разделы: DAU/WAU/MAU и удержание, ИИ-функции и упоры в лимит, экраны, изменения настроек,
доля включивших каждую функцию, поиск без результатов (спрос на то, чего нет), версии и устройства.
