import { expect, type Page } from "@playwright/test";
import { z } from "zod";
import type { components } from "../../src/shared/api/schema";
export const titles = [
  "Подготовить вопросы к встрече",
  "Забрать книгу из пункта выдачи",
  "Перечитать заметки после встречи и собрать в одном документе вопросы, которые нужно обсудить с командой перед следующим этапом работы",
  "Прогуляться без телефона",
  "Навести порядок на рабочем столе",
];
const user: components["schemas"]["User"] = {
  id: "00000000-0000-4000-8000-000000000001",
  email: "anna.petrova@example.ru",
  emailVerified: true,
  createdAt: "2026-11-06T00:00:00Z",
};
export async function mockApi(page: Page, signedIn = false) {
  const state = {
    signedIn,
    failNextPatch: false,
    expireNextTask: false,
    rateLimitLogin: false,
    csrfCount: 0,
    writes: [] as { path: string; body: unknown }[],
    tasks: titles.map((title, i) => ({
      id: `00000000-0000-4000-8000-00000000000${i + 2}`,
      title,
      completed: i > 2,
      createdAt: "2026-11-06T00:00:00Z",
      updatedAt: "2026-11-06T00:00:00Z",
    })),
  };
  await page.route("**/api/**", async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    const method = req.method();
    const json = (
      body: unknown,
      status = 200,
      headers: Record<string, string> = {},
    ) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(body),
        headers,
      });
    const problem = (
      status: number,
      code: string,
      headers: Record<string, string> = {},
    ) =>
      json({ type: "about:blank", title: code, status, code }, status, headers);
    if (path === "/api/auth/csrf") {
      state.csrfCount++;
      return json(
        { headerName: "X-CSRF-TOKEN", token: `csrf-${state.csrfCount}` },
        200,
        { "Set-Cookie": "JSESSIONID=e2e; HttpOnly; SameSite=Lax; Path=/" },
      );
    }
    if (path === "/api/auth/me")
      return state.signedIn ? json(user) : problem(401, "unauthenticated");
    const body: unknown = req.postData() ? req.postDataJSON() : undefined;
    if (method !== "GET") {
      expect(req.headers()["x-csrf-token"]).toMatch(/^csrf-/);
      state.writes.push({ path, body });
    }
    if (path === "/api/auth/login") {
      if (state.rateLimitLogin)
        return problem(429, "rate_limited", { "Retry-After": "30" });
      const data = z
        .object({ email: z.string(), password: z.string() })
        .parse(body);
      if (data.password !== "correct-password")
        return problem(401, "invalid_credentials");
      state.signedIn = true;
      return json(user);
    }
    if (path === "/api/auth/logout") {
      state.signedIn = false;
      return route.fulfill({ status: 204 });
    }
    if (
      [
        "/api/auth/register",
        "/api/auth/resend-verification",
        "/api/auth/forgot-password",
      ].includes(path)
    )
      return json(
        {
          message:
            "Если действие доступно для этого адреса, письмо будет отправлено.",
        },
        202,
      );
    if (
      path === "/api/auth/verify-email" ||
      path === "/api/auth/reset-password"
    ) {
      const data = z.object({ token: z.string() }).parse(body);
      if (data.token !== "valid-token") return problem(400, "invalid_token");
      return route.fulfill({ status: 204 });
    }
    if (path.startsWith("/api/tasks")) {
      if (state.expireNextTask) {
        state.signedIn = false;
        state.expireNextTask = false;
      }
      if (!state.signedIn) return problem(401, "unauthenticated");
      if (path === "/api/tasks" && method === "GET") return json(state.tasks);
      if (path === "/api/tasks" && method === "POST") {
        const data = z.object({ title: z.string() }).strict().parse(body);
        const task = {
          ...data,
          id: crypto.randomUUID(),
          completed: false,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        };
        state.tasks.unshift(task);
        return json(task, 201);
      }
      const id = path.split("/").at(-1);
      const task = state.tasks.find((t) => t.id === id);
      if (!task) return problem(404, "task_not_found");
      if (method === "PATCH") {
        if (state.failNextPatch) {
          state.failNextPatch = false;
          return problem(500, "internal_error");
        }
        const data = z
          .object({
            title: z.string().optional(),
            completed: z.boolean().optional(),
          })
          .strict()
          .parse(body);
        Object.assign(task, data);
        return json(task);
      }
      if (method === "DELETE") {
        state.tasks = state.tasks.filter((t) => t.id !== id);
        return route.fulfill({ status: 204 });
      }
      return json(task);
    }
    return problem(404, "not_found");
  });
  return state;
}
