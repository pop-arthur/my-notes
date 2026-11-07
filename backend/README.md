# Backend

Слой хранения «Списка задач»: Java 21, Spring Boot 4.1.2, JPA / Hibernate через EntityManager, PostgreSQL 18, Flyway. Сборка через Maven Wrapper 3.9.11. Версии библиотек управляются Spring Boot; ArchUnit зафиксирован отдельно в `pom.xml`.

Реализованы JPA entities и интерфейсы репозиториев с единственными JPA-реализациями областей `tasks` и `identity`, включая пользователей, password credentials, внешние identities и email-токены. Реализованы бизнес-сервисы задач, регистрации, подтверждения адреса, проверки пароля, сброса пароля и обработки доверенной Google identity. Реализованы HTTP API, Google OIDC, JDBC-сессии, CSRF, rate limiting и редактирование задач. SMTP-адаптер реализован; для production требуется настройка почтового сервиса. Frontend находится в соседнем каталоге frontend/.

## Локальная база и запуск

Нужны JDK 21+ и работающий Docker. Команды выполнять из `backend/`:

```sh
cp .env.example .env
# Отредактировать локальный DB_PASSWORD. .env — доверенный shell-файл.
set -a
source .env
set +a
docker compose --profile mail up -d --wait
./mvnw verify
./mvnw spring-boot:run
```

`spring-boot:run` запускает HTTP-сервер на порту 8080, применяет миграции и проверяет JPA-схему. Профиль local отключает Secure для локального HTTP; в production Secure включён. Для сборки jar: `./mvnw package`. Проверки интеграции входят в `verify`, поэтому перед выпуском запускать именно его.

Параметры подключения:

| Переменная | Назначение |
| --- | --- |
| `DB_USERNAME` | Обязательный пользователь БД |
| `DB_PASSWORD` | Обязательный пароль БД |
| `DB_URL` | JDBC URL, по умолчанию `jdbc:postgresql://localhost:5432/my_notes` |
| `DB_PORT` | Локальный порт Compose, по умолчанию `5432`; при изменении обновить `DB_URL` |

Секреты не сохранять в Git. Compose публикует PostgreSQL только на loopback. Данные остаются в Docker volume при `docker compose down`; `down -v` удаляет их. Hibernate использует `ddl-auto=validate`, схему изменяет только Flyway. Для PostgreSQL 18 volume смонтирован на `/var/lib/postgresql`.

## Архитектура и контракты хранения

Внутри `tasks` и `identity` используется структура `http / service / repo`:

```text
tasks/                         identity/
  http/                          http/
  service/                       service/
  repo/                          repo/
    TaskRepository.java            UserRepository.java
    JpaTaskRepository.java         JpaUserRepository.java
    entity/TaskEntity.java          PasswordCredentialRepository.java
    exception/                     ExternalIdentityRepository.java
                                   EmailTokenRepository.java
                                   entity/
                                   exception/
shared/repo/InsertOperations.java
```

`http` предназначен для контроллеров и request/response DTO, `service` — для бизнес-логики и транзакций, `repo` — для запросов хранения. Направление зависимостей: `http → service → repo`. В http находятся контроллеры, DTO и security-интеграция; бизнес-сценарии — в service. SMTP-реализация находится в identity.service.mail.smtp.

На сущность приходится один интерфейс `*Repository` и одна реализация `Jpa*Repository` с `@Repository` и `EntityManager`. Сервисы зависят от интерфейсов; Spring внедряет единственную JPA-реализацию. Дополнительного Spring Data репозитория и отдельного слоя портов нет. Сервисам доступны JPA entities из `repo.entity`; отдельного domain-слоя и маппинга domain ↔ entity нет. Entities имеют конструкторы и getters, без публичных setters; изменения полей выполняются специализированными методами репозитория. Сервисы возвращают `service.result` без ссылок на entities; HTTP преобразует их в свои DTO и не вызывает repo напрямую. Общий insert helper находится в `shared.repo`.

- Создание использует `persist`, не `merge`: занятый ID не перезаписывает запись. Дубликаты дают `DuplicateUserException`, `DuplicateTaskException` и соответствующие именованные ошибки остальных репозиториев. Обрабатывать их за пределами завершившейся неуспешной транзакции.
- Репозитории принимают entities с готовыми UUID, нормализованными значениями и `Instant` от вызывающего service-кода. Нормализация email, валидация ввода, вычисление password/token hashes и создание UUID находятся вне repo-layer. БД дополнительно проверяет уникальность, внешние ключи и базовые ограничения.
- Email хранится в нижнем регистре без внешних пробелов. Password hash — строка кодировщика до 512 символов. Token hash — SHA-256 в виде 64 строчных hex-символов; исходный токен в БД не передаётся. `toString` entities credentials и токенов скрывает хеши.
- Все даты — `Instant` / PostgreSQL `timestamptz` (микросекундная точность БД). Скрытых JPA callbacks, генераторов ID и времени нет.
- Методы задач всегда принимают владельца; список отсортирован по `createdAt DESC, id DESC`. Неизвестная и чужая задача дают одинаковое отсутствие результата. Повторное удаление возвращает `false`. Установка completed обновляет переданный `updatedAt`, не меняя владельца, title и createdAt.
- Изменение credentials обновляет только существующую запись, не создаёт пароль Google-пользователю. Внешняя identity определяется парой issuer + subject; репозиторий не объединяет аккаунты по email.

## Транзакции и конкурентность

Обычные методы выполняются в `REQUIRED` транзакциях; чтение — `readOnly`. Они присоединяются к транзакции service-сценария, если она уже есть. Составные операции (создание пользователя + credentials, подтверждение email + погашение токена, сброс пароля) service-слой оборачивает одной транзакцией.

`UserRepository.findByIdForUpdate` и `EmailTokenRepository.consume` требуют уже открытой **writable** транзакции (`MANDATORY`). Блокировка пользователя удерживается до commit/rollback. Для операций замены/отзыва токенов service-код сначала блокирует строку пользователя: это сериализует конкурентные выдачи токенов одного пользователя.

Bulk UPDATE/DELETE предварительно выполняют flush и затем очищают persistence context, чтобы последующее чтение не возвращало устаревшие entities. Ранее полученные ссылки после таких операций становятся detached; сервис должен использовать актуальные результаты репозитория.

Погашение токена выполняет один условный UPDATE по hash, назначению, отсутствию consumedAt и `expiresAt > now`: ровно один конкурентный запрос может получить успех. При rollback токен остаётся доступным. Отзыв отмечает consumedAt у всех непогашенных токенов выбранного пользователя и назначения, включая истёкшие; отдельной семантики публичной ошибки для отозванного токена нет. Отзыв сессий при сбросе пароля будет реализован в слое аутентификации.

## Бизнес-сервисы

| Сервис | Операции |
| --- | --- |
| `TaskService` | create, list, get, update, setCompleted, delete — для подтверждённого владельца |
| `RegistrationService` | register — нейтральный результат, неподтверждённый пользователь + credentials + токен |
| `EmailVerificationService` | verify, resend — подтверждение адреса и замена ссылки |
| `PasswordAuthenticationService` | authenticate — проверка пароля и подтверждения, без создания сессии |
| `PasswordRecoveryService` | requestReset, reset — смена пароля и увеличение auth version |
| `GoogleIdentityService` | authenticate — поиск/создание по доверенным issuer + subject |
| `CurrentUserService` | requireVerified — безопасный профиль подтверждённого пользователя |

`EmailTokenService` — внутренний координатор, его payload с исходным токеном доступен только почтовой границе, не HTTP. `TaskResult`, `UserResult`, `AuthenticationResult` не содержат credentials и email-токенов. AuthenticationResult содержит внутреннюю auth version для слоя сессий; её не следует добавлять в публичный User DTO.

Сервисы получают Clock и UuidGenerator через DI. Время сохраняется в UTC с микросекундной точностью. Email очищается по краям и приводится к нижнему регистру через Locale.ROOT без Gmail-преобразований. Заголовок — 1–200 Unicode code points после strip. Пароль при создании/сбросе — 12–128 code points, при входе — 1–128, без нормализации и усечения. Хранение пароля: PBKDF2 с идентификатором `{pbkdf2@SpringSecurity_v5_8}`. При отсутствии credentials вход выполняет проверку dummy hash и выдаёт ту же ошибку, что неверный пароль.

GoogleIdentityService не проверяет JWT и не начинает OAuth flow. Он принимает VerifiedGoogleIdentity только после доверенной проверки Google ID token в HTTP/security-интеграции. Issuer `accounts.google.com` приводится к `https://accounts.google.com`; другие issuer отклоняются. Требуется подтверждённый email. Аккаунты по совпадению email не объединяются, email существующей Google identity автоматически не меняется.

### Транзакции identity и граница почты

Регистрация, первый Google-вход и выдача писем через resend/requestReset выполняются в REQUIRES_NEW. Они коммитятся независимо от внешней транзакции вызывающего кода; конфликт создания обрабатывается уже после rollback. Подтверждение и reset используют REQUIRED и откатываются вместе с транзакцией вызывающего сценария. При блокировке сначала захватывается пользователь, затем изменяется токен; состояние пользователя после ожидания lock перечитывается из БД.

SMTP-реализация IdentityMailSender включается MAIL_ENABLED=true. По умолчанию флаг false и sender bean отсутствует: register, resend и requestReset явно завершаются MailDeliveryUnavailableException **до обращения к аккаунтам или записи в БД**. Остальные сервисы и приложение могут запускаться. Для разработки есть Mailpit, для production — JavaMailSender с реальным SMTP. Перехватчики отдельных тестов остаются только в test sources. Исходные токены не возвращаются из публичных бизнес-сценариев и не печатаются через toString.

SMTP sender получает IdentityMail (адрес, назначение, исходный токен) только после успешного commit. Если sender бросит ошибку, данные уже сохранены, возвращается безопасная MailDeliveryFailureException без причины, способной раскрыть содержимое письма. Повторный resend/requestReset заменяет ссылку. Outbox и гарантированной доставки пока нет; настройка почты будет отдельным этапом.

Ссылки подтверждения действуют 24 часа, сброса — 30 минут. Истечение проверяется строго (`expiresAt > now`), токен одноразовый; хранятся только SHA-256 hashes случайных 32-байтовых Base64 URL токенов.

### auth_version

Миграция V2 добавляет users.auth_version (изначально 0). Успешный reset увеличивает её в одной транзакции со сменой пароля и погашением токенов. AuthenticationResult возвращает снимок версии при проверке входа. HTTP/security проверяет версию через сервис на каждом аутентифицированном запросе, отклоняет устаревшие сессии и очищает cookie. После commit reset обработчик удаляет JDBC-сессии с более старой версией; свежие сессии сохраняются. Ошибка физической очистки не отменяет смену пароля, проверка версии остаётся обязательной защитой.

## Проверки

```sh
./mvnw verify
```

ArchUnit проверяет запрет зависимостей repo/entities от service/http, service от http и конкретных Jpa-репозиториев, а также http от repo. Отсутствие реализаций service/http допускается. Интеграционные тесты запускают отдельный PostgreSQL 18 через Testcontainers; локальная БД и переменные подключения им не нужны. Docker обязателен: при его отсутствии сборка падает, интеграционные тесты не пропускаются.

Проверяются миграции, Hibernate validation, маппинг, уникальность, внешние ключи, изоляция задач, rollback, блокировка пользователя, бизнес-валидация, цепочки регистрации/подтверждения/сброса и конкурентные выдача/погашение токенов и входы. Дополнительно проверяются HTTP-аутентификация, CSRF, сессии после перезапуска, отзыв сессий, PATCH и локальный подписывающий OIDC-провайдер с JWKS. Обращения к реальному Google в тестах нет. Startup timeout PostgreSQL — 180 секунд; отсутствие Docker приводит к ошибке, не пропуску.

- [Правила разработки](../AGENTS.md)
- [Требования и сценарии](../docs/task-analysis.md)
- [Контракт API](../docs/openapi.yaml)

## HTTP API и локальная проверка

Контракт: [OpenAPI 0.2.0](../docs/openapi.yaml). API использует один origin, CORS закрыт. Для будущего dev-frontend использовать proxy на backend; UI сейчас не создаётся. Callback Google резервирует frontend-пути `/` и `/login`, страницы этих путей пока отсутствуют.

```sh
curl -i http://localhost:8080/actuator/health
curl -i -c /tmp/my-notes-cookies http://localhost:8080/api/auth/csrf
```

Из ответа CSRF взять token и передавать его в `X-CSRF-TOKEN`, сохраняя cookie через curl `-b/-c`. Это обязательно и для анонимных POST регистрации/входа. После login/logout/reset получить новый токен. Успешный JSON login возвращает User DTO и меняет ID сессии. `GET /api/auth/me` без входа даёт 401 Problem Details, без HTML-редиректа.

По умолчанию MAIL_ENABLED=false: register/resend-verification/forgot-password возвращают 503 `mail_delivery_unavailable`. Для обычного использования включить SMTP по инструкции ниже. Fake production-аккаунты и API-выдача токенов не используются; реальная SMTP-цепочка проверяется интеграционными тестами с Mailpit. Ошибка настроенного sender после commit даёт `mail_delivery_failed`, данные уже сохранены и восстановление доступно повторным запросом письма.

PATCH `/api/tasks/{id}` поддерживает `{ "title": "Новое название" }`, `{ "completed": true }` или оба поля. Пропуск сохраняет значение; пустой объект, null, неизвестные поля и неверные типы дают 400. Обновление атомарно и ограничено владельцем. Идентификатор владельца в JSON не принимается.

### PostgreSQL-сессии

V3 создаёт стандартные SPRING_SESSION и SPRING_SESSION_ATTRIBUTES из схемы управляемой Spring Session JDBC 4.1.1. Автоинициализация таблиц выключена. Браузер хранит JSESSIONID, HttpOnly, SameSite=Lax, Path=/; Secure по умолчанию, профиль local/test разрешает отключение. Тайм-аут бездействия — 30 минут, очистка истёкших записей — раз в минуту. Сессия сохраняется после перезапуска приложения на той же БД.

Logout закрывает только текущую сессию. Reset меняет auth_version и отзывает старые сессии. Если reset выполнен из сессии другого пользователя, она остаётся открытой, но её CSRF обновляется. В БД хранится только сериализуемая локальная identity; Google tokens и пароли туда не записываются.

### Google OIDC

По умолчанию вход выключен и `/oauth2/authorization/google` даёт 503 `google_unavailable`. Для включения:

```sh
export GOOGLE_OAUTH_ENABLED=true
export GOOGLE_CLIENT_ID='your-client-id'
export GOOGLE_CLIENT_SECRET='your-client-secret'
export APP_PUBLIC_ORIGIN='http://localhost:3000'
# Callback будет http://localhost:3000/login/oauth2/code/google
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

В Google Console зарегистрировать точный callback URL. Production URL должен использовать HTTPS. При включении неполная конфигурация останавливает запуск. GOOGLE_REDIRECT_URI можно задать отдельно для совместимости; при заданном APP_PUBLIC_ORIGIN он обязан совпадать с origin + /login/oauth2/code/google. Запрашиваются только openid/email/profile, offline access не используется. Spring Security проверяет signature, issuer, audience, expiry, state и nonce до обработки identity; аккаунты по email не объединяются. Сохраняется локальная session identity, Google tokens отбрасываются. Фиксированные редиректы: `/` и `/login?error=oauth_failed`.

### Ошибки и лимиты

Ошибки MVC и security-фильтров имеют application/problem+json с code/status/title/type/instance без секретов; instance не содержит query. API/auth ответы имеют Cache-Control: no-store. JSON строго проверяет неизвестные и дублирующиеся поля, строки/числа не преобразуются в boolean. Health показывает только status.

Лимиты по socket IP на маршрут: login/verify/reset — 20/мин; register/resend/forgot — 5/10 мин; Google start — 10/мин; CSRF — 60/мин. На превышение — 429 с Retry-After в секундах. Настройки: app.rate-limit.login/token/mail/google/csrf, maximum-keys (по умолчанию 10000). Кэш ограничен по размеру, живёт в памяти одного экземпляра и сбрасывается при рестарте. X-Forwarded-* не доверяются, server.forward-headers-strategy=none; если развёртывание потребует reverse proxy, его доверенную конфигурацию нужно задавать отдельно.

## Настройка SMTP

Подключён Spring Boot Mail. Реализация SmtpIdentityMailSender отправляет UTF-8 MIME-письмо с plain-text и HTML-версией, не логирует содержимое и скрывает причины ошибок транспорта. Шаблоны ведут на frontend-страницы `/verify-email?token=…` и `/reset-password?token=…`. GET страницы не погашает токен: действие подтверждается frontend-запросом POST. Host и X-Forwarded-Host запроса не участвуют в формировании ссылки.

### Локальный Mailpit

`.env.example` включает MAIL_ENABLED=true и профиль local. Maven не загружает .env автоматически: после копирования загрузить его через `set -a`, `source .env`, `set +a`, как в командах выше. В shell-файле значения с пробелами/спецсимволами заключать в одинарные кавычки. Не source чужие непроверенные env-файлы. Файл .env игнорируется Git.

Local-профиль по умолчанию использует localhost:1025, SMTP_TLS_MODE=none, From=no-reply@example.test и APP_PUBLIC_ORIGIN=http://localhost:3000. Mailpit запускается профилем Compose `mail`; SMTP опубликован только на 127.0.0.1:1025, UI — http://localhost:8025. Письма не уходят в интернет. Frontend запускается отдельно по его README.

После запуска зарегистрироваться на localhost:3000, открыть письмо в Mailpit и перейти по ссылке. Аналогично проверяется forgot-password. Для остановки Mailpit: `docker compose --profile mail down` (данные PostgreSQL в volume сохраняются).

### Production

| Переменная | Значение |
| --- | --- |
| MAIL_ENABLED | true для включения SMTP; по умолчанию false |
| APP_PUBLIC_ORIGIN | Полный HTTPS origin общего сайта без path/query/fragment |
| SMTP_HOST | Host вашего SMTP-провайдера |
| SMTP_PORT | При отсутствии — 587 для starttls, 465 для tls; явный порт 1–65535 |
| SMTP_TLS_MODE | starttls (по умолчанию) или tls; none запрещён вне local/test |
| SMTP_USERNAME / SMTP_PASSWORD | Credentials SMTP; обязательны вместе в production |
| MAIL_FROM | Один email без display name, разрешённый вашим провайдером |

В production **не** использовать профиль local и его тестовый From. При включённой почте неполная конфигурация останавливает приложение до отправки. Проверяется certificate identity; нет trust-all. STARTTLS обязателен в режиме starttls, а не opportunistic. Connection/read/write timeout — по 5 секунд, mail.debug=false. Проверка настроек не отправляет письмо и не проверяет credentials сетевым запросом при старте.

Отправка по-прежнему синхронная после commit. Недоступный SMTP возвращает 503 `mail_delivery_failed`, пользователь/credentials/токен уже сохранены. После восстановления запросить повторную ссылку: прежняя отзывается. Автоматической очереди/outbox и гарантированной доставки нет.

### Google credentials и Cloudflare

План размещения: общий сайт — Cloudflare Worker, API/OAuth пути он проксирует на внешний Spring Boot backend, БД — внешний PostgreSQL. Деплой в эту задачу не входит. Внешний адрес сайта пока не зафиксирован; после его появления задать APP_PUBLIC_ORIGIN и зарегистрировать **точный** callback `APP_PUBLIC_ORIGIN/login/oauth2/code/google` в Google.

1. В выбранном Google Cloud проекте открыть Google Auth Platform и настроить Branding/Audience/consent screen. Для тестового внешнего приложения добавить тестовые аккаунты.
2. В Clients создать OAuth client типа Web application.
3. В Authorized redirect URIs указать точный HTTPS callback общего сайта. Для локальной разработки можно добавить http://localhost:3000/login/oauth2/code/google. Это backend OIDC flow, JavaScript client secret/frontend OAuth SDK не нужен.
4. В окружении **backend-хостинга** сохранить GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_OAUTH_ENABLED=true и APP_PUBLIC_ORIGIN. Явный GOOGLE_REDIRECT_URI необязателен; если он задан вместе с origin, значения проверяются на соответствие.
5. Проверить переход с кнопки Google и возврат на тот же общий origin. Секреты SMTP/Google не передавать в frontend, NEXT_PUBLIC_* или публичные файлы Worker.

Для реальных писем нужен SMTP-аккаунт или почтовый сервис. Если выбрать Cloudflare Email Service, потребуется подключённый к Cloudflare DNS домен отправителя. Сам адрес Workers сайта не предоставляет домен для отправки писем от имени workers.dev. Покупка домена, создание платных аккаунтов, настройки DNS и размещение приложения остаются отдельными действиями.

Источники: [Spring Mail](https://docs.spring.io/spring-boot/reference/io/email.html), [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect), [Cloudflare Email Service](https://developers.cloudflare.com/email-service/get-started/send-emails/).

### Проверки почты

`./mvnw clean verify` включает тесты TLS-конфигурации, шаблонов и кодирования ссылок, выключенного sender, Google callback и SMTP-интеграцию с Mailpit v1.31.1 через Testcontainers. Проверяются регистрация → настоящее SMTP-письмо → подтверждение и восстановление → SMTP-письмо → смена пароля, а также тайм-аут SMTP, сохранение данных после сбоя и успешная повторная отправка. Реальный production SMTP и настоящий Google аккаунт требуют последующей проверки с вашими credentials и origin.

## VPS / production Compose

Отдельная конфигурация: `compose.vps.yaml`, multi-stage сборка — `Dockerfile`.
Инструкция размещения, SSH-туннеля, обновления и резервного копирования: [VPS deployment](../docs/vps-deployment.md).
Production env хранится вне Git в `/etc/my-notes/production.env`; шаблон — `deploy/.env.example`.

Cloudflare Worker подключён к приватному backend через Tunnel/VPC. EDGE_PROXY_SECRET
защищает собственный заголовок IP; произвольные forwarded-заголовки не доверенные.
Без секрета и числового client IP защищённый origin возвращает 403; GET health остаётся публичным.
Подключение, секреты и Google callback: [Cloudflare deployment](../docs/cloudflare-deployment.md).


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
