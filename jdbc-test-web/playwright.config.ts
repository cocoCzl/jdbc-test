import { defineConfig, devices } from "@playwright/test";

const production = process.env.JDBC_TEST_E2E_MODE === "production";
const baseURL = process.env.JDBC_TEST_E2E_BASE_URL ??
  (production ? "http://127.0.0.1:8080" : "http://127.0.0.1:5173");

export default defineConfig({
  testDir: "./e2e",
  outputDir: "test-results/artifacts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [
    ["list"],
    ["json", { outputFile: "test-results/e2e-results.json" }],
  ],
  use: {
    ...devices["Desktop Chrome"],
    channel: process.env.PLAYWRIGHT_CHANNEL,
    baseURL,
    viewport: { width: 1440, height: 1000 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "mocked", testMatch: "**/smoke.spec.ts" },
    { name: "real", testMatch: "**/real.spec.ts", use: { trace: "off" } },
  ],
  webServer: production ? undefined : {
    command: "npm run dev",
    url: "http://127.0.0.1:5173",
    reuseExistingServer: !process.env.CI,
  },
});
