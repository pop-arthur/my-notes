import assert from "node:assert/strict";
import { chromium } from "@playwright/test";
const origin = "https://my-notes.pop-arthur-c189baa0.workers.dev";
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  await page.goto(origin + "/login");
  await page
    .getByLabel("Email", { exact: true })
    .fill("deployment-check@example.test");
  await page
    .getByLabel("Пароль", { exact: true })
    .fill("not-a-real-account-password");
  await page.getByRole("button", { name: "Войти", exact: true }).click();
  await page
    .getByRole("main")
    .getByRole("alert")
    .filter({ hasText: "Неверная почта или пароль" })
    .waitFor();
  assert(
    (await page.context().cookies()).some(
      (c) =>
        c.name === "JSESSIONID" &&
        c.secure &&
        c.httpOnly &&
        c.sameSite === "Lax",
    ),
  );
  const health = await page.request.get(origin + "/actuator/health");
  assert.equal(health.status(), 200);
  assert.deepEqual(await health.json(), { status: "UP" });
  assert.equal((await page.request.get(origin + "/api/auth/me")).status(), 401);
  const csrf = await page.request.get(origin + "/api/auth/csrf");
  assert.equal(csrf.status(), 200);
  assert.equal(csrf.headers()["cache-control"], "no-store");
  const denied = await page.request.post(origin + "/api/auth/login", {
    data: {
      email: "deployment-check@example.test",
      password: "not-a-real-account-password",
    },
  });
  assert.equal(denied.status(), 403);
  console.log(
    "Public browser checks passed: HTML, safe login failure, secure session, health, CSRF and unauthenticated API",
  );
} finally {
  await browser.close();
}
