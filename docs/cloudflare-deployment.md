# Cloudflare Workers + приватный backend на VPS

Публичный сайт: **https://my-notes.pop-arthur-c189baa0.workers.dev**.
Backend и PostgreSQL работают на VPS `<VPS_IP>`. Сайт обращается к API того же origin;
Worker передаёт API/health через HTTP VPC Service и именованный Cloudflare Tunnel.
Прямой публичный HTTP-доступ к backend и PostgreSQL закрыт. Сертификат на IP не используется.

## Ресурсы и конфигурация

- Worker `my-notes`; стабильный production workers.dev URL, preview URLs выключены.
- Tunnel `my-notes-vps`, ID `367676dd-8c7d-4e89-a679-22e467afb6dd`.
- VPC Service `my-notes-backend`, ID `01a1b294-18ab-7ec2-8354-ee0273f8a82c`:
  только HTTP `127.0.0.1:8080` со стороны коннектора, binding `BACKEND`.
- `cloudflared` установлен из официального apt-репозитория; служба `my-notes-tunnel.service`
  запускается автоматически. Token: `/etc/my-notes/tunnel.token`, root:root, mode 600;
  systemd передаёт его через LoadCredential, без значения в argv или environment.
- Backend: `/opt/my-notes/backend`, Compose `compose.vps.yaml`;
  env `/etc/my-notes/production.env`, mode 600. PostgreSQL volume постоянный.
- `APP_PUBLIC_ORIGIN` равен адресу сайта. Google callback выводится из него автоматически.
- `EDGE_PROXY_SECRET` хранится в серверном env и Wrangler secret с тем же именем.
  SMTP/Google secrets находятся только на VPS; они не передаются Worker или браузеру.
- В Git находятся только идентификаторы ресурсов и шаблоны, без token/паролей/секретов.

Workers VPC находится в open beta. Обычный fetch из Worker напрямую к IP не используется.
Транспорт до VPS шифруется Tunnel; участок cloudflared → backend идёт по loopback.
Worker имеет привязку к одному VPC Service, не к сети или PostgreSQL.
Платные ресурсы, домен, R2 и KV не создавались.

## Маршрутизация и доверие

`/api`, `/api/*` и `/actuator/health` обслуживает фиксированный VPC transport.
Остальные страницы обслуживает Next через OpenNext. OAuth start/callback остаются
узкими Next handlers и используют тот же VPC binding; локально — BACKEND_ORIGIN.
Нельзя передать произвольный upstream URL через path/query/body.

Worker сохраняет cookie, CSRF, статусы и отдельные Set-Cookie, не следует OAuth
редиректам на сервере и не повторяет изменяющие запросы. API и страницы имеют no-store;
статика обслуживается assets binding. Ошибки транспорта — безопасный 502 Problem Details,
ошибки browser navigation — существующая HTML-страница входа.

Worker перезаписывает собственные proxy-заголовки и удаляет forwarded-заголовки.
Backend принимает числовой IP клиента только с правильным EDGE_PROXY_SECRET;
сравнение секрета constant-time. Без настройки секрета, например локально, лимиты
по-прежнему используют socket IP и игнорируют клиентские proxy-заголовки.
Публичный status-only GET health не требует edge-секрета. Cookie остаётся Secure,
HttpOnly, SameSite=Lax; CORS закрыт и CSRF обязателен.

Worker observability выключен, cloudflared не записывает request URLs в журнал.
Не включать debug/body logging, wrangler tail или стороннюю аналитику с query auth/email URL.

## Сборка, тесты и обновление Worker

Требуется Node 24 LTS. Все версии закреплены lockfile.

```sh
cd frontend
npm ci
npm run lint
npm run typecheck
npm test
npm run worker:build
npm run test:worker
npm run worker:check
npm run worker:deploy
```

`test:worker` проверяет настоящий workerd с локальным тестовым upstream: страницы,
API, cookie/CSRF, защиту IP, безопасный 502 и OAuth navigation. Production config
не содержит тестовых bindings. `worker:check` выполняет dry-run без публикации.
`worker:deploy` повторно собирает приложение и запрещает placeholder VPC ID.
Локальный `worker:preview` требует настоящего доступного VPC binding, авторизации и
EDGE_PROXY_SECRET в игнорируемом `.dev.vars` (шаблон `.dev.vars.example`);
для полностью автономной проверки предназначен `test:worker`.

Wrangler авторизация — `npx wrangler login --device`. Account/Service IDs находятся
в `wrangler.jsonc`. Для другого аккаунта сначала создать собственные Tunnel/VPC,
задать account ID и service ID; не переиспользовать чужие идентификаторы.

После изменения EDGE_PROXY_SECRET обновить значение и в серверном env, и командой
`npx wrangler secret put EDGE_PROXY_SECRET` (ввод без эха). Не передавать значение
аргументом командной строки и не печатать серверный env. Пересоздать backend через
`my-notes-compose up -d --no-deps --wait --wait-timeout 300 backend`; затем проверить CSRF
через публичный сайт. Не делать вывод о совпадении секретов только по health.

После deploy проверить публичные `/login`, `/actuator/health`, `/api/auth/csrf`, 401
защищённых API, cookie flags, отсутствие кеширования и отказ POST без CSRF.
`npm run test:public` проверяет опубликованный сайт в Chromium без создания аккаунтов.
`npx wrangler deployments list` показывает версии; для отката использовать
`npx wrangler rollback <VERSION_ID>`. Откат Worker не откатывает backend или миграции.

## VPS, Tunnel и резервные копии

```sh
ssh root@"<VPS_IP>"
my-notes-compose ps
curl -fsS http://127.0.0.1:8080/actuator/health
systemctl status my-notes-tunnel
curl -fsS http://127.0.0.1:2000/ready
systemctl restart my-notes-tunnel
my-notes-backup
my-notes-restore-check /var/backups/my-notes/ИМЯ.dump
```

На этом VPS исходящие соединения к Gmail SMTP по IPv4 не устанавливаются; по IPv6 работают.
Поэтому production Compose включает IPv6 в default bridge network и задаёт JVM
`-Djava.net.preferIPv6Addresses=true`. IPv4 сохраняется; backend по-прежнему опубликован
только на `127.0.0.1:8080`, PostgreSQL не имеет опубликованных портов. IPv6-подсеть
выделяется Docker автоматически как ULA и использует NAT, не публичную адресацию контейнеров.
При переносе на другой VPS проверить SMTP именно из контейнера. Host-only проверка недостаточна.
Для IPv6 egress у хоста должны сохраняться рабочий маршрут и RA при forwarding
(на текущем сервере eth0.accept_ra=2). Firewall не отключать и TLS-проверки не ослаблять.
Изменение enable_ipv6 требует пересоздания сети: сначала backup, затем compose down **без -v**
и up. Именованный PostgreSQL volume сохраняется.

Порт метрик 2000 слушает только loopback. Служба Tunnel отключает stdout/stderr,
чтобы ошибки проксирования не записывали OAuth query; готовность проверяется через
`/ready`, а запуск/завершение процесса — через systemd. После обновления cloudflared
перезапустить службу и проверить публичный CSRF.

При остановке Tunnel API должен возвращать безопасный 502; после запуска — восстановиться.
БД и JSESSIONID переживают пересоздание backend; authenticated restart дополнительно
проверяет PostgreSQL integration test. Локальные бэкапы проверены восстановлением
во временную БД. Внешнее хранилище бэкапов пока не подключено.
Подробные инструкции обновления backend и восстановления — [VPS deployment](vps-deployment.md).

## Google: оставшийся внешний шаг

Google выключен: пользователь пока не создал Google Cloud project/Web client.
Для завершения:

1. В https://console.cloud.google.com/ создать проект `my-notes` без подключения billing.
2. В Google Auth Platform настроить Branding: имя приложения, support email и developer contact.
3. Audience: External / Testing; добавить собственный Google email в test users.
   Для входа нужны только базовые scopes openid, email, profile.
4. Clients → Create client → Web application, имя `my-notes-web`.
   JavaScript origin: `https://my-notes.pop-arthur-c189baa0.workers.dev`.
   Authorized redirect URI — точно:
   `https://my-notes.pop-arthur-c189baa0.workers.dev/login/oauth2/code/google`.
5. На VPS добавить в `/etc/my-notes/production.env` GOOGLE_CLIENT_ID и GOOGLE_CLIENT_SECRET,
   установить GOOGLE_OAUTH_ENABLED=true. Не присылать secret в чат и не помещать в Git/Worker.
   GOOGLE_REDIRECT_URI можно не задавать — он выводится из APP_PUBLIC_ORIGIN.
6. Пересоздать backend и выполнить настоящий Google-вход, CRUD задач, reload и logout.
   Проверить второго пользователя и сохранение сессии после restart. Пока эти шаги не выполнены,
   настоящий Google-вход и authenticated сценарии публичного сайта не считаются проверенными.

До настройки Google start корректно переводит на `/login?error=google_unavailable`;
callback — на общий `/login?error=oauth_failed`.

## SMTP

7 ноября 2026 Gmail SMTP включён на VPS: MAIL_ENABLED=true, SMTP_HOST=smtp.gmail.com,
SMTP_PORT=587, SMTP_TLS_MODE=starttls. MAIL_FROM совпадает с SMTP_USERNAME.
Используется пароль приложения Gmail; он сохранён только в серверном env с правами 600,
в Git не попадает. Нельзя использовать обычный пароль аккаунта или отключать проверку TLS-сертификата.

Проверены STARTTLS, сертификат и авторизация LOGIN. Исправлена IPv6-связность сети Docker;
аутентификация JavaMail из того же backend-образа отдельно проверена по IPv6. Backend успешно пересоздан и прошёл
публичные health/CSRF проверки. Техническое UTF-8 multipart-письмо отправлено через Gmail,
получение подтверждено пользователем. Первоначальная host-only проверка не обнаружила
недоступный IPv4 SMTP из контейнера; этот пробел устранён проверкой JavaMail и сети контейнера.
После исправления повторная отправка подтверждения через публичный backend завершилась HTTP 202
с реальной отправкой JavaMail. Подтверждение по ссылке и восстановление → смена пароля
на публичном сайте ещё требуют пользовательской проверки через полученные письма. Локальные сквозные сценарии с Mailpit проходили ранее.

При изменении настроек SMTP обновить только `/etc/my-notes/production.env` и выполнить
`my-notes-compose up -d --no-deps --wait --wait-timeout 300 backend`. Пароль не выводить
в логи или чат. На случай сбоя предыдущая конфигурация сохранена на VPS в закрытой
резервной копии env. Sender не подменять no-op, production-аккаунты автоматически не подтверждать.

## Проверено 6–7 ноября 2026

- Backend: `./mvnw clean verify`, 90 тестов, PostgreSQL Testcontainers, без пропусков.
- Frontend: 38 unit-тестов, 24 UI-сценария и 2 сквозных сценария desktop/mobile
  с настоящими Spring Boot/PostgreSQL/Mailpit; lint/typecheck/format и сборка прошли.
- Настоящий workerd с тестовым binding: страницы, API/health, PATCH body, cookie/CSRF,
  запрет произвольного upstream, безопасный 502 и OAuth start/callback.
- Публичный Chromium: HTML, ошибка входа несуществующего аккаунта, Secure/HttpOnly/Lax cookie,
  health 200, me 401, CSRF и отказ POST без CSRF. Почта возвращает предусмотренный 503,
  отключённый Google переводит на безопасную страницу ошибки.
- Полная перезагрузка VPS: Docker, PostgreSQL, backend и Tunnel запустились автоматически;
  публичный API восстановился, существующая JDBC-сессия и её CSRF сохранились.
- Остановка Tunnel: API отдаёт безопасный 502, frontend доступен; после старта связь восстановилась.
- Свежий pg_dump успешно восстановлен в отдельную временную БД, рабочие данные не изменялись.

Настоящий Google-вход и authenticated CRUD на публичном сайте остаются непроверенными
до настройки OAuth credentials. Статус SMTP обновлён в разделе выше. Полные публичные сценарии email-подтверждения и сброса пароля пока не проверены.


## Resend (HTTPS вместо SMTP)

На VPS можно выбрать `MAIL_PROVIDER=resend` при `MAIL_ENABLED=true`.
Задайте `RESEND_API_KEY` (отдельный ключ с правом отправки),
`AUTH_FROM_EMAIL` (например `My Notes <noreply@your-domain.example>`) и
`APP_PUBLIC_ORIGIN=https://my-notes.pop-arthur-c189baa0.workers.dev`.
Домен отправителя должен быть подтверждён в Resend. Ключ хранится только
в окружении backend, не в Worker и не в Git. Maven не загружает `.env` автоматически.

Адаптер обращается к https://api.resend.com/emails с тайм-аутами 5 секунд,
без редиректов и автоматических повторов отправки. Используются существующие
UTF-8 text/html шаблоны, ссылки `/verify-email?token=…` и `/reset-password?token=…`.
Токены, PostgreSQL, сессии и API остаются прежними; отправка выполняется после commit.
Ошибки сохраняют коды `mail_delivery_unavailable` / `mail_delivery_failed`.
Без ключа/отправителя включённый Resend останавливает запуск с безопасной ошибкой.
`MAIL_ENABLED=false` отключает sender и сохраняет предусмотренный 503.
Для Mailpit и прежнего SMTP используйте `MAIL_PROVIDER=smtp` (по умолчанию).

Переключение production ожидает нового подтверждённого отправителя и ключа;
реальная доставка через Resend пока не проверена.
