import ts from "typescript";
import { readFileSync, mkdirSync, writeFileSync, existsSync } from "node:fs";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
const root = fileURLToPath(new URL("../", import.meta.url));
if (!existsSync(`${root}/.open-next/worker.js`))
  throw new Error("Run npm run worker:build first");
const parsed = ts.parseConfigFileTextToJson(
  "wrangler.jsonc",
  readFileSync(`${root}/wrangler.jsonc`, "utf8"),
);
if (parsed.error) throw new Error("Invalid Wrangler configuration");
const config = parsed.config;
config.main = `${root}/worker/index.ts`;
config.assets.directory = `${root}/.open-next/assets`;
config.vpc_services = [];
delete config.account_id;
config.services.push({ binding: "BACKEND", service: "backend-runtime-test" });
config.vars = {
  EDGE_PROXY_SECRET: "runtime-test-secret-with-at-least-32-characters",
};
mkdirSync(`${root}/.wrangler/runtime`, { recursive: true });
writeFileSync(
  `${root}/.wrangler/runtime/wrangler.json`,
  JSON.stringify(config),
);
const child = spawn(
  process.execPath,
  [
    `${root}/node_modules/wrangler/bin/wrangler.js`,
    "dev",
    "--local",
    "--config",
    `${root}/.wrangler/runtime/wrangler.json`,
    "--config",
    `${root}/tests/worker/wrangler.jsonc`,
    "--ip",
    "127.0.0.1",
    "--port",
    "8789",
    "--log-level",
    "error",
  ],
  { stdio: "inherit", cwd: root },
);
const base = "http://127.0.0.1:8789";
try {
  const deadline = Date.now() + 90_000;
  while (true) {
    try {
      if ((await fetch(`${base}/login`)).ok) break;
    } catch {
      /* Starting. */
    }
    if (child.exitCode !== null || Date.now() > deadline)
      throw new Error("Worker runtime did not start");
    await new Promise((r) => setTimeout(r, 500));
  }
  const response = await fetch(`${base}/api/inspect`, {
    method: "PATCH",
    headers: {
      "CF-Connecting-IP": "203.0.113.1",
      Cookie: "JSESSIONID=browser",
      "X-CSRF-TOKEN": "csrf",
      "X-Forwarded-For": "spoof",
      "X-My-Notes-Client-IP": "spoof",
      "Content-Type": "application/json",
    },
    body: '{"completed":true}',
  });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(response.headers.getSetCookie().length, 2);
  const value = await response.json();
  assert.equal(value.method, "PATCH");
  assert.equal(value.cookie, "JSESSIONID=browser");
  assert.equal(value.csrf, "csrf");
  assert.notEqual(value.client, "spoof");
  assert.equal(value.forwarded, null);
  assert.equal(value.proxyAuthenticated, true);
  assert.equal(value.body, '{"completed":true}');
  assert.equal(
    (await fetch(`${base}/api/empty`, { method: "DELETE" })).status,
    204,
  );
  const failure = await fetch(`${base}/api/fail`);
  assert.equal(failure.status, 502);
  assert.equal((await failure.json()).code, "internal_error");
  const start = await fetch(`${base}/oauth2/authorization/google`, {
    redirect: "manual",
  });
  assert.equal(start.status, 302);
  assert.equal(
    new URL(start.headers.get("Location")).origin,
    "https://accounts.google.com",
  );
  assert.equal(start.headers.getSetCookie().length, 2);
  const callback = await fetch(
    `${base}/login/oauth2/code/google?code=test&state=test`,
    { redirect: "manual" },
  );
  assert.equal(callback.status, 302);
  assert.equal(callback.headers.get("Location"), "/");
  assert.deepEqual(await (await fetch(`${base}/actuator/health`)).json(), {
    status: "UP",
  });
  console.log(
    "Worker runtime checks passed: Next, API, cookie/CSRF, trusted IP, 502 and OAuth navigation",
  );
} finally {
  child.kill("SIGTERM");
  if (child.exitCode === null && child.signalCode === null)
    await new Promise((r) => child.once("exit", r));
}
