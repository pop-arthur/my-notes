import { test, expect } from "@playwright/test";
import { mockApi } from "./api-fixture";

const cases = [
  {
    path: "/login",
    endpoint: "/api/auth/login",
    inputs: { Email: "test@example.com", Пароль: "correct-password" },
    submit: "Войти",
    status: 500,
    html: true,
    message: "Не удалось выполнить действие",
  },
  {
    path: "/register",
    endpoint: "/api/auth/register",
    inputs: { Email: "test@example.com", Пароль: "correct-password" },
    submit: "Создать аккаунт",
    status: 503,
    code: "mail_delivery_unavailable",
    message: "Отправка писем пока недоступна",
  },
  {
    path: "/forgot-password",
    endpoint: "/api/auth/forgot-password",
    inputs: { Email: "test@example.com" },
    submit: "Отправить ссылку",
    network: true,
    message: "Не удалось связаться с сервером",
  },
  {
    path: "/check-email",
    endpoint: "/api/auth/resend-verification",
    inputs: { Email: "test@example.com" },
    submit: "Отправить письмо повторно",
    status: 429,
    code: "rate_limited",
    message: "Слишком много запросов",
  },
  {
    path: "/verify-email?token=expired",
    endpoint: "/api/auth/verify-email",
    inputs: {},
    submit: "Подтвердить почту",
    status: 400,
    code: "invalid_token",
    message: "Ссылка недействительна",
  },
  {
    path: "/reset-password?token=expired",
    endpoint: "/api/auth/reset-password",
    inputs: { "Новый пароль": "correct-password" },
    submit: "Сохранить пароль",
    status: 502,
    html: true,
    message: "Не удалось выполнить действие",
  },
];

for (const scenario of cases) {
  test(`handles API failure inside ${scenario.path.split("?")[0]}`, async ({
    page,
  }) => {
    await mockApi(page);
    await page.route(`**${scenario.endpoint}`, async (route) => {
      if (scenario.network) return route.abort("failed");
      return route.fulfill({
        status: scenario.status,
        contentType: scenario.html ? "text/html" : "application/problem+json",
        headers: scenario.status === 429 ? { "Retry-After": "30" } : {},
        body: scenario.html
          ? "<html><body>PRIVATE_ERROR: stack trace</body></html>"
          : JSON.stringify({
              code: scenario.code,
              detail: "PRIVATE_ERROR: stack trace",
              status: scenario.status,
            }),
      });
    });
    await page.goto(scenario.path);
    for (const [label, value] of Object.entries(scenario.inputs)) {
      await page.getByLabel(label, { exact: true }).fill(value);
    }
    await page
      .getByRole("button", { name: scenario.submit, exact: true })
      .click();
    await expect(page.getByRole("main").getByRole("alert")).toContainText(
      scenario.message,
    );
    await expect(page).toHaveURL(
      new RegExp(`${scenario.path.split("?")[0]}(?:\\?|$)`),
    );
    await expect(page.getByRole("main")).not.toContainText("PRIVATE_ERROR");
    await expect(page.getByRole("main")).not.toContainText("stack trace");
  });
}
