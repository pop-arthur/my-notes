# VPS: backend и PostgreSQL

## Размещение

VPS `31.59.45.10`, SSH `root`, порт 22. Ubuntu 24.04, 1 vCPU, около 1 ГБ RAM, диск около 10 ГБ. Добавлен swap 1 ГБ (`/swapfile`, запись в `/etc/fstab`). Это минимальный стенд: перед реальной нагрузкой пересмотреть RAM и диск.

- Исходники и production Compose: `/opt/my-notes/backend/`.
- Конфигурация: `/etc/my-notes/production.env`, root:root, `600`, каталог `700`.
- PostgreSQL: Docker volume `my-notes-postgres-data`, mount `/var/lib/postgresql` (раскладка PostgreSQL 18).
- Бэкапы: `/var/backups/my-notes/`, каталог `700`, файлы `600`.
- Команды: `/usr/local/sbin/my-notes-compose`, `my-notes-backup`, `my-notes-restore-check`.
- Build log: `/var/log/my-notes-build.log`. Вывод runtime — через Compose logs, ротация 3 × 10 МБ на контейнер.

Docker Engine и Compose установлены через [официальный apt-репозиторий Docker](https://docs.docker.com/engine/install/ubuntu/). Docker и containerd включены в автозапуск. Для PostgreSQL 18 используется [официальный образ и его новая точка volume](https://hub.docker.com/_/postgres).

## Состав и ограничения

`backend/compose.vps.yaml` запускает только backend и PostgreSQL 18. Build выполняет Maven Wrapper 3.9.11 под Java 21, runtime — Java 21 JRE, UID/GID 10001. Docker build пропускает тесты; до выпуска отдельно выполнить `./mvnw verify` с работающим Docker. Версия Spring Boot берётся из `pom.xml` (4.1.2).

Профиль `production`, Flyway включён, Hibernate `validate`. Postgres создаёт отдельную роль `my_notes` без superuser/createdb/createrole; backend использует её. Административный пароль другой. SQL инициализации выполняется **только при первом старте пустого volume**. Изменение env не меняет пароль существующей БД — для ротации требуется согласованное изменение роли и окружения.

Backend публикуется только на `127.0.0.1:8080`, у PostgreSQL вообще нет port mapping. UFW: incoming deny, outgoing allow; SSH 22 разрешён, 5432/8080 закрыты для IPv4 и IPv6. Docker может обходить обычные UFW-правила при публикации портов: не добавлять внешние mappings в Compose. Не изменены SSH-порт и root login.

SMTP и Google по умолчанию выключены; включение управляется production env. `APP_PUBLIC_ORIGIN` и callback не заданы. Почтовые сценарии и Google возвращают 503, фиктивных аккаунтов/успеха нет. Вход с email возможен только для уже подтверждённого пользователя; на чистой БД нет пользователей. Cookie Secure остаётся включённым; профиль local не используется.

## Первичная установка файлов

Перед повторной установкой проверять ОС, сервисы, порты, firewall и существующие файлы. Не перезаписывать env, не удалять volume. Установка пакетов — по официальной инструкции выше. Образам нужны Docker Hub, а сборке — Maven Central.

Передать `backend/` без `.env`, `target`, IDE-файлов и секретов в `/opt/my-notes/backend`. Шаблон `backend/deploy/.env.example` содержит только заглушки. Сгенерировать на VPS три независимых значения DB_ADMIN_PASSWORD, DB_PASSWORD и EDGE_PROXY_SECRET (`openssl rand -hex 32`), записать в `/etc/my-notes/production.env`, не выводя их. Не использовать `set -x`, `docker compose config` без `--quiet` или `docker inspect` целиком: они могут раскрыть env. Доступ к Docker/root равнозначен доступу к секретам.

Установка команд и таймера (root, на сервере):

```sh
install -m 755 /opt/my-notes/backend/deploy/compose.sh /usr/local/sbin/my-notes-compose
install -m 755 /opt/my-notes/backend/deploy/backup.sh /usr/local/sbin/my-notes-backup
install -m 755 /opt/my-notes/backend/deploy/restore-check.sh /usr/local/sbin/my-notes-restore-check
install -m 644 /opt/my-notes/backend/deploy/systemd/my-notes-backup.* /etc/systemd/system/
systemctl daemon-reload
my-notes-compose config --quiet
my-notes-compose build
my-notes-compose up -d --wait --wait-timeout 300
systemctl enable --now my-notes-backup.timer
```

## Проверка через SSH-туннель

На своём компьютере:

```sh
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:18080:127.0.0.1:8080 root@31.59.45.10
```

В другом терминале:

```sh
curl -i http://localhost:18080/actuator/health
curl -i -c /tmp/my-notes-vps.cookies http://localhost:18080/api/auth/csrf
curl -i http://localhost:18080/api/auth/me
curl -i http://localhost:18080/api/tasks
curl -i http://localhost:18080/oauth2/authorization/google
```

Ожидается: health `200 {"status":"UP"}`, CSRF `200` и Secure/HttpOnly session cookie, `me` и tasks `401 application/problem+json`, Google `503 google_unavailable`. Секретные cookie/CSRF не публиковать. Для POST использовать token из CSRF в `X-CSRF-TOKEN` и cookie той же сессии; без CSRF ожидается 403. Mail endpoints с валидным CSRF дают 503 `mail_delivery_unavailable`. Если HTTP-клиент не отправляет Secure cookie через туннель, использовать curl с `localhost` или передать cookie вручную только в локальном проверочном клиенте; не отключать Secure в production.

Повторяемая проверка без вывода cookie/CSRF: `python3 backend/deploy/smoke-check.py http://localhost:18080` на своём компьютере через открытый туннель.

На VPS:

```sh
python3 /opt/my-notes/backend/deploy/smoke-check.py
my-notes-compose ps
my-notes-compose exec -T postgres pg_isready -U postgres -d my_notes
my-notes-compose exec -T postgres psql -X -U postgres -d my_notes \
  -c 'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;'
my-notes-compose logs --tail=100 backend
```

## Обновление

1. Локально проверить `backend/./mvnw verify`. Просмотреть миграции и совместимость старого приложения с новой схемой.
2. На VPS выполнить `my-notes-backup` и проверить свежий архив через `my-notes-restore-check /var/backups/my-notes/ИМЯ.dump`. Убедиться в свободном месте (`df -h`, `docker system df`).
3. Сохранить текущий образ: `docker image tag my-notes-backend:vps my-notes-backend:previous`. Передать проверенные backend-исходники, не перезаписывая `/etc/my-notes/production.env`. Не копировать локальные секреты и build output.
4. `my-notes-compose config --quiet`, затем `my-notes-compose build backend` и `my-notes-compose up -d --no-deps --wait --wait-timeout 300 backend`.
5. Проверить health, миграции и отказ неавторизованного доступа. Пересоздать backend после изменения env (`up -d --force-recreate backend`); `restart` не подхватывает новое окружение.

При совместимой схеме для отката установить `BACKEND_IMAGE_TAG=previous` в серверном env и выполнить `my-notes-compose up -d --no-deps --wait backend`. **Откат контейнера приложения не откатывает миграции БД.** Для несовместимой схемы нужен отдельный план восстановления, остановка записи и возможная потеря данных после точки бэкапа. Не запускать старое приложение на неподходящей схеме.

Образы используют major-теги (Temurin 21, Postgres 18): при обновлении просматривать release notes; запись ID/digest развернутых образов можно получить через `docker image ls --digests`. Смена major PostgreSQL требует отдельной процедуры, а не простой смены тега. Не запускать `docker system prune --volumes` или `compose down -v`.

## Логи и перезапуск

```sh
my-notes-compose logs --tail=100 -f backend
my-notes-compose logs --tail=100 postgres
my-notes-compose restart backend
my-notes-compose ps
systemctl status docker --no-pager
systemctl list-timers my-notes-backup.timer --no-pager
journalctl -u my-notes-backup.service --since yesterday
```

Restart policy `unless-stopped` возвращает контейнеры после рестарта Docker/хоста, если они не были остановлены вручную. Healthcheck сам по себе не перезапускает процесс при статусе unhealthy: проверить логи, БД и память. При необходимости восстановить весь Compose командой `my-notes-compose up -d --wait --wait-timeout 300`.

## Бэкапы и восстановление

Таймер `my-notes-backup.timer`: ежедневно 03:15 UTC с задержкой до 5 минут; `Persistent=true` запускает пропущенное задание. Это 06:15–06:20 по Москве. `pg_dump --format=custom` создаёт согласованный снимок одной БД, файл сначала `.partial`, затем атомарно переименовывается. Проверяется читаемость оглавления. `flock` исключает одновременные задания, архивы старше 14 дней удаляются только после успешной новой копии. Диск маленький: контролировать размер/успешность копий; автоматической отправки уведомлений нет.

Ручной запуск и проверка восстановления:

```sh
my-notes-backup
ls -lh /var/backups/my-notes/
my-notes-restore-check /var/backups/my-notes/ИМЯ.dump
```

Проверка создаёт уникальную временную БД `my_notes_restore_*`, восстанавливает архив целиком в одной транзакции, проверяет миграции и таблицы users/tasks/spring_session, затем удаляет **только эту временную БД** через trap. Рабочая БД не изменяется. Это проверка восстанавливаемости архива; сверку бизнес-данных после накопления реальных записей нужно расширить.

Для восстановления после аварии сначала сохранить текущий volume/состояние и остановить backend. Проверить архив во временной БД. Создать **новую** БД, восстановить в неё архив и переключить DB_URL backend на неё; не удалять старую до проверки. Пример на VPS (заменить путь и имя; имя должно быть новым):

```sh
my-notes-compose stop backend
my-notes-compose exec -T postgres createdb -U postgres -O my_notes my_notes_recovered_YYYYMMDD
my-notes-compose exec -T postgres pg_restore -U postgres \
  --dbname=my_notes_recovered_YYYYMMDD --no-owner --role=my_notes \
  --exit-on-error --single-transaction < /var/backups/my-notes/ИМЯ.dump
```

Для переключения изменить `DB_URL` в production Compose (сейчас он задан явно на `my_notes`), затем пересоздать backend и проверить Flyway/health. Учитывать, что `POSTGRES_DB` и текущий backup script также должны указывать восстановленную БД: обновить Compose согласованно, чтобы последующие копии снимались с неё. Не запускать recovery автоматически на рабочей БД. PostgreSQL roles не входят в pg_dump: при переносе на новый сервер сначала создать роль `my_notes`; fresh volume делает это init SQL. Пароли, серверный env и исходники нужны отдельно. После восстановления желательно отозвать восстановленные пользовательские сессии по отдельной процедуре.

**Локальные копии на том же VPS не являются полноценным бэкапом.** Нужна отдельная внешняя площадка, шифрование, ограниченный доступ, retention, контроль успешной доставки и периодические restore drills. Внешнее хранилище не создавалось и не оплачено.

## Cloudflare и публичный запуск

Публичный сайт размещён на workers.dev. Backend закрыт на loopback; Worker связан с ним
через один HTTP VPC Service и постоянный Cloudflare Tunnel. Покупка домена и IP-сертификат
не требуются. Детали, команды проверки и оставшиеся Google/SMTP настройки:
[Cloudflare deployment](cloudflare-deployment.md).

При включённом EDGE_PROXY_SECRET прямые API-запросы через SSH-туннель без собственных
доверенных заголовков возвращают 403. Это ожидаемая защита origin; GET health остаётся 200.
Публичный Worker добавляет проверяемый секрет и IP клиента; forwarded-заголовкам не доверять.
Секрет не выводить при curl, compose config или inspect. Для end-to-end проверок использовать
публичный сайт и CSRF одной сессии. Внешнее хранилище бэкапов пока не подключено.
