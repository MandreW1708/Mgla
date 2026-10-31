# Российский вход (белые списки / Билайн)

## Почему одного DE/FI мало

На части мобильных сетей РФ (особенно Билайн/Tele2 в регионах) ТСПУ включает
**IP-whitelist**: TLS ClientHello до **зарубежного** VPS дропается ещё до SNI.
Все текущие ноды Mgla (mglabot / mglahub / fin.mgla) сидят в **AS216127 (DE/FI)** —
при полном белом списке прямой обход **физически невозможен**, какой бы ни был
путь `/cdn/...` или decoy.

Нужна цепочка:

```text
Клиент (Билайн) → RU whitelisted IP:443 (Yandex CDN / Selectel / Timeweb)
                 → origin DE1/DE2/FI (WS relay)
                 → Telegram DC
```

## Что уже на origin (DE1 / DE2 / FI) — 2026-10-31

- Пул WS-путей: `/cdn/v3/stream`, `/api/realtime`, `/_next/webpack-hmr`,
  `/socket/websocket`, `/ws/v1/live` (+ legacy `/apiws`)
- `relay.py` принимает все маркеры; nginx location’ы на всех трёх нодах
- Клиент: ротация путей (до 3 на хост), emergency DE1↔DE2 с правильными токенами,
  Chrome Android 131 + Sec-Fetch/Client-Hints, meta `c=app` без маркера `mgla`
- Decoy landing на origin

Эти меры помогают при **чёрном списке / заморозке / DPI по пути**, но
**не** заменяют RU-вход при полном whitelist на Билайне.

## P0: edge в РФ — готовый nginx

Файл: `ru-front-nginx.conf.example`

1. VPS Selectel / Timeweb / Yandex с IPv4 из белого списка оператора.
2. Домен **без** `mgla` в SNI (например `rt.<ваш-домен>`), LE-сертификат.
3. Скопировать пример, поменять `server_name` и upstream (DE1 / DE2 / FI).
4. Origin Host / SNI к релею оставить `mglabot.mooo.com` (или хаб/фин).
5. В клиенте добавить edge-хост **первым** в `MGLA_WS_RELAY_HOSTS` /
   `MGLA_HUB_RELAY_HOSTS` (pin: либо отдельный SPKI edge, либо pin-only после
   выпуска сертификата — отдельный маленький PR в `MglaWsConfig`).

### Альтернатива: Yandex Cloud CDN

1. CDN origin = `https://mglabot.mooo.com`, host header = origin.
2. Клиент ходит на CDN hostname:443.
3. На origin nginx: trust `X-Forwarded-For` только с префиксов Yandex CDN.

CDN проще по сертификату, дороже по трафику; VPS-edge дешевле и полностью
контролируем path pool.

## Поведенческий стелс (уже / дальше)

| Сигнал ТСПУ | Контрмера |
|-------------|-----------|
| L3 drop foreign IP | RU edge / CDN (обязательно для Билайна) |
| SNI blacklist `*mgla*` | нейтральный edge SNI; sslip только emergency |
| Path fingerprint `/apiws` | пул CDN/SPA путей |
| JA3 «прокси» | Android Conscrypt + Chrome UA/Client-Hints |
| Много коротких TCP | sticky host + меньше path-retries при TLS fail |
| Долгий binary WS | маскировка под realtime/HMR path |

## Нужно от вас

- Аккаунт Yandex Cloud / Selectel / Timeweb **или** уже whitelisted VPS + домен
- После появления edge — скажите hostname: вшьём в `BuildConfig` / remote config
  первым кандидатом для DE1 (и отдельно для DE2/FI при необходимости)
