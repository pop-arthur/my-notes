import { randomUUID } from "node:crypto";
import { test, expect, type Page } from "@playwright/test";
import { z } from "zod";
const password = "Исходный-пароль-🔐123",
  nextPassword = "Обновлённый-пароль-🔐456";
async function link(email: string, path: string) {
  let result: URL | undefined;
  await expect(async () => {
    const list = z
      .object({
        messages: z.array(
          z.object({
            ID: z.string(),
            To: z.array(z.object({ Address: z.string() })),
          }),
        ),
      })
      .parse(
        await (await fetch("http://127.0.0.1:18025/api/v1/messages")).json(),
      );
    for (const item of list.messages.filter((m) =>
      m.To.some((r) => r.Address === email),
    )) {
      const body = z
        .object({ Text: z.string() })
        .parse(
          await (
            await fetch(`http://127.0.0.1:18025/api/v1/message/${item.ID}`)
          ).json(),
        );
      for (const match of body.Text.matchAll(/http:\/\/[^\s]+/g)) {
        const url = new URL(match[0]);
        if (url.pathname === path) result = url;
      }
    }
    expect(!!result).toBe(true);
  }).toPass({ timeout: 10_000 });
  if (!result || result.origin !== "http://127.0.0.1:3200")
    throw new Error("Email origin mismatch");
  return result.href;
}
async function login(page: Page, email: string, secret = password) {
  await page.goto("/login");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Пароль", { exact: true }).fill(secret);
  await page.getByRole("button", { name: "Войти", exact: true }).click();
}
async function register(page: Page, email: string) {
  await page.goto("/register");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Пароль", { exact: true }).fill(password);
  await page
    .getByRole("button", { name: "Создать аккаунт", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Проверьте почту" }),
  ).toBeVisible();
  await page.goto(await link(email, "/verify-email"));
  await page.getByRole("button", { name: "Подтвердить почту" }).click();
  await expect(
    page.getByRole("heading", { name: "Почта подтверждена" }),
  ).toBeVisible();
}
test("real SMTP, isolated tasks, persistence, password recovery and logout", async ({
  page,
  browser,
}) => {
  const email = `${randomUUID()}@example.test`;
  await register(page, email);
  await login(page, email);
  await expect(page.getByRole("heading", { name: "Мои задачи" })).toBeVisible();
  const cookie = (await page.context().cookies()).find(
    (c) => c.name === "JSESSIONID",
  );
  expect(cookie?.httpOnly).toBe(true);
  expect(cookie?.sameSite).toBe("Lax");
  const title = "Реальная задача 🔐",
    updated = "Изменённая задача ✅";
  await page.getByLabel("Новая задача").fill(`  ${title}  `);
  await page.getByRole("button", { name: "Добавить", exact: true }).click();
  await expect(page.getByText(title, { exact: true })).toBeVisible();
  await page
    .getByRole("button", { name: `Редактировать: ${title}`, exact: true })
    .click();
  await page.getByLabel("Название задачи").fill(updated);
  await page.getByLabel("Название задачи").press("Enter");
  await page
    .getByRole("checkbox", {
      name: `Отметить выполненной: ${updated}`,
      exact: true,
    })
    .click();
  await page.reload();
  await expect(
    page.getByRole("checkbox", {
      name: `Вернуть в невыполненные: ${updated}`,
      exact: true,
    }),
  ).toBeChecked();
  const [task] = z
    .array(z.object({ id: z.string() }))
    .parse(await (await page.request.get("/api/tasks")).json());
  if (!task) throw new Error("Task not persisted");
  const other = await browser.newContext({ baseURL: "http://127.0.0.1:3200" });
  try {
    const second = await other.newPage(),
      otherEmail = `${randomUUID()}@example.test`;
    await register(second, otherEmail);
    await login(second, otherEmail);
    await expect(
      second.getByRole("heading", { name: "Мои задачи" }),
    ).toBeVisible();
    await expect(second.getByText(updated, { exact: true })).toHaveCount(0);
    const foreign = `/api/tasks/${task.id}`;
    expect((await other.request.get(foreign)).status()).toBe(404);
    const csrf = z
      .object({ token: z.string() })
      .parse(await (await other.request.get("/api/auth/csrf")).json());
    const headers = { "X-CSRF-TOKEN": csrf.token };
    expect(
      (
        await other.request.patch(foreign, {
          headers,
          data: { completed: false },
        })
      ).status(),
    ).toBe(404);
    expect((await other.request.delete(foreign, { headers })).status()).toBe(
      404,
    );
    expect(
      (
        await other.request.post("/api/tasks", { data: { title: "Без CSRF" } })
      ).status(),
    ).toBe(403);
    await second.getByRole("button", { name: "Выйти", exact: true }).click();
    await expect(second).toHaveURL(/\/login/);
    expect((await page.request.get("/api/auth/me")).status()).toBe(200);
  } finally {
    await other.close();
  }
  await page.goto("/forgot-password");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByRole("button", { name: "Отправить ссылку" }).click();
  await expect(page.getByRole("main").getByRole("status")).toContainText(
    "письмо будет отправлено",
  );
  await page.goto(await link(email, "/reset-password"));
  await page.getByLabel("Новый пароль", { exact: true }).fill(nextPassword);
  await page.getByRole("button", { name: "Сохранить пароль" }).click();
  await expect(
    page.getByRole("heading", { name: "Пароль обновлён" }),
  ).toBeVisible();
  expect((await page.request.get("/api/auth/me")).status()).toBe(401);
  await login(page, email);
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "Неверная почта или пароль",
  );
  await login(page, email, nextPassword);
  await expect(page.getByText(updated, { exact: true })).toBeVisible();
  await page
    .getByRole("button", { name: `Удалить: ${updated}`, exact: true })
    .click();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Здесь появятся ваши задачи" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Выйти", exact: true }).click();
  await expect(page).toHaveURL(/\/login/);
  expect((await page.request.get("/api/auth/me")).status()).toBe(401);
});
