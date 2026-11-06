# Frontend agreements

- Работать в `frontend/`. Стек: Next App Router, React, TypeScript strict, Tailwind, shadcn/Radix, TanStack Query, RHF + Zod, Lucide. Версии фиксировать lockfile; Node 24 LTS.
- `src/app` — маршруты/layouts/providers; `views` — страницы; `widgets` — крупные блоки; `features` — сценарии; `entities` — сущности/query keys; `shared` — независимые API/UI/lib. Импорты только вниз по слоям, внутри feature допустимы локальные импорты. Не заводить `src/pages`.
- Новую feature создавать вокруг пользовательского действия. HTTP вынести в API-модуль, форму — в RHF + Zod, server state — в TanStack Query. Не дублировать server state в Zustand/useState. Не создавать слой ради одного forwarding-компонента.
- API брать из `../docs/openapi.yaml` и сверять с существующим backend. Запустить `npm run api:generate`; не редактировать `shared/api/schema.d.ts`. Расхождения описывать в `../docs/frontend-analysis.md`, API backend не менять.
- Все запросы через `shared/api/client.ts`. Относительные URL, `credentials: include`, cookie-session и CSRF. Никаких JWT/localStorage, секретов, автоматического replay мутаций, HTTP в JSX. Изменения сессии сбрасывают CSRF и приватный query cache.
- PATCH title поддерживается фактическим backend, хотя отсутствует в OpenAPI; локальное расширение только в `entities/task/api.ts`, типы полей выводить из generated Task.
- UI primitives в `shared/ui`, варианты на Radix/shadcn; дизайн-токены Figma в `app/globals.css`. Не заменять макет стандартной темой shadcn. Исходный SVG глаза — `public/figma/eye.svg`, 20×20; не использовать временные Figma URL.
- Каждый экран имеет loading/empty/error/success состояния, labels, клавиатурный фокус и адаптивность. Пароли не нормализовать, длину считать по Unicode code points как backend.
- Server Components использовать для маршрутов/статической композиции; Client Components — для browser-session, форм и интерактивности. Production routing через ingress; dev rewrites нужны из-за отключённого backend CORS. Не добавлять общий BFF или прямой доступ к БД. Единственное обоснованное исключение — Node route handlers существующих Google start/callback URL: они передают один запрос в Spring и сохраняют cookies/разрешённые редиректы, ошибки переводят на HTML-страницу входа. Не переносить сюда OAuth-проверки или бизнес-логику; server helper находится в `features/auth/server`. В production эти два пути идут в Next, `/api/*` — прямо в Spring.
- Проверки: `npm run lint`, `npm run typecheck`, `npm run test`, `npm run build`; для пользовательских сценариев `npm run test:e2e`, для форматирования `npm run format:check`. E2E mocks только в тестах. Не подавлять ошибки через any, ts-ignore, eslint-disable или ослабление strict.

<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
