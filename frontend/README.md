# Frontend · Список задач

Next.js App Router, React, TypeScript strict, Tailwind CSS, адаптированные shadcn/ui primitives на Radix, TanStack Query, React Hook Form и Zod. Иконки — Lucide; Inter размещён локально. Zustand не нужен: общего клиентского состояния вне сценариев нет.

## Запуск

Требуется Node.js 24 LTS (24.15+) и npm; `nvm use` использует `.nvmrc`. Версии пакетов закреплены в `package-lock.json`.

```bash
cd frontend
npm ci
cp .env.example .env.local
npm run dev
```

Сайт: http://localhost:3000. Backend запускается отдельно по [его инструкции](../backend/README.md), с профилем `local` для HTTP-cookie.

`BACKEND_ORIGIN` — серверный origin Spring для OAuth navigation handlers и локальных API rewrites. В разработке по умолчанию `http://127.0.0.1:8080`; в production переменная обязательна для Google-входа. Браузер всегда обращается к относительным путям того же origin. Публичных секретов и `NEXT_PUBLIC_*` переменных нет.

В production frontend работает как Cloudflare Worker через OpenNext. `/api/*` и
`/actuator/health` передаются через фиксированный VPC Service в Spring Boot; страницы
и Google start/callback обслуживает Next. OAuth handlers используют тот же VPC transport,
сохраняют cookie и разрешённые редиректы; JWT/state/nonce проверяет Spring.
BACKEND_ORIGIN используется только для обычного локального Next, без VPC.
Сайт и API имеют один origin; CORS в backend закрыт.
Подробности: [Cloudflare deployment](../docs/cloudflare-deployment.md).

## Страницы и API

| Страница                  | Сценарий                                                        | API                                                                                                |
| ------------------------- | --------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `/`                       | Личные задачи, добавление, редактирование, выполнение, удаление | `GET /api/auth/me`, `GET/POST /api/tasks`, `PATCH/DELETE /api/tasks/{id}`, `POST /api/auth/logout` |
| `/login`                  | Email-вход, ошибка Google, истечение сессии                     | `POST /api/auth/login`, навигация `/oauth2/authorization/google`                                   |
| `/register`               | Регистрация, затем ожидание подтверждения                       | `POST /api/auth/register`, `POST /api/auth/resend-verification`                                    |
| `/check-email`            | Запрос повторного подтверждения                                 | `POST /api/auth/resend-verification`                                                               |
| `/verify-email?token=…`   | Явное подтверждение кнопкой                                     | `POST /api/auth/verify-email`                                                                      |
| `/email-verified`         | Результат подтверждения, переход ко входу                       | —                                                                                                  |
| `/forgot-password`        | Нейтральный запрос восстановления                               | `POST /api/auth/forgot-password`                                                                   |
| `/reset-password?token=…` | Новый пароль                                                    | `POST /api/auth/reset-password`                                                                    |
| `/password-updated`       | Результат сброса, переход ко входу                              | —                                                                                                  |

Email-ссылки должны вести на `/verify-email?token=…` и `/reset-password?token=…` общего origin. Приложение задаёт `Referrer-Policy: no-referrer`, не подключает аналитику и не сохраняет токены/пароли в localStorage. Внешние access logs должны исключать/redact query этих URL. GET не погашает одноразовую ссылку.

Перед изменяющими запросами клиент получает `GET /api/auth/csrf`, передаёт cookie и `X-CSRF-TOKEN`. Одновременные запросы токена объединяются; записи сериализуются. После login/logout/reset CSRF сбрасывается. При `csrf_invalid` клиент обновляет токен, но не повторяет исходную операцию автоматически. Чтение профиля повторяется при возвращении в окно, приватный query cache ограничен ID пользователя и очищается при выходе/истечении сессии. Пароли не попадают в query cache; mutation cache имеет `gcTime: 0`.

## Архитектура

`app` — server layouts, маршруты и providers; `views` — композиция страниц; `widgets` — шапка и доска задач; `features` — auth и действия над задачами; `entities` — профиль/задачи и query keys; `shared` — HTTP-клиент, сгенерированные типы, UI primitives, валидация. Направление импортов сверху вниз. Взаимодействие с cookie-сессией требует браузера; формы и интерактивная доска — Client Components, layouts/route composition остаются Server Components.

Контракт: [OpenAPI](../docs/openapi.yaml). Типы генерируются командой `npm run api:generate`; файл `src/shared/api/schema.d.ts` не редактируется вручную. [Карта реализации и расхождения](../docs/frontend-analysis.md).

## Проверки

```bash
npm run api:generate
npm run lint
npm run typecheck
npm run test
npm run build
npx playwright install chromium
npm run test:e2e
npm run format:check
```

Vitest проверяет граничную валидацию Unicode, cookie/CSRF-клиент, обработку ошибок и редактор. Playwright выполняет пользовательские сценарии на desktop Chromium и мобильном Chromium с контролируемым HTTP API, включая ошибки, сессию и screenshots. Эти browser-тесты не заменяют проверку реальной доставки email и Google OAuth. Фикстуры существуют только в `tests/`, production-приложение всегда использует настоящий API.

## Ограничения интеграции

- OpenAPI 0.2.0 поддерживает PATCH title/completed; frontend использует generated UpdateTaskRequest. Gateway 502 описан в контракте.
- Production SMTP подключён через Gmail. При недоступности отправщика формы показывают `503 mail_delivery_unavailable` / `mail_delivery_failed`, не подменяя их успехом.
- Google требует настройки backend credentials. Без неё backend возвращает `503 google_unavailable`; браузерный обработчик возвращает пользователя на `/login?error=google_unavailable`, не показывая JSON. OAuth невозможно завершить одной настройкой frontend.
- Figma Starter ограничил чтение части состояний. Основные экраны, палитра, шкалы, мобильные задачи и редактор получены; недоступные варианты адаптированы на основе полученных компонентов. Полное визуальное совпадение всех недоступных состояний не подтверждено.

## Обработка ошибок браузерных переходов

Google-вход и регистрация используют общий `/oauth2/authorization/google`. Его browser handler и callback handler `/login/oauth2/code/google` передают только существующие Google-маршруты backend, session cookie и документированные callback-параметры. Исходящий запрос не следует редиректам автоматически: браузер получает разрешённый Google redirect и все Set-Cookie; callback допускает только `/` и `/login?error=oauth_failed`. Ошибки 429 сохраняют безопасный Retry-After как время ожидания. Произвольные URL, JSON/HTML ошибок, OAuth code/state и детали исключений не попадают на экран ошибки. В dev отключён access logging для callback и email-ссылок с токенами. На внешнем ingress также исключить query этих маршрутов из журналов.

Проверены остальные UI-переходы: ссылки ведут на HTML-страницы; email-вход, регистрация, подтверждение, resend и восстановление используют API-клиент и безопасные Notice. Тесты `errors.spec.ts` проверяют недоступность сети, 429, JSON 4xx/5xx и HTML ошибки на всех шести auth-формах. OAuth navigation отдельно проверяется с upstream mocks (включая сохранение cookie и штатного redirect), а отключённый Google — в реальном браузере.

## Cloudflare Workers / VPS

Сайт: https://my-notes.pop-arthur-c189baa0.workers.dev.
Deployment: `npm run worker:deploy`; preview: `npm run worker:preview`.
Сначала проверить `npm run worker:build`, `npm run test:worker`, `npm run worker:check`.
Worker передаёт API/health в закрытый Spring Boot через BACKEND VPC Service и Tunnel;
OAuth handlers тоже используют binding. BACKEND_ORIGIN нужен для обычного локального Next.
Подробности, обновление/откат и оставшаяся настройка Google/SMTP:
[Cloudflare deployment](../docs/cloudflare-deployment.md).

`npm run test:integration` запускает отдельные временные PostgreSQL/Mailpit, Spring Boot
на 18080 и Next на 3200, Mailpit API на 18025. Нужны Docker, Java 21 и Chromium Playwright.
Локальная БД и .env не используются; контейнеры удаляются при завершении.
Тесты работают на desktop/mobile; не запускать браузерные проверки параллельно.
