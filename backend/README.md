# Backend

Слой хранения «Списка задач»: Java 21, Spring Boot 4.1.2, JPA / Hibernate через EntityManager, PostgreSQL 18, Flyway. Сборка через Maven Wrapper 3.9.11. Версии библиотек управляются Spring Boot; ArchUnit зафиксирован отдельно в `pom.xml`.

Реализованы JPA entities и интерфейсы репозиториев с единственными JPA-реализациями областей `tasks` и `identity`, включая пользователей, password credentials, внешние identities и email-токены. Реализованы бизнес-сервисы задач, регистрации, подтверждения адреса, проверки пароля, сброса пароля и обработки доверенной Google identity. HTTP API, OAuth flow, сессии, CSRF, rate limiting, реальная отправка писем и frontend пока не реализованы. Из Spring Security подключён только Crypto-модуль.

## Локальная база и запуск

Нужны JDK 21+ и работающий Docker. Команды выполнять из `backend/`:

```sh
export DB_USERNAME=my_notes
export DB_PASSWORD='replace-with-a-local-password'
docker compose up -d --wait
./mvnw verify
./mvnw spring-boot:run
```

`spring-boot:run` запускает только non-web контекст и проверяет миграции и JPA-схему; HTTP-порт пока не открывается. Для сборки jar: `./mvnw package`. Проверки интеграции входят в `verify`, поэтому перед выпуском запускать именно его.

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

`http` предназначен для контроллеров и request/response DTO, `service` — для бизнес-логики и транзакций, `repo` — для запросов хранения. Направление зависимостей: `http → service → repo`. В http пока только `package-info.java`; бизнес-сервисы находятся в service.

На сущность приходится один интерфейс `*Repository` и одна реализация `Jpa*Repository` с `@Repository` и `EntityManager`. Сервисы зависят от интерфейсов; Spring внедряет единственную JPA-реализацию. Дополнительного Spring Data репозитория и отдельного слоя портов нет. Сервисам доступны JPA entities из `repo.entity`; отдельного domain-слоя и маппинга domain ↔ entity нет. Entities имеют конструкторы и getters, без публичных setters; изменения полей выполняются специализированными методами репозитория. Сервисы возвращают `service.result` без ссылок на entities; в будущем HTTP преобразует их в свои DTO и не вызывает repo напрямую. Общий insert helper находится в `shared.repo`.

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
| `TaskService` | create, list, get, setCompleted, delete — для подтверждённого владельца |
| `RegistrationService` | register — нейтральный результат, неподтверждённый пользователь + credentials + токен |
| `EmailVerificationService` | verify, resend — подтверждение адреса и замена ссылки |
| `PasswordAuthenticationService` | authenticate — проверка пароля и подтверждения, без создания сессии |
| `PasswordRecoveryService` | requestReset, reset — смена пароля и увеличение auth version |
| `GoogleIdentityService` | authenticate — поиск/создание по доверенным issuer + subject |
| `CurrentUserService` | requireVerified — безопасный профиль подтверждённого пользователя |

`EmailTokenService` — внутренний координатор, его payload с исходным токеном доступен только почтовой границе, не HTTP. `TaskResult`, `UserResult`, `AuthenticationResult` не содержат credentials и email-токенов. AuthenticationResult содержит внутреннюю auth version для будущего слоя сессий; её не следует добавлять в публичный User DTO.

Сервисы получают Clock и UuidGenerator через DI. Время сохраняется в UTC с микросекундной точностью. Email очищается по краям и приводится к нижнему регистру через Locale.ROOT без Gmail-преобразований. Заголовок — 1–200 Unicode code points после strip. Пароль при создании/сбросе — 12–128 code points, при входе — 1–128, без нормализации и усечения. Хранение пароля: PBKDF2 с идентификатором `{pbkdf2@SpringSecurity_v5_8}`. При отсутствии credentials вход выполняет проверку dummy hash и выдаёт ту же ошибку, что неверный пароль.

GoogleIdentityService не проверяет JWT и не начинает OAuth flow. Он принимает VerifiedGoogleIdentity только от будущей доверенной проверки Google ID token. Issuer `accounts.google.com` приводится к `https://accounts.google.com`; другие issuer отклоняются. Требуется подтверждённый email. Аккаунты по совпадению email не объединяются, email существующей Google identity автоматически не меняется.

### Транзакции identity и граница почты

Регистрация, первый Google-вход и выдача писем через resend/requestReset выполняются в REQUIRES_NEW. Они коммитятся независимо от внешней транзакции вызывающего кода; конфликт создания обрабатывается уже после rollback. Подтверждение и reset используют REQUIRED и откатываются вместе с транзакцией вызывающего сценария. При блокировке сначала захватывается пользователь, затем изменяется токен; состояние пользователя после ожидания lock перечитывается из БД.

Почта пока представлена только интерфейсом IdentityMailSender. В обычном запуске sender bean отсутствует: register, resend и requestReset явно завершаются MailDeliveryUnavailableException **до обращения к аккаунтам или записи в БД**. Остальные сервисы и приложение могут запускаться. Тестовый перехватчик находится только в test sources; он не является production-отправщиком. Исходные токены не возвращаются из публичных бизнес-сценариев и не печатаются через toString.

После подключения настоящего sender он будет получать IdentityMail (адрес, назначение, исходный токен) только после успешного commit. Если sender бросит ошибку, данные уже сохранены, возвращается безопасная MailDeliveryFailureException без причины, способной раскрыть содержимое письма. Повторный resend/requestReset заменяет ссылку. Outbox и гарантированной доставки пока нет; настройка почты будет отдельным этапом.

Ссылки подтверждения действуют 24 часа, сброса — 30 минут. Истечение проверяется строго (`expiresAt > now`), токен одноразовый; хранятся только SHA-256 hashes случайных 32-байтовых Base64 URL токенов.

### auth_version

Миграция V2 добавляет users.auth_version (изначально 0). Успешный reset увеличивает её в одной транзакции со сменой пароля и погашением токенов. AuthenticationResult возвращает снимок версии при проверке входа. Будущий слой сессий должен сравнивать его с текущей версией и отклонять устаревшие сессии. Сейчас это только подготовленный механизм: фактические браузерные сессии, login/logout HTTP и их отзыв ещё не реализованы.

## Проверки

```sh
./mvnw verify
```

ArchUnit проверяет запрет зависимостей repo/entities от service/http, service от http и конкретных Jpa-репозиториев, а также http от repo. Отсутствие реализаций service/http допускается. Интеграционные тесты запускают отдельный PostgreSQL 18 через Testcontainers; локальная БД и переменные подключения им не нужны. Docker обязателен: при его отсутствии сборка падает, интеграционные тесты не пропускаются.

Проверяются миграции, Hibernate validation, маппинг, уникальность, внешние ключи, изоляция задач, rollback, блокировка пользователя, бизнес-валидация, цепочки регистрации/подтверждения/сброса и конкурентные выдача/погашение токенов и входы. HTTP-аутентификация, CSRF и сессии будут проверяться после реализации соответствующего слоя.

- [Правила разработки](../AGENTS.md)
- [Требования и сценарии](../docs/task-analysis.md)
- [Контракт API](../docs/openapi.yaml)
