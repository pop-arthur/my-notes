# Список задач

Полноценное веб-приложение по тестовому заданию: личные задачи, регистрация и адаптивный интерфейс по макетам Figma. Данные хранятся в PostgreSQL и сохраняются после обновления страницы и перезапуска сервера.

**[Открыть сайт](https://my-notes.pop-arthur-c189baa0.workers.dev)** · **[Код на GitHub](https://github.com/pop-arthur/my-notes)**

## Возможности

- Добавление, редактирование, отметка выполнения и удаление задач.
- Регистрация по email, подтверждение адреса и восстановление пароля; SMTP подключён.
- Личный список с проверкой доступа на сервере.
- Мобильная версия, валидация форм, состояния загрузки, пустого списка и ошибок.
- Google-вход реализован через OpenID Connect; в демо пока выключен до настройки OAuth credentials.

## Техническая реализация

**Backend:** Java 21, Spring Boot, Spring Security, JPA / Hibernate через EntityManager, PostgreSQL, Flyway. Слоистая архитектура `http → service → repo`, разделение по областям `tasks` и `identity`, принципы SOLID.

**Frontend:** Next.js App Router, React, TypeScript strict, Tailwind CSS, shadcn/Radix, TanStack Query, React Hook Form и Zod. Типы API генерируются из OpenAPI.

**Качество:** unit- и интеграционные тесты с PostgreSQL Testcontainers, архитектурные проверки ArchUnit, браузерные тесты Playwright и сквозные сценарии с Mailpit. Серверные сессии, CSRF-защита, хеширование паролей и одноразовые email-токены.

**Развёртывание:** frontend — Cloudflare Workers; backend и БД — VPS, доступ к API через закрытый Tunnel/VPC. Сайт и API работают на одном origin.

## Использование AI и запуск

Проект разработан с помощью **Codex**: анализ требований, проектирование API и архитектуры, реализация backend и frontend по Figma, написание тестов и настройка развёртывания.

[Запуск backend](backend/README.md) · [Запуск frontend](frontend/README.md) · [OpenAPI](docs/openapi.yaml) · [Требования](docs/task-analysis.md) · [Развёртывание](docs/cloudflare-deployment.md)
