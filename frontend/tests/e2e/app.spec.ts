import { test, expect } from "@playwright/test";
import { mockApi, titles } from "./api-fixture";

test("email login, create/edit/complete/delete, persistence and logout", async ({
  page,
}) => {
  const state = await mockApi(page);
  await page.goto("/");
  await expect(page).toHaveURL(/\/login/);
  await page
    .getByLabel("Email", { exact: true })
    .fill("anna.petrova@example.ru");
  await page.getByLabel("Пароль", { exact: true }).fill("wrong-password");
  await page.getByRole("button", { name: "Войти", exact: true }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "Неверная почта или пароль",
  );
  await page.getByLabel("Пароль", { exact: true }).fill("correct-password");
  await page.getByRole("button", { name: "Войти", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Мои задачи" })).toBeVisible();
  await page.getByLabel("Новая задача").fill("Новая задача для проверки");
  await page.getByRole("button", { name: "Добавить", exact: true }).click();
  await expect(
    page.getByText("Новая задача для проверки", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", {
      name: "Редактировать: Новая задача для проверки",
      exact: true,
    })
    .click();
  await page.getByLabel("Название задачи").fill("Обновлённая задача");
  await page.getByLabel("Название задачи").press("Enter");
  await expect(
    page.getByText("Обновлённая задача", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("checkbox", {
      name: "Отметить выполненной: Обновлённая задача",
      exact: true,
    })
    .click();
  await expect(
    page.getByRole("checkbox", {
      name: "Вернуть в невыполненные: Обновлённая задача",
      exact: true,
    }),
  ).toBeChecked();
  await page.reload();
  await expect(
    page.getByText("Обновлённая задача", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Удалить: Обновлённая задача", exact: true })
    .click();
  await expect(
    page.getByText("Обновлённая задача", { exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Выйти", exact: true }).click();
  await expect(page).toHaveURL(/\/login/);
  expect(state.csrfCount).toBeGreaterThanOrEqual(2);
  expect(state.tasks).toHaveLength(5);
  await page
    .getByRole("link", { name: "Создать аккаунт", exact: true })
    .click();
  await expect(page).toHaveURL(/\/register$/);
  await expect(
    page.getByRole("heading", { name: "Создать аккаунт", exact: true }),
  ).toBeVisible();
  await page
    .getByLabel("Email", { exact: true })
    .fill("next-signup@example.com");
  await page.getByLabel("Пароль", { exact: true }).fill("correct-password");
  await page
    .getByRole("button", { name: "Создать аккаунт", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Проверьте почту" }),
  ).toBeVisible();
});

test("registration, verification, recovery and password reset", async ({
  page,
}) => {
  await mockApi(page);
  await page.goto("/register");
  await page
    .getByLabel("Email", { exact: true })
    .fill("anna.petrova@example.ru");
  await page.getByLabel("Пароль", { exact: true }).fill("short");
  await page
    .getByRole("button", { name: "Создать аккаунт", exact: true })
    .click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "От 12 до 128 символов",
  );
  await page.getByLabel("Пароль", { exact: true }).fill("correct-password");
  await page
    .getByRole("button", { name: "Создать аккаунт", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Проверьте почту" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Отправить письмо повторно" }).click();
  await expect(
    page.getByText(
      "Если действие доступно для этого адреса, письмо будет отправлено.",
      { exact: true },
    ),
  ).toBeVisible();
  await page.goto("/verify-email?token=valid-token");
  await page.getByRole("button", { name: "Подтвердить почту" }).click();
  await expect(
    page.getByRole("heading", { name: "Почта подтверждена" }),
  ).toBeVisible();
  await page.goto("/forgot-password");
  await page
    .getByLabel("Email", { exact: true })
    .fill("anna.petrova@example.ru");
  await page.getByRole("button", { name: "Отправить ссылку" }).click();
  await expect(page.getByRole("main").getByRole("status")).toContainText(
    "письмо будет отправлено",
  );
  await page.goto("/reset-password?token=valid-token");
  await page
    .getByLabel("Новый пароль", { exact: true })
    .fill("new-password-123");
  await page.getByRole("button", { name: "Сохранить пароль" }).click();
  await expect(
    page.getByRole("heading", { name: "Пароль обновлён" }),
  ).toBeVisible();
});

test("failed edit retains draft, Escape cancels, keyboard focus returns", async ({
  page,
}) => {
  const state = await mockApi(page, true);
  await page.goto("/");
  const edit = page.getByRole("button", {
    name: `Редактировать: ${titles[0]}`,
    exact: true,
  });
  await edit.click();
  const editor = page.getByLabel("Название задачи");
  await expect(editor).toBeFocused();
  await editor.fill("Черновик после ошибки");
  state.failNextPatch = true;
  await page.getByRole("button", { name: "Сохранить", exact: true }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "Не удалось выполнить действие",
  );
  await expect(editor).toHaveValue("Черновик после ошибки");
  await editor.press("Escape");
  await expect(editor).toHaveCount(0);
  await expect(edit).toBeFocused();
  expect(state.tasks[0]?.title).toBe(titles[0]);
});

test("rate limiting, invalid email link and Google error", async ({ page }) => {
  const state = await mockApi(page);
  state.rateLimitLogin = true;
  await page.goto("/login");
  await page
    .getByLabel("Email", { exact: true })
    .fill("anna.petrova@example.ru");
  await page.getByLabel("Пароль", { exact: true }).fill("correct-password");
  await page.getByRole("button", { name: "Войти", exact: true }).click();
  await expect(
    page.getByRole("button", { name: /Повторить через/ }),
  ).toBeDisabled();
  await page.goto("/verify-email?token=expired");
  await page.getByRole("button", { name: "Подтвердить почту" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "Ссылка недействительна",
  );
  await expect(
    page.getByRole("link", { name: "Запросить новую ссылку" }),
  ).toHaveAttribute("href", "/check-email");
  await page.goto("/login?error=oauth_failed");
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "Не удалось войти через Google",
  );
  await expect(
    page.getByRole("link", { name: "Войти через Google" }),
  ).toHaveAttribute("href", "/oauth2/authorization/google");
});

test("expired session leaves private screen", async ({ page }) => {
  const state = await mockApi(page, true);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Мои задачи" })).toBeVisible();
  state.expireNextTask = true;
  await page.getByLabel("Новая задача").fill("Не сохранять после выхода");
  await page.getByRole("button", { name: "Добавить", exact: true }).click();
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByText(titles[0]!, { exact: true })).toHaveCount(0);
});

test("Figma layouts and editor fit viewport", async ({ page }, testInfo) => {
  await mockApi(page, true);
  await page.goto("/");
  await expect(page.getByText(titles[0]!, { exact: true })).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("tasks.png"),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page
    .getByRole("button", { name: `Редактировать: ${titles[0]}`, exact: true })
    .click();
  await page.screenshot({
    path: testInfo.outputPath("editor.png"),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.goto("/login");
  await page.screenshot({
    path: testInfo.outputPath("login.png"),
    fullPage: true,
  });
});
