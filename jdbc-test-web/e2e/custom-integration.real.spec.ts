import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import { chooseOption } from "./select";

// Explicit opt-in. Never fall back to fixtures, pre-existing connections or remote targets.
const enabled = process.env.JDBC_TEST_CUSTOM_ACCEPTANCE === "owned-disposable";
const input = process.env.JDBC_TEST_CUSTOM_INPUT ?? "";
const evidence = process.env.JDBC_TEST_CUSTOM_EVIDENCE ?? "";
const stateFile = join(evidence, "browser-state.json");

test.describe("real custom integration acceptance", () => {
  test.skip(!enabled, "Requires a newly owned disposable platform and target environment");
  test.setTimeout(180_000);

  test("browser imports driver and packages, freezes queued tasks and shows SQL assertions", async ({ page, request }) => {
    const config = JSON.parse(readFileSync(join(input, "browser-input.json"), "utf8"));
    const faults: { kind: string; detail: string }[] = [];
    const traffic: { path: string; method: string; status: number }[] = [];
    page.on("pageerror", error => faults.push({ kind: "BROWSER", detail: error.message }));
    page.on("response", response => {
      const path = new URL(response.url()).pathname;
      if (path.startsWith("/api/")) {
        traffic.push({ path, method: response.request().method(), status: response.status() });
        if (response.status() >= 400) faults.push({ kind: "API", detail: `${response.status()} ${path}` });
      }
    });
    try {
      await page.goto("/drivers/new");
      await page.getByLabel("Name", { exact: true }).fill("Disposable vendor driver");
      await page.getByLabel("Vendor", { exact: true }).fill("PostgreSQL");
      await page.getByLabel("Version", { exact: true }).fill(config.driverVersion);
      await page.getByLabel("JAR files").setInputFiles(config.driverJar);
      const uploaded = page.waitForResponse(r => r.url().endsWith("/api/drivers") && r.request().method() === "POST");
      await page.getByRole("button", { name: "Upload driver package" }).click();
      const driverResponse = await uploaded;
      expect(driverResponse.status()).toBe(201);
      const driver = await driverResponse.json();
      await page.getByRole("button", { name: "Validate driver" }).click();
      await expect(page.getByText("Validated in an isolated Worker")).toBeVisible();

      await page.goto("/adapters");
      await page.getByLabel("Adapter ZIP (maximum 8 MiB unpacked)").setInputFiles(join(input, "adapter-v1.zip"));
      const imported = page.waitForResponse(r => new URL(r.url()).pathname === "/api/adapters" && r.request().method() === "POST");
      await page.getByRole("button", { name: "Validate and import", exact: true }).click();
      const adapter = await (await imported).json();
      expect(adapter).toMatchObject({ id: config.adapterId, status: "LOCAL", origin: "IMPORTED", version: "1.0.0", dialect: "postgresql" });
      await expect(page.getByRole("status").filter({ hasText: "Format and resources validated" })).toBeVisible();
      await page.screenshot({ path: join(evidence, "adapter-local.png"), fullPage: true });
      await page.locator(`a[href="/connections/new?adapterId=${config.adapterId}"]`).first().click();
      await page.getByLabel("Name", { exact: true }).fill("Disposable vendor SQL target");
      await chooseOption(page, page.getByLabel("Driver package", { exact: true }), driver.id);
      await page.getByLabel("JDBC URL", { exact: true }).fill(config.url);
      await page.getByLabel("Username", { exact: true }).fill(config.username);
      await page.getByLabel("Authorized test database", { exact: true }).fill(config.database);
      await page.getByLabel("Authorized test schema", { exact: true }).fill(config.schema);
      await page.getByLabel("Password", { exact: true }).fill(config.password);
      await page.getByRole("button", { name: "Test connection", exact: true }).click();
      await expect(page.getByText("Connected", { exact: true })).toBeVisible();
      await page.getByLabel("Password", { exact: true }).fill(config.password);
      await page.getByLabel("Save password", { exact: true }).check();
      const saved = page.waitForResponse(r => r.url().endsWith("/api/connections") && r.request().method() === "POST");
      await page.getByRole("button", { name: "Save connection", exact: true }).click();
      const connection = await (await saved).json();
      await expect(page.getByText("Saved password available")).toBeVisible();

      const uploadSql = async (file: string) => {
        await page.goto("/sql-packages");
        await page.getByLabel(/SQL package JSON/).setInputFiles(join(input, file));
        const response = page.waitForResponse(r => r.url().endsWith("/api/sql-packages") && r.request().method() === "POST");
        await page.getByRole("button", { name: "Validate and import SQL package" }).click();
        const result = await response;
        expect(result.status()).toBe(200);
        await expect(page.getByRole("status").filter({ hasText: "Definition validated and saved" })).toBeVisible();
        return result.json();
      };
      const sql = await uploadSql("sql-v1.json");
      const start = async (cases: string[]) => {
        await page.goto("/sql-packages");
        await chooseOption(page, page.getByLabel("Test package revision"), sql.fingerprint);
        for (const id of cases) await page.getByRole("checkbox", { name: new RegExp(`^${id.replaceAll(".", "\\.")} ·`) }).check();
        await chooseOption(page, page.getByLabel("Target connection"), connection.id);
        await page.getByRole("checkbox", { name: /I authorize these reviewed SQL/ }).check();
        const response = page.waitForResponse(r => r.url().endsWith("/api/runs") && r.request().method() === "POST");
        await page.getByRole("button", { name: "Start SQL test run" }).click();
        const result = await response;
        expect(result.status()).toBe(201);
        const run = await result.json();
        await expect(page).toHaveURL(new RegExp(`/runs/${run.id}$`));
        return run.id as string;
      };
      const holder = await start(["hold.sleep"]);
      await expect.poll(async () => (await (await request.get(`/api/runs/${holder}`)).json()).status).toBe("RUNNING");
      const runId = await start(["basic.query", "null.parameter", "join.aggregate", "expected.error", "intentional.failure"]);
      const before = await (await request.get(`/api/runs/${runId}`)).json();
      expect(before.status).toBe("QUEUED");
      await page.screenshot({ path: join(evidence, `${runId}-queued.png`), fullPage: true });

      // Update both libraries while the original task is queued behind a real Worker.
      await page.goto("/adapters");
      await chooseOption(page, page.getByLabel("Import or explicit update"), config.adapterId);
      await page.getByLabel("Adapter ZIP (maximum 8 MiB unpacked)").setInputFiles(join(input, "adapter-v2.zip"));
      const updated = page.waitForResponse(r => new URL(r.url()).pathname === "/api/adapters" && r.request().method() === "POST");
      await page.getByRole("button", { name: "Validate and import", exact: true }).click();
      const adapterV2 = await (await updated).json();
      expect(adapterV2.version).toBe("1.1.0");
      const sqlV2 = await uploadSql("sql-v2.json");
      expect(sqlV2.fingerprint).not.toBe(sql.fingerprint);
      await page.goto(`/runs/${runId}`);
      await expect.poll(async () => (await (await request.get(`/api/runs/${runId}`)).json()).status, { timeout: 60_000 }).toBe("FAILED");
      const run = await (await request.get(`/api/runs/${runId}`)).json();
      expect(run).toMatchObject({ passed: 4, failed: 1, totalCases: 5, failureReason: "SQL_ASSERTION_FAILED", cleanupEvidence: { status: "SUCCESS" }, adapterSnapshot: { fingerprint: adapter.fingerprint, version: "1.0.0" }, sqlPackage: { fingerprint: sql.fingerprint }, driver: { packageFingerprint: driver.packageFingerprint } });
      const raw = await request.get(`/api/runs/${runId}/report.json`);
      expect(raw.status()).toBe(200);
      const reportText = await raw.text();
      expect(reportText).not.toContain(config.password);
      const report = JSON.parse(reportText);
      expect(report.testDomain).toBe("SQL_FEATURE");
      expect(report.scenario_results["basic.query"].steps[0].sql).toBe("SELECT 1 AS n");
      expect(report.scenario_results["intentional.failure"].conclusion).toBe("SQL_ASSERTION_FAILED");
      expect(report.scenario_results["expected.error"].steps[0].actual.sqlState).toBe("22012");
      writeFileSync(join(evidence, `${runId}-report.json`), reportText);
      writeFileSync(join(evidence, `${runId}-detail.json`), JSON.stringify(run, null, 2));
      writeFileSync(stateFile, JSON.stringify({ runId, holder, driverId: driver.id, connectionId: connection.id, adapter, adapterV2, sqlFingerprint: sql.fingerprint, sqlV2Fingerprint: sqlV2.fingerprint, reportText }, null, 2));
      await inspectResult(page, runId, evidence);
      expect(faults).toEqual([]);
    } finally {
      writeFileSync(join(evidence, "browser-traffic.json"), JSON.stringify({ faults, traffic }, null, 2));
    }
  });

  test("after normal Server restart history still shows the original frozen task", async ({ page, request }) => {
    const state = JSON.parse(readFileSync(stateFile, "utf8"));
    const run = await (await request.get(`/api/runs/${state.runId}`)).json();
    expect(run.adapterSnapshot.fingerprint).toBe(state.adapter.fingerprint);
    expect(run.sqlPackage.fingerprint).toBe(state.sqlFingerprint);
    // JSON object field ordering is not stable across JVMs; compare every value and ordered array.
    expect(await (await request.get(`/api/runs/${state.runId}/report.json`)).json()).toEqual(JSON.parse(state.reportText));
    await inspectResult(page, state.runId, evidence, "restarted");
    await page.goto("/runs");
    await expect(page.locator(`a[href="/runs/${state.runId}"]`).first()).toBeVisible();
    await page.screenshot({ path: join(evidence, `${state.runId}-restart-history.png`), fullPage: true });

    // A new task uses the updated revisions; its live SSE is observed without replacing the API.
    await page.addInitScript(() => {
      const Source = window.EventSource;
      const observed: string[] = [];
      Object.assign(window, { acceptanceEvents: observed });
      window.EventSource = class extends Source {
        constructor(url: string | URL, options?: EventSourceInit) {
          super(url, options);
          for (const type of ["run.snapshot", "run.status", "case.started", "case.finished", "run.progress", "run.finished"])
            this.addEventListener(type, () => observed.push(type));
        }
      };
    });
    await page.goto("/sql-packages");
    await chooseOption(page, page.getByLabel("Test package revision"), state.sqlV2Fingerprint);
    await page.getByRole("checkbox", { name: /^basic\.query ·/ }).check();
    await page.getByRole("checkbox", { name: /^hold\.sleep ·/ }).check();
    await chooseOption(page, page.getByLabel("Target connection"), state.connectionId);
    await page.getByRole("checkbox", { name: /I authorize these reviewed SQL/ }).check();
    const created = page.waitForResponse(r => new URL(r.url()).pathname === "/api/runs" && r.request().method() === "POST");
    await page.getByRole("button", { name: "Start SQL test run" }).click();
    const updated = await (await created).json();
    await expect.poll(async () => (await (await request.get(`/api/runs/${updated.id}`)).json()).status).toBe("RUNNING");
    await page.screenshot({ path: join(evidence, `${updated.id}-running.png`), fullPage: true });
    await expect.poll(async () => (await (await request.get(`/api/runs/${updated.id}`)).json()).status, { timeout: 60_000 }).toBe("SUCCEEDED");
    const reportText = await (await request.get(`/api/runs/${updated.id}/report.json`)).text();
    const report = JSON.parse(reportText);
    expect(report.adapterSnapshot.fingerprint).toBe(state.adapterV2.fingerprint);
    expect(report.sqlPackage.fingerprint).toBe(state.sqlV2Fingerprint);
    expect(report.scenario_results["basic.query"].steps[0].actual.rows).toEqual([["2"]]);
    const events = await page.evaluate(() => (window as unknown as { acceptanceEvents: string[] }).acceptanceEvents);
    expect(events).toEqual(expect.arrayContaining(["run.snapshot", "case.started", "case.finished", "run.progress", "run.finished"]));
    writeFileSync(join(evidence, `${updated.id}-report.json`), reportText);
    writeFileSync(join(evidence, `${updated.id}-events.json`), JSON.stringify(events, null, 2));
    writeFileSync(join(evidence, "updated-task.json"), JSON.stringify({ runId: updated.id }, null, 2));
    await page.getByRole("tab", { name: "Report", exact: true }).click();
    await page.screenshot({ path: join(evidence, `${updated.id}-passed.png`), fullPage: true });
  });
});

async function inspectResult(page: Page, runId: string, evidence: string, suffix = "original") {
  await page.goto(`/runs/${runId}`);
  await expect(page.getByText("SQL feature package frozen for this task")).toBeVisible();
  await page.getByRole("tab", { name: "Test cases", exact: true }).click();
  const failedRow = page.getByRole("row").filter({ hasText: "Intentional assertion failure validates platform" });
  await failedRow.getByRole("button", { name: "SQL step assertions", exact: true }).click();
  await expect(page.getByRole("heading", { name: "SQL step assertions" })).toBeVisible();
  await expect(page.getByText("SELECT 999 AS n", { exact: true })).toBeVisible();
  await expect(page.getByText("Expected", { exact: true })).toBeVisible();
  await expect(page.getByText("Actual / failure", { exact: true })).toBeVisible();
  await page.screenshot({ path: join(evidence, `${runId}-${suffix}-assertion.png`), fullPage: true });
  await page.getByRole("dialog").getByText("Actual / failure", { exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(evidence, `${runId}-${suffix}-actual.png`), fullPage: true });
  await page.keyboard.press("Escape");
  await page.getByRole("tab", { name: "Report", exact: true }).click();
  await expect(page.getByText("Adapter frozen for this task")).toBeVisible();
  await page.getByRole("button", { name: "View report JSON" }).click();
  await expect(page.getByRole("dialog")).toContainText("SQL_ASSERTION_FAILED");
  await page.screenshot({ path: join(evidence, `${runId}-${suffix}-report.png`), fullPage: true });
  await page.keyboard.press("Escape");
}
