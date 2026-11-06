# Backend

Слой хранения «Списка задач»: Java 21, Spring Boot 4.1.2, JPA / Hibernate через EntityManager, PostgreSQL 18, Flyway. Сборка через Maven Wrapper 3.9.11. Версии библиотек управляются Spring Boot; ArchUnit зафиксирован отдельно в `pom.xml`.

Реализованы JPA entities и интерфейсы репозиториев с единственными JPA-реализациями областей `tasks` и `identity`, включая пользователей, password credentials, внешние identities и email-токены. Реализованы бизнес-сервисы задач, регистрации, подтверждения адреса, проверки пароля, сброса пароля и обработки доверенной Google identity. Реализованы HTTP API, Google OIDC, JDBC-сессии, CSRF, rate limiting и редактирование задач. Реальный почтовый адаптер и frontend ещё не реализованы.

## Локальная база и запуск

Нужны JDK 21+ и работающий Docker. Команды выполнять из `backend/`:

```sh
export DB_USERNAME=my_notes
export DB_PASSWORD='replace-with-a-local-password'
docker compose up -d --wait
./mvnw verify
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
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

`http` предназначен для контроллеров и request/response DTO, `service` — для бизнес-логики и транзакций, `repo` — для запросов хранения. Направление зависимостей: `http → service → repo`. В http находятся контроллеры, DTO и security-интеграция; бизнес-сценарии — в service.

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

Почта пока представлена только интерфейсом IdentityMailSender. В обычном запуске sender bean отсутствует: register, resend и requestReset явно завершаются MailDeliveryUnavailableException **до обращения к аккаунтам или записи в БД**. Остальные сервисы и приложение могут запускаться. Тестовый перехватчик находится только в test sources; он не является production-отправщиком. Исходные токены не возвращаются из публичных бизнес-сценариев и не печатаются через toString.

После подключения настоящего sender он будет получать IdentityMail (адрес, назначение, исходный токен) только после успешного commit. Если sender бросит ошибку, данные уже сохранены, возвращается безопасная MailDeliveryFailureException без причины, способной раскрыть содержимое письма. Повторный resend/requestReset заменяет ссылку. Outbox и гарантированной доставки пока нет; настройка почты будет отдельным этапом.

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

Почтовый sender не включён: register/resend-verification/forgot-password без адаптера возвращают 503 `mail_delivery_unavailable`. Не создаётся fake production-аккаунт и не возвращается исходный токен. Полная email-цепочка выполняется тестами с sender из test sources; для обычного использования нужно добавить реальную реализацию IdentityMailSender отдельным этапом. Ошибка настроенного sender после commit даёт `mail_delivery_failed`, данные уже сохранены и восстановление доступно повторным запросом письма.

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
export GOOGLE_REDIRECT_URI='http://localhost:8080/login/oauth2/code/google'
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

В Google Console зарегистрировать точный callback URL. Production URL должен использовать HTTPS. При включении неполная конфигурация останавливает запуск. Запрашиваются только openid/email/profile, offline access не используется. Spring Security проверяет signature, issuer, audience, expiry, state и nonce до обработки identity; аккаунты по email не объединяются. Сохраняется локальная session identity, Google tokens отбрасываются. Фиксированные редиректы: `/` и `/login?error=oauth_failed`.

### Ошибки и лимиты

Ошибки MVC и security-фильтров имеют application/problem+json с code/status/title/type/instance без секретов; instance не содержит query. API/auth ответы имеют Cache-Control: no-store. JSON строго проверяет неизвестные и дублирующиеся поля, строки/числа не преобразуются в boolean. Health показывает только status.

Лимиты по socket IP на маршрут: login/verify/reset — 20/мин; register/resend/forgot — 5/10 мин; Google start — 10/мин; CSRF — 60/мин. На превышение — 429 с Retry-After в секундах. Настройки: app.rate-limit.login/token/mail/google/csrf, maximum-keys (по умолчанию 10000). Кэш ограничен по размеру, живёт в памяти одного экземпляра и сбрасывается при рестарте. X-Forwarded-* не доверяются, server.forward-headers-strategy=none; если развёртывание потребует reverse proxy, его доверенную конфигурацию нужно задавать отдельно.
