# Mgla WS-релей на Германии (mglabot.mooo.com)

Клиент уже настроен на `wss://mglabot.mooo.com/apiws?dc=N`.
Мини-приложение бота не трогаем — добавляется только location `/apiws`.

Токен (должен совпадать с клиентом):

```
***REMOVED***
```

---

## Шаг 1. SSH на сервер в Германии

```bash
ssh root@ВАШ_IP_ИЛИ_ХОСТ
```

Убедитесь, что это тот же сервер, где уже крутится mini app на `mglabot.mooo.com`.

---

## Шаг 2. Положить файлы релея

На своём ПК (из корня репозитория Glass2), подставьте хост:

```bash
scp -r tools/mgla-ws-relay root@ВАШ_ХОСТ:/opt/mgla-ws-relay
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
cat >/opt/mgla-ws-relay/env <<'EOF'
MGLA_WS_HOST=127.0.0.1
MGLA_WS_PORT=8766
MGLA_WS_TOKEN=***REMOVED***
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

Найдите конфиг сайта `mglabot.mooo.com` (часто что-то вроде `/etc/nginx/sites-enabled/...`):

```bash
grep -R "mglabot.mooo.com" /etc/nginx/ -n
```

Откройте этот `server { ... }` и **внутрь него** (не вместо mini app) вставьте содержимое файла `nginx-apiws.conf`:

```nginx
location /apiws {
    proxy_pass http://127.0.0.1:8766;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Mgla-Token $http_x_mgla_token;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    proxy_buffering off;
}
```

Проверка и перезагрузка:

```bash
nginx -t && systemctl reload nginx
```

Mini app по-прежнему на своих URL. Релей — только `https://mglabot.mooo.com/apiws?...`.

---

## Шаг 6. Проверка с сервера

```bash
# Релей жив локально:
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Sec-WebSocket-Protocol: binary" \
  -H "X-Mgla-Token: ***REMOVED***" \
  "http://127.0.0.1:8766/apiws?dc=2"
```

Ожидается `101` (или хотя бы не `401`/`404`).

Снаружи (с вашего ПК или телефона через браузер не получится удобно — достаточно с сервера):

```bash
# nginx отдаёт тот же путь по HTTPS:
curl -sI "https://mglabot.mooo.com/apiws?dc=2" | head
```

Не пугайтесь `400`/`426` без WS-заголовков — главное, что не 404 и не HTML mini app.

---

## Шаг 7. В клиенте Mgla

1. Соберите APK с текущим кодом.
2. **Настройки Mgla → Обход блокировок**.
3. Включите **«Обход через релей mglabot.mooo.com»**.
4. Статус:
   - `Ожидание соединения…` → релей поднялся, Telegram ещё не прошёл;
   - `Работает · mglabot.mooo.com` → мост живой;
   - Telegram: `Подключено` → успех.

На сервере в `journalctl -u mgla-ws-relay -f` при подключении клиента появятся строки вида:

```
... open dc=2 (149.154.167.51)
... closed dc=2
```

---

## Если не работает

| Симптом | Что проверить |
|--------|----------------|
| `401` / unauthorized | Токен в `/opt/mgla-ws-relay/env` ≠ `***REMOVED***` |
| `404` на /apiws | Не добавили location в nginx или reload не сделали |
| Релей не стартует | `journalctl -u mgla-ws-relay -e` |
| `dc connect failed` | С сервера Германии нет доступа к `149.154.*:443` (фаервол исходящий) |
| Клиент «Подключение к прокси» | Логи релея пустые → TLS/DNS/nginx; логи есть, но сразу closed → проблема DC |

Исходящий доступ с сервера к Telegram (проверка):

```bash
nc -vz 149.154.167.51 443
nc -vz 149.154.175.50 443
```

Должно быть `succeeded` / `open`.
