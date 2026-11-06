# Список задач

Учебный веб-проект To-Do List: добавление, отметка выполнения и удаление задач с сохранением в базе данных.

Backend: Java + Spring Boot + ORM + миграции. Frontend-стек пока не выбран.

В требования добавлены вход через Google или email и личный список задач. Для email пока предполагается вход с паролем, подтверждение адреса и восстановление доступа.

- [Разбор задачи и план реализации](docs/task-analysis.md)
- [Контракт API (OpenAPI 3.1)](docs/openapi.yaml)
- [Правила разработки и именования коммитов](AGENTS.md)

Статус: реализованы backend HTTP API, repo/service layers, редактирование задач, Spring Security, Google OIDC и PostgreSQL-сессии. SMTP-адаптер и локальный Mailpit готовы; для production нужны почтовые credentials и публичный origin. [Настройка и проверка backend](backend/README.md).

AI-инструмент: Codex — подготовка репозитория, разбор требований, API-контракт и реализация backend, HTTP/security и редактирования задач с unit- и интеграционными проверками.
