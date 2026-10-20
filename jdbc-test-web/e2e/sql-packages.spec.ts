import { readFileSync } from "node:fs";
import { test, expect } from "@playwright/test";
import { chooseOption } from "./select";
import { adapters, connection, finishedRun, page as paged } from "../src/test/fixtures";

const manifest = readFileSync(new URL("../../examples/sql/postgresql-basics.json", import.meta.url), "utf8");
const definition = JSON.parse(manifest);

test("TEST FIXTURE ONLY: SQL import, selection and step evidence remain separate from JDBC in both languages", async ({ page }) => {
  let imported = false;
  let created: Record<string, unknown> | undefined;
  const bundle = { fingerprint: "c".repeat(64), origin: "LOCAL", definition };
  const result = { ...finishedRun, profile: "CUSTOM", status: "FAILED", totalCases: 1, completedCases: 1, passed: 0, failed: 1, skipped: 0,
    selectedModules: ["sql_feature"], contractSummary: null,
    sqlPackage: { manifest, fingerprint: bundle.fingerprint },
    selectionSnapshot: { catalogCount: 4, userExcluded: ["null.parameter", "join.aggregate", "expected.error"] },
  };
  const failure = { id: "fixture-result", scenarioId: "basic.query", moduleId: "sql_feature", displayName: "Basic ordered query", status: "FAILED", durationMs: 2,
    executionEvidence: { testDomain: "SQL_FEATURE", conclusion: "SQL_ASSERTION_FAILED", variant: "postgresql", phase: "EXECUTION", contractInvoked: false,
      steps: [{ id: "query", sql: "SELECT 999 AS n", phase: "EXECUTION", status: "FAILED", expected: { columns: ["INTEGER"], rows: [["1"]] }, actual: { rows: [["999"]] } }] },
  };
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/sql-packages") {
      if (route.request().method() === "POST") {
        if (route.request().postDataBuffer()?.includes(Buffer.from("invalid.json"))) return route.fulfill({ status: 422, json: { code: "SQL_PACKAGE_INVALID", detail: "cases[].expect: unsupported FLOAT" } });
        imported = true;
        return route.fulfill({ json: bundle });
      }
      return route.fulfill({ json: imported ? [bundle] : [] });
    }
    if (path === "/api/runs" && route.request().method() === "POST") {
      created = route.request().postDataJSON();
      return route.fulfill({ status: 201, json: result });
    }
    return route.fulfill({ json: path === "/api/adapters" ? adapters.map(item => ({ ...item, fingerprint: "b".repeat(64) }))
      : path === "/api/connections" ? paged([{ ...connection, executionDatabase: "fixture", executionSchema: "owned_scope" }])
      : path.endsWith("/results") ? paged([failure])
      : path.endsWith("/report") ? { summary: result, failures: [failure] }
      : result });
  });
  await page.goto("/sql-packages");
  await page.getByLabel(/SQL package JSON/).setInputFiles({ name: "invalid.json", mimeType: "application/json", buffer: Buffer.from("TEST FIXTURE ONLY") });
  await page.getByRole("button", { name: "Validate and import SQL package" }).click();
  await expect(page.getByText(/cases\[\].expect: unsupported FLOAT/)).toBeVisible();
  await page.getByLabel(/SQL package JSON/).setInputFiles({ name: "sql-package.json", mimeType: "application/json", buffer: Buffer.from(manifest) });
  await page.getByRole("button", { name: "Validate and import SQL package" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Definition validated and saved" })).toBeVisible();
  await chooseOption(page, page.getByLabel("Test package revision"), bundle.fingerprint);
  await page.getByRole("checkbox", { name: /basic.query/ }).check();
  await chooseOption(page, page.getByLabel("Target connection"), connection.id);
  await page.getByRole("checkbox", { name: /I authorize these reviewed SQL/ }).check();
  await page.screenshot({ path: test.info().outputPath("sql-package-selection-fixture-en.png"), fullPage: true });
  await page.getByRole("button", { name: "Start SQL test run" }).click();
  await expect.poll(() => created?.sqlPackageFingerprint).toBe(bundle.fingerprint);
  expect(created?.includeScenarios).toEqual(["basic.query"]);
  expect(created?.profile).toBe("CUSTOM");
  await expect(page.getByText("SQL feature package frozen for this task")).toBeVisible();
  await page.getByRole("tab", { name: "Test cases", exact: true }).click();
  await page.getByRole("button", { name: "SQL step assertions", exact: true }).click();
  await expect(page.getByRole("heading", { name: "SQL step assertions" })).toBeVisible();
  await expect(page.getByText("Expected", { exact: true })).toBeVisible();
  await expect(page.getByText("Actual / failure", { exact: true })).toBeVisible();
  await expect(page.getByText("SELECT 999 AS n", { exact: true })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath("sql-step-failure-fixture-en.png"), fullPage: true });
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Language", exact: true }).click();
  await page.getByRole("menuitemradio", { name: "简体中文" }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: test.info().outputPath("sql-task-fixture-zh-mobile.png"), fullPage: true });
});
