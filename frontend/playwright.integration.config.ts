import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/integration",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: "list",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  // Email links carry secrets: do not persist traces, screenshots or video.
  use: {
    baseURL: "http://127.0.0.1:3200",
    trace: "off",
    screenshot: "off",
    video: "off",
  },
  projects: [
    { name: "real-chromium", use: { ...devices["Desktop Chrome"] } },
    {
      name: "real-mobile",
      use: { ...devices["iPhone 13"], defaultBrowserType: "chromium" },
    },
  ],
  webServer: [
    {
      command: "node scripts/integration-backend.mjs",
      url: "http://127.0.0.1:18080/actuator/health",
      reuseExistingServer: false,
      timeout: 240_000,
      gracefulShutdown: { signal: "SIGTERM", timeout: 10_000 },
    },
    {
      command: "npm run dev -- --hostname 127.0.0.1 --port 3200",
      url: "http://127.0.0.1:3200/login",
      env: {
        BACKEND_ORIGIN: "http://127.0.0.1:18080",
        NEXT_DIST_DIR: ".next-integration",
      },
      reuseExistingServer: false,
      timeout: 120_000,
    },
  ],
});
