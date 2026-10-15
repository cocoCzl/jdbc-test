import { chooseOption } from "./select";
import { expect, test } from "@playwright/test";
import type { Catalog, Driver, Run } from "../src/types/api";

declare global {
  interface Window {
    phase8Events: { runId: string; type: string }[];
    closedEventSources: string[];
  }
}

test("REAL PostgreSQL browser flow: external Driver → Statement/Connection → SSE/report/history", async ({
  page,
  request,
}) => {
  const jar = process.env.JDBC_TEST_E2E_DRIVER_JAR;
  const password = process.env.JDBC_TEST_E2E_PASSWORD;
  if (!jar || !password)
    throw new Error(
      "Real E2E requires JDBC_TEST_E2E_DRIVER_JAR and JDBC_TEST_E2E_PASSWORD; no mocked fallback is allowed.",
    );
  await page.addInitScript(() => {
    const NativeSource = window.EventSource;
    window.phase8Events = [];
    window.closedEventSources = [];
    window.EventSource = class extends NativeSource {
      constructor(url: string | URL, options?: EventSourceInit) {
        super(url, options);
        for (const type of [
          "run.snapshot",
          "run.status",
          "case.started",
          "case.finished",
          "run.progress",
          "run.finished",
          "run.failed",
          "run.cancelled",
        ]) {
          this.addEventListener(type, () => {
            window.phase8Events.push({
              runId: String(url).split("/").at(-2) ?? "",
              type,
            });
          });
        }
      }
      close() {
        window.closedEventSources.push(this.url.split("/").at(-2) ?? "");
        super.close();
      }
    };
  });
  const errors: string[] = [];
  const eventContentTypes: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("response", (response) => {
    if (response.url().endsWith("/events"))
      eventContentTypes.push(response.headers()["content-type"] ?? "");
  });
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Drivers", exact: true }).click();
  await page
    .getByRole("link", { name: "Add driver", exact: true })
    .first()
    .click();
  const name = `PostgreSQL web ${Date.now()}`;
  await page.getByLabel("Name", { exact: true }).fill(name);
  await page.getByLabel("Vendor", { exact: true }).fill("PostgreSQL");
  await page
    .getByLabel("Version", { exact: true })
    .fill(process.env.JDBC_TEST_E2E_DRIVER_VERSION ?? "42.7.5");
  await page.getByLabel("JAR files").setInputFiles(jar);
  const uploadResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/drivers") &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Upload driver package" }).click();
  const uploaded = await uploadResponse;
  expect(uploaded.status()).toBe(201);
  const driver = (await uploaded.json()) as Driver;
  expect(driver.packageFingerprint).toMatch(/^[a-f0-9]{64}$/);
  expect(driver.artifacts).toHaveLength(1);
  await page.getByRole("button", { name: "Validate driver" }).click();
  await expect(page.getByText("Validated in an isolated Worker")).toBeVisible();
  await page.reload();
  await expect(page.getByRole("button", { name: "Validate driver" })).toBeVisible();
  await page.getByRole("link", { name: "Connections", exact: true }).click();
  await page
    .getByRole("link", { name: "Add connection", exact: true })
    .first()
    .click();
  const connectionName = `PostgreSQL UI target ${Date.now()}`;
  await page.getByLabel("Name", { exact: true }).fill(connectionName);
  await page.getByLabel("Database type", { exact: true }).fill("PostgreSQL");
  await chooseOption(page, page.getByLabel("Adapter", { exact: true }), "postgresql");
  await chooseOption(page, page.getByLabel("Driver package", { exact: true }), driver.id);
  await page
    .getByLabel("JDBC URL", { exact: true })
    .fill(
      process.env.JDBC_TEST_E2E_TARGET_URL ??
        "jdbc:postgresql://127.0.0.1:55433/jdbc_ui",
    );
  await page
    .getByLabel("Username", { exact: true })
    .fill(process.env.JDBC_TEST_E2E_USERNAME ?? "jdbc_ui");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByLabel("Authorized test database", { exact: true })
    .fill(process.env.JDBC_TEST_E2E_DATABASE ?? "jdbc_ui");
  await page.getByLabel("Authorized test schema", { exact: true })
    .fill(process.env.JDBC_TEST_E2E_SCHEMA ?? "jdbc_ui");
  await page
    .getByRole("button", { name: "Test connection", exact: true })
    .click();
  await expect(page.getByText("Connected", { exact: true })).toBeVisible();
  await expect(page.getByText(/PostgreSQL JDBC Driver/)).toContainText(
    process.env.JDBC_TEST_E2E_DRIVER_VERSION ?? "42.7.5",
  );
  await expect(page.getByLabel("Password", { exact: true })).toHaveValue("");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByLabel("Save password", { exact: true }).check();
  await page.getByRole("button", { name: "Save connection" }).click();
  await expect(page.getByText("Saved password available")).toBeVisible();
  const connectionId = page.url().split("/").at(-1)!;
  await page.reload();
  await expect(page.getByText("Saved password available")).toBeVisible();
  const catalogResponse = await request.get("/api/test-catalog");
  expect(catalogResponse.status()).toBe(200);
  const catalog = await catalogResponse.json() as Catalog;
  const createdRunIds: string[] = [];
  for (const module of ["Statement", "Connection"]) {
    const count = catalog.modules.find(item => item.id === module.toLowerCase())!.scenarioCount;
    expect(count).toBeGreaterThan(0);
    await page.getByRole("link", { name: "Start run", exact: true }).click();
    await page.getByRole("button", { name: /^Next:/ }).click();
    await page.getByRole("button", { name: /^Next:/ }).click();
    await page.getByRole("button", { name: /Custom Choose/ }).click();
    await page
      .getByRole("checkbox", {
        name: `${module} ${count} scenarios`,
        exact: true,
      })
      .check();
    await expect(page.getByText(`${count} scenarios selected`)).toBeVisible();
    await page.getByRole("button", { name: /^Next:/ }).click();
    await page.getByRole("checkbox", { name: /I understand/ }).check();
    await page.getByRole("button", { name: "Start test run" }).click();
    await expect(page).toHaveURL(/\/runs\/[a-f0-9-]+$/);
    const id = page.url().split("/").at(-1)!;
    createdRunIds.push(id);
    await expect(page.getByText("Run succeeded", { exact: true })).toBeVisible({
      timeout: 60_000,
    });
    await expect(
      page.getByText(`${count} / ${count}`, { exact: true }),
    ).toBeVisible();
    const detail = await request.get(`/api/runs/${id}`);
    expect(detail.status()).toBe(200);
    const run = (await detail.json()) as Run;
    // The bundled PG declaration disables only this selected multi-result scenario.
    // Additional/unexplained skips must fail acceptance, not become a permissive allowance.
    const expectedSkippedIds = module === "Statement" ? ["statement.result_sequence"] : [];
    expect(run).toMatchObject({
      status: "SUCCEEDED",
      totalCases: count,
      passed: count - expectedSkippedIds.length,
      failed: 0,
      skipped: expectedSkippedIds.length,
      selectedModules: [module.toLowerCase()],
      cleanupEvidence: { status: "SUCCESS" },
      accounting: { notRun: 0, incomplete: 0 },
      connection: { adapterId: "postgresql" },
    });
    expect(run.driver.packageFingerprint).toBe(driver.packageFingerprint);
    expect(run.driver.id).toBe(driver.id);
    const events = await page.evaluate(
      (runId) =>
        window.phase8Events
          .filter((event) => event.runId === runId)
          .map((event) => event.type),
      id,
    );
    expect(events).toEqual(
      expect.arrayContaining([
        "run.snapshot",
        "run.status",
        "case.started",
        "case.finished",
        "run.progress",
        "run.finished",
      ]),
    );
    await expect.poll(() => page.evaluate(
      (runId) => window.closedEventSources.includes(runId), id,
    )).toBe(true);
    await page.getByRole("tab", { name: "Test cases" }).click();
    await page.reload();
    await expect(page.getByText("Run succeeded", { exact: true })).toBeVisible();
    await page.getByRole("tab", { name: "Test cases" }).click();
    const resultsResponse = await request.get(
      `/api/runs/${id}/results?size=100`,
    );
    expect(resultsResponse.status()).toBe(200);
    const results = (await resultsResponse.json()) as {
      totalElements: number;
      items: { scenarioId: string; moduleId: string; status: string; message: string | null }[];
    };
    expect(results.totalElements).toBe(count);
    expect(
      results.items.every(
        (item) =>
          item.moduleId === module.toLowerCase() &&
          item.status === (expectedSkippedIds.includes(item.scenarioId) ? "SKIPPED" : "PASSED"),
      ),
    ).toBe(true);
    expect(run.accounting?.scenarios).toHaveLength(count);
    expect(new Set(results.items.map(item => item.scenarioId)).size).toBe(count);
    expect(results.items.map(item => item.scenarioId).sort()).toEqual(
      run.accounting!.scenarios.map(item => item.scenarioId).sort(),
    );
    expect(results.items.filter(item => item.status === "SKIPPED").map(item => item.scenarioId).sort())
      .toEqual(expectedSkippedIds);
    for (const item of results.items.filter(item => item.status === "SKIPPED")) {
      expect(item.message).toContain("Execution capability disabled");
      expect(item.message).toContain("multiple_result_sets");
      expect(item.message).toContain("multiple_result_sequences");
    }
    await page.getByRole("tab", { name: "Report", exact: true }).click();
    await expect(page.getByText("Captured environment")).toBeVisible();
    await page.getByRole("button", { name: "View report JSON" }).click();
    await expect(page.getByRole("dialog")).toContainText('"summary"');
    const rawResponse = await request.get(`/api/runs/${id}/report.json`);
    expect(rawResponse.status()).toBe(200);
    expect(await rawResponse.text()).not.toContain(password);
    await page.getByRole("button", { name: "Close dialog" }).click();
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: `test-results/real-${module.toLowerCase()}-report.png`,
      fullPage: true,
    });
    await page.getByRole("link", { name: "Run history", exact: true }).click();
    if (module === "Statement") {
      const filteredRuns = page.waitForResponse(
        (response) =>
          response.url().includes("/api/runs") &&
          response.url().includes("status=SUCCEEDED"),
      );
      await chooseOption(page, page.getByLabel("Filter run status"), "SUCCEEDED");
      const filteredResponse = await filteredRuns;
      expect(filteredResponse.status()).toBe(200);
      const filteredPage = await filteredResponse.json() as {
        totalElements: number;
        items: { status: string }[];
      };
      expect(filteredPage.totalElements).toBeGreaterThanOrEqual(1);
      expect(filteredPage.items.every((item) => item.status === "SUCCEEDED")).toBe(true);
    }
    await expect(
      page.getByRole("table").getByText(connectionName).first(),
    ).toBeVisible();
    await expect(page.getByRole("table").locator(`a[href="/runs/${id}"]`)).toHaveText(module);
    await page.goto(`/connections/${connectionId}`);
  }
  for (const width of [1280, 1024, 768]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");
    await expect(page.getByText("Recent runs", { exact: true })).toBeVisible();
    await expect(
      page.getByRole("table").getByText(connectionName).first(),
    ).toBeVisible();
    for (const id of createdRunIds) {
      await expect(page.getByRole("table").locator(`a[href="/runs/${id}"]`)).toBeVisible();
    }
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: `test-results/real-dashboard-${width}.png`,
      fullPage: true,
    });
  }
  expect(
    await page.evaluate(() => localStorage.length + sessionStorage.length),
  ).toBe(0);
  expect(errors).toEqual([]);
  expect(eventContentTypes.length).toBeGreaterThanOrEqual(2);
  expect(eventContentTypes.every((type) => type.startsWith("text/event-stream"))).toBe(true);
});
