# Mgla site (`mgla.home.mooo.com`)

Публичный сайт клиента + CMS-конструктор для разделов **Инструкция** и **Поддержать**.

Работает на том же хосте, что и hub (`194.58.33.22`), но **отдельным nginx vhost** и сервисом `mgla-site` на `127.0.0.1:8770`.  
Конфиг `mglahub` / `/apiws` / stats / tproxy **не изменяется**.

## URL

- Сайт: `https://mgla.home.mooo.com/`
- Premium: `/#premium`
- Политика конфиденциальности: `/privacy`
- Пользовательское соглашение: `/terms`
- Инструкция: `/guide`
- Поддержать: `/support`
- CMS: `/admin` (логин из `/opt/mgla-site/env`)

## Deploy

```bash
# с машины разработки: скопировать Tools/mgla-site на сервер, затем:
bash /opt/mgla-site/deploy.sh
# DNS A-запись mgla.home.mooo.com → 194.58.33.22, затем:
certbot --nginx -d mgla.home.mooo.com
```

## Важно

Перед `nginx reload` всегда `nginx -t`. Не редактировать `sites-enabled/mglahub`.
