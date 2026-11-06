import { execFileSync, spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
const backend = fileURLToPath(new URL("../../backend/", import.meta.url)),
  containers = [];
let child,
  stopping = false;
function cleanup(code = 0) {
  if (stopping) return;
  stopping = true;
  child?.kill("SIGTERM");
  for (const id of containers.reverse()) {
    try {
      execFileSync("docker", ["rm", "-f", id], { stdio: "ignore" });
    } catch {
      /* Already removed. */
    }
  }
  process.exit(code);
}
process.on("SIGTERM", () => cleanup());
process.on("SIGINT", () => cleanup());
const docker = (args) =>
  execFileSync("docker", args, { encoding: "utf8" }).trim();
function container(args) {
  const id = docker(["run", "--rm", "--detach", ...args]);
  containers.push(id);
  return id;
}
const port = (id, name) => docker(["port", id, name]).split(":").at(-1);
try {
  const password = randomUUID();
  const db = container([
    "--publish",
    "127.0.0.1::5432",
    "--env",
    "POSTGRES_USER=integration",
    "--env",
    `POSTGRES_PASSWORD=${password}`,
    "--env",
    "POSTGRES_DB=my_notes",
    "postgres:18",
  ]);
  const mail = container([
    "--publish",
    "127.0.0.1::1025",
    "--publish",
    "127.0.0.1:18025:8025",
    "axllent/mailpit:v1.31.1",
  ]);
  const deadline = Date.now() + 180_000;
  while (true) {
    try {
      docker(["exec", db, "pg_isready", "-U", "integration", "-d", "my_notes"]);
      break;
    } catch {
      if (Date.now() > deadline) throw new Error("PostgreSQL timeout");
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  await new Promise((resolve, reject) => {
    child = spawn("./mvnw", ["--batch-mode", "-DskipTests", "package"], {
      cwd: backend,
      stdio: "inherit",
    });
    child.once("error", reject);
    child.once("exit", (code) =>
      code === 0 ? resolve() : reject(new Error("Backend build failed")),
    );
  });
  const runtime = Object.fromEntries(
    ["PATH", "JAVA_HOME", "HOME", "TMPDIR", "TEMP", "SystemRoot"].flatMap(
      (k) => (process.env[k] === undefined ? [] : [[k, process.env[k]]]),
    ),
  );
  child = spawn("java", ["-jar", "target/my-notes-0.0.1-SNAPSHOT.jar"], {
    cwd: backend,
    stdio: "inherit",
    env: {
      ...runtime,
      SERVER_ADDRESS: "127.0.0.1",
      SERVER_PORT: "18080",
      SPRING_PROFILES_ACTIVE: "local",
      EDGE_PROXY_SECRET: "",
      DB_URL: `jdbc:postgresql://127.0.0.1:${port(db, "5432/tcp")}/my_notes`,
      DB_USERNAME: "integration",
      DB_PASSWORD: password,
      MAIL_ENABLED: "true",
      SMTP_HOST: "127.0.0.1",
      SMTP_PORT: port(mail, "1025/tcp"),
      SMTP_USERNAME: "",
      SMTP_PASSWORD: "",
      SMTP_TLS_MODE: "none",
      MAIL_FROM: "no-reply@example.test",
      APP_PUBLIC_ORIGIN: "http://127.0.0.1:3200",
      GOOGLE_OAUTH_ENABLED: "false",
    },
  });
  child.once("error", () => cleanup(1));
  child.once("exit", (code) => cleanup(code ?? 1));
} catch {
  console.error(
    "Integration stack failed; check Docker, Java 21 and Maven output",
  );
  cleanup(1);
}
