import { chooseOption } from "./select";
import { test, expect } from "@playwright/test";
import { adapters, driver, connection, catalog, finishedRun, page as paged } from "../src/test/fixtures";

test("TEST FIXTURE ONLY: imported adapter is local and reaches the connection form in both languages", async ({ page }) => {
  const custom = { ...adapters[0], id: "private-db", name: "Private database", version: "2", revision: "r2", origin: "IMPORTED", status: "LOCAL", fingerprint: "b".repeat(64), dialect: "postgresql", validatedCombinations: [] };
  const customConnection = { ...connection, adapterId: custom.id, executionDatabase: "owned_db", executionSchema: "owned_schema" };
  const result = { ...finishedRun, connection: { ...finishedRun.connection, adapterId: custom.id }, adapterSnapshot: custom };
  let imported = false;
  let submittedRun: Record<string, unknown> | undefined;
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/adapters" && route.request().method() === "POST") {
      expect(route.request().headers()["content-type"]).toContain("multipart/form-data");
      imported = true;
      return route.fulfill({ json: custom });
    }
    const path = url.pathname;
    if (path === "/api/runs" && route.request().method() === "POST") {
      submittedRun = route.request().postDataJSON();
      return route.fulfill({ status: 201, json: result });
    }
    if (path === "/api/connections" && route.request().method() === "POST") {
      expect(route.request().postDataJSON().adapterId).toBe(custom.id);
      return route.fulfill({ status: 201, json: customConnection });
    }
    const body = path === "/api/adapters" ? [...adapters, ...(imported ? [custom] : [])]
      : path === "/api/connections/connection-1" ? customConnection
      : path === "/api/connections" ? paged([customConnection])
      : path === "/api/test-catalog" ? catalog
      : path === "/api/drivers/driver-1" ? driver
      : path.endsWith("/report") ? { summary: result, failures: [] }
      : path === "/api/runs/run-1" ? result : paged([driver]);
    return route.fulfill({ json: body });
  });
  await page.goto("/adapters");
  await page.getByLabel("Adapter ZIP (maximum 8 MiB unpacked)").setInputFiles({ name: "fixture.zip", mimeType: "application/zip", buffer: Buffer.from("TEST FIXTURE ONLY") });
  await page.getByRole("button", { name: "Validate and import" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Format and resources validated" })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath("adapter-import-fixture-en-desktop.png"), fullPage: true });
  await page.locator('a[href="/connections/new?adapterId=private-db"]').first().click();
  await expect(page.getByLabel("Adapter", { exact: true })).toContainText("Private database");
  await expect(page.getByText(custom.fingerprint, { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "Language", exact: true }).click();
  await page.getByRole("menuitemradio", { name: "简体中文" }).click();
  await expect(page.getByText("用户导入 · 本地", { exact: false })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: test.info().outputPath("imported-adapter-fixture-zh-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "界面语言", exact: true }).click();
  await page.getByRole("menuitemradio", { name: "English" }).click();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByLabel("Name", { exact: true }).fill("Test database");
  await chooseOption(page, page.getByLabel("Driver package", { exact: true }), driver.id);
  await page.getByLabel("JDBC URL", { exact: true }).fill("jdbc:fixture:never-contacted");
  await page.locator("#execution-database").fill("owned_db");
  await page.locator("#execution-schema").fill("owned_schema");
  await page.getByRole("button", { name: "Save connection", exact: true }).click();
  await page.getByRole("link", { name: "Start run", exact: true }).click();
  await page.getByRole("button", { name: /^Next:/ }).click();
  await page.getByRole("button", { name: /^Next:/ }).click();
  await page.getByRole("checkbox", { name: "Connection", exact: true }).uncheck();
  await page.getByRole("button", { name: /^Next:/ }).click();
  await page.getByRole("checkbox", { name: /I understand/ }).check();
  await page.getByRole("button", { name: "Start test run", exact: true }).click();
  await expect.poll(() => submittedRun?.expectedAdapterFingerprint).toBe(custom.fingerprint);
  await page.getByRole("tab", { name: "Report", exact: true }).click();
  await expect(page.getByText("Adapter frozen for this task")).toBeVisible();
  await expect(page.getByText(custom.fingerprint, { exact: false })).toBeVisible();
});
