# Frontend: анализ и карта реализации

## Источники

- Figma: https://www.figma.com/design/bvF8q21cAlNXfUgkrbqQHP/Untitled, страница `0:1`.
- Контракт `docs/openapi.yaml`; фактические `AuthController`, `TaskController`, `UpdateTaskRequest`, `SecurityConfiguration`, `InputValidation`, `ApiExceptionHandler`.
- До начала работ `frontend/` был пуст. Незакоммиченные backend-изменения пользователя сохранены.

## Карта дизайна

| Figma | Узел | Реализация |
| --- | --- | --- |
| Вход, регистрация | `2:18736`, `2:18820` | CredentialsPage, CredentialsForm, GoogleButton |
| Ожидание/результат подтверждения | `2:18900`, `2:18946` | VerificationWaiting, VerifyEmailAction, result page |
| Восстановление, новый пароль, результат | `2:18982`, `2:19028`, `2:19080` | RecoveryForm, ResetPasswordForm, result page |
| Мои задачи desktop/mobile | `2:19116`, `2:19208` | TaskBoard, CreateTaskForm, TaskRow |
| Редактор mobile | `2:19403` | TaskEditor, адаптивная строка |
| Палитра, типографика/шкалы | `2:20850`, `2:20883` | CSS variables/theme и shared UI |

Полный metadata-инвентарь также содержит мобильные auth-экраны, desktop-редактор, доски состояний и семейства компонентов. Figma Starter вернул лимит для части high-fidelity запросов (в том числе `2:18778`, `2:19303`). Их отдельный контекст и полная проверка всех variants недоступны. Получены high-fidelity контексты основных auth-экранов, задач и мобильного редактора, screenshots и variable definitions; Code Connect mappings в ответах не предоставлены. Дополнительные состояния реализованы по доступным компонентам и API, а не считаются независимо сверенными.

Токены: фон `#F8F9FC`, поверхность `#FFFFFF`, текст `#182033`, вторичный `#5E687C`, границы `#DCE1EA`, индиго `#4F46E5`, ошибка `#B42332`, успех `#23724B`; Inter, line-height 150%, размеры 12/14/16/20/28/32, радиусы 4/8/12. Auth-карточка 440 px с padding 32, колонка задач 720 px. Мобильные задачи: padding 20, действия под текстом. SVG eye из Figma сохранён локально, исходный размер 20×20. Google «G» в исходном макете является текстом, а не SVG.

## Порядок реализации

1. Инвентаризация Figma/API и выбор структуры `app → views → widgets → features → entities → shared`.
2. Foundation: Next, generated OpenAPI types, UI/tokens, providers, API client.
3. Auth-экраны: регистрация, подтверждение, вход, восстановление, Google browser flow.
4. Личные задачи, редактор, ошибки, кэш и сессии.
5. Unit/component, desktop/mobile E2E, сборка и визуальная проверка.

Полная карта страниц и endpoints находится в `frontend/README.md`. `GET /api/tasks/{id}` присутствует в контракте, но отдельная detail-страница в дизайне отсутствует — лишний запрос не добавлен. OAuth-протокол callback проверяет Spring; browser route handler Next передаёт запрос, cookies и разрешённый редирект, а ошибки возвращает на страницу входа.

## Расхождения и минимальные решения

| Наблюдение | Решение frontend |
| --- | --- |
| Figma предусматривает редактирование; OpenAPI PATCH принимает только `completed`, backend принимает `title` и/или `completed` | Использовать существующий PATCH; локальный тип — Partial Pick generated Task. Backend/API-документ не менять в этой задаче |
| В OpenAPI нет 503 для почты/Google; backend возвращает именованные коды | Показывать безопасные сообщения по фактическим кодам; не имитировать успешную доставку |
| У backend CORS отключён, cookie/OAuth требуют общий origin | Относительные URL; только dev transparent rewrites. Production ingress направляет API прямо в Spring, browser OAuth start/callback — в Next для обработки ошибок |
| В макете ожидания есть обратный отсчёт 42 сек., но успешный email API не возвращает срок | Не выдумывать cooldown; отсчёт только по реальному Retry-After на 429 |
| В backend нет sender и по умолчанию Google выключен | UI подключён к реальным маршрутам, ошибки доступны; полный внешний auth требует инфраструктуры |
| Ссылка email и GET не должны погашать токен | Отдельная страница с явным POST-подтверждением; reset выполняется при отправке нового пароля |
| Ожидание письма без сохранённого адреса после reload | Поле email для повторной отправки; адрес не хранить в URL/localStorage |

Backend пользовательским запросом не изменяется. Никаких новых endpoints, mock-режима в runtime, токенов в localStorage, автоматического повтора записей и оптимистического показа успешного сохранения.

## Проверка реализации

- Unit/component: 29 проверок — Unicode и валидация, CSRF/credentials/очередь запросов, обработка ошибок, работа редактора и OAuth browser navigation.
- Playwright: 12 сценариев в desktop Chromium 1440×1024 и mobile Chromium 390×844 (24 запуска). Авторизация, CRUD с перезагрузкой, регистрация/подтверждение/сброс, ошибки, rate limit, истечение сессии, фокус и отсутствие горизонтальной прокрутки. Включён переход к регистрации после выхода; тест ожидает целевую страницу перед заполнением формы.
- Скриншоты login/tasks/editor сохраняются в игнорируемом `frontend/test-results/`; визуально сверены с доступными Figma renders. Исправлено оформление disabled-действий редактора; production не содержит Next dev indicator.
- Реальный browser smoke на Spring Boot и отдельной PostgreSQL 18: email-вход, настоящий CSRF и session cookie, создание, PATCH title, выполнение, сохранение после reload, удаление, выход и показ реального 503 почтовой доставки. Ошибок JavaScript в браузере нет. Для этой проверки использовалась отдельная временная БД, существующая пользовательская БД не затрагивалась.
- Google consent и реальная доставка писем не проверены: нужны внешний OAuth client и почтовый sender. В browser E2E эти внешние границы заменены HTTP-фикстурами только на уровне теста.
- `npm audit --omit=dev`: без уязвимостей production-зависимостей. Полный audit сообщает moderate advisory в dev-цепочке ESLint/TypeScript ESLint → minimatch; автоматический предложенный downgrade Next config не применялся. ESLint 9 и TypeScript 5.9 выбраны по совместимости с текущими Next lint plugins и openapi-typescript.

## Аудит ошибок браузерной навигации

Обнаружена прямая ссылка Google на backend endpoint, возвращающий Problem JSON при выключенном OAuth или rate limit. Та же проблема возможна на callback при ошибках фильтров/прокси. Добавлены ровно два browser route handlers на прежних URL; downstream endpoints и backend не меняются. Нет дополнительного probe-запроса: Google state создаётся один раз. Cookies Spring передаются в обе стороны без изменения, внешние редиректы ограничены Google, callback — двумя документированными локальными адресами. Backend outage/timeout и JSON/HTML ошибок преобразуются в безопасный redirect на login. Этот узкий серверный переход необходим, поскольку browser fetch с manual redirect скрывает Location и Set-Cookie и не позволяет продолжить тот же OAuth flow.

Остальные источники навигации проверены: обычные ссылки, logout, session-expired и email-ссылки ведут на UI. Ошибки шести auth-форм, задач, CSRF, неизвестного/HTML ответа сервера и сети показываются через безопасные сообщения; body/detail/stack trace не рендерятся. API остаётся JSON для программных клиентов.

После аудита прошли lint, typecheck, 29 unit/component tests, production build и 24 Playwright-проверки на production-сборке; работающий dev-сайт и backend оставлены запущенными. В реальном браузере проверены Google-вход, регистрация через Google и отказ callback: документы JSON не открываются.
