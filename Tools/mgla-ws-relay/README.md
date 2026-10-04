# Mgla WS-релей

Клиент ходит на `wss://<RELAY_HOST>/apiws?dc=N` (прямой `kws*` в блокировках обычно мёртв).
Мини-приложение бота не трогаем — добавляется только location `/apiws`.

Токен должен совпадать с клиентом. В клиенте задаётся в корневом `local.properties`
(файл **не** в git, см. `local.properties.example`):

```
MGLA_WS_RELAY_HOST=your-relay.example.com
MGLA_WS_RELAY_TOKEN=<сгенерируйте длинный случайный секрет>
```

На сервере — тот же токен в `/opt/mgla-ws-relay/env` (`MGLA_WS_TOKEN`).
Никогда не коммитьте реальный токен в репозиторий.

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
