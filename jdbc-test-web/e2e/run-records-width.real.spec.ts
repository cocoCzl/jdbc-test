import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { chooseOption } from "./select";
import { checkRunRecordWidths } from "./run-record-widths";

const id = process.env.JDBC_TEST_HISTORICAL_RUN_ID ?? "";
const hash = (data: unknown) => createHash("sha256").update(JSON.stringify(data)).digest("hex");

test("REAL 8080 + user website: restarted Server, consistent outcomes and bounded columns", async ({page, request, baseURL}) => {
  test.skip(process.env.JDBC_TEST_RUN_RECORDS_READONLY !== "true" || !id, "Explicit read-only local verification only");
  expect(baseURL).toBe("http://127.0.0.1:5173");
  const get = async (path: string) => {
    const response = await request.get(`http://127.0.0.1:8080/api/runs${path}`);
    expect(response.status()).toBe(200);
    return response.json();
  };
  const run = await get(`/${id}`);
  expect(run.status).toBe("FAILED");
  expect(run.executionOutcome).toBe("COMPLETED");
  const summary = await get(`/${id}/report`);
  expect(summary.summary.executionOutcome).toBe(run.executionOutcome);
  const before = {run: hash(run), report: hash(await get(`/${id}/report.json`))};
  const requests: {method: string; url: string}[] = [];
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  // Observe actual browser requests; do not intercept or supply any API response.
  page.on("request", request => {
    if (new URL(request.url()).pathname.startsWith("/api/")) requests.push({method: request.method(), url: request.url()});
  });
  const measurements = [];
  for (const locale of ["zh-CN", "en"]) {
    const zh = locale === "zh-CN";
    const label = zh ? "已完成 · 存在未通过用例" : "Completed · unsuccessful cases";
    await page.addInitScript(locale => {localStorage.setItem("jdbc-test.locale", locale); localStorage.setItem("jdbc-test.theme", "light");}, locale);
    await page.goto("/runs");
    const row = page.locator("tbody tr").filter({has: page.locator(`a[href="/runs/${id}"]`)});
    await expect(row.locator("td").nth(1)).toHaveText(label);
    for (const width of [1366, 1440, 1920, 2048]) {
      await page.setViewportSize({width, height: 900});
      measurements.push({locale, ...await checkRunRecordWidths(page)});
      await expect(page.locator("main")).toHaveCSS("opacity", "1");
      await page.screenshot({path: test.info().outputPath(`REAL-records-${locale}-${width}.png`), fullPage: true, animations: "disabled"});
    }
    const filter = page.getByRole("combobox", {name: zh ? "筛选运行状态" : "Filter run status"});
    for (const group of ["WAITING", "IN_PROGRESS", "COMPLETED", "ABNORMAL", "CANCELLED", ""]) {
      const response = page.waitForResponse(response => {
        const url = new URL(response.url());
        return url.pathname === "/api/runs" && (url.searchParams.get("statusGroup") ?? "") === group;
      });
      await chooseOption(page, filter, group);
      expect((await response).status()).toBe(200);
      if (group === "COMPLETED" || group === "") await expect(row.locator("td").nth(1)).toHaveText(label);
      else await expect(row).toHaveCount(0);
      if (group === "COMPLETED" || group === "ABNORMAL") await page.screenshot({path: test.info().outputPath(`REAL-filter-${locale}-${group}.png`), fullPage: true, animations: "disabled"});
    }
    await row.getByRole("link", {name: zh ? "查看详情" : "View details", exact: true}).click();
    await expect(page.locator(".jdbc-page-header .status-badge")).toHaveText(label);
    const fullName = await page.locator("h1").textContent();
    expect(fullName?.trim()).toBeTruthy();
    if (run.name) expect(fullName).toBe(run.name);
    await page.reload();
    await expect(page.locator(".jdbc-page-header .status-badge")).toHaveText(label);
    for (const width of [1920, 375, 320]) {
      await page.setViewportSize({width, height: 900});
      await expect(page.locator("main")).toHaveCSS("opacity", "1");
      await page.screenshot({path: test.info().outputPath(`REAL-detail-${locale}-${width}.png`), fullPage: true, animations: "disabled"});
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    }
    await page.getByRole("tab", {name: zh ? "报告" : "Report", exact: true}).click();
    await expect(page.locator(".status-badge").filter({hasText: label})).toHaveCount(2);
    await page.getByRole("link", {name: zh ? "返回运行记录" : "Back to run records"}).click();
    await page.reload();
    await expect(row.locator("td").nth(1)).toHaveText(label);
  }
  await page.setViewportSize({width: 1366, height: 900});
  for (const [resource, actionWidth] of [["drivers", 128], ["connections", 176]] as const) {
    await page.goto(`/${resource}`);
    const table = page.locator(`.resource-table--${resource}`);
    await expect(table.locator("tbody tr").first()).toBeVisible();
    expect((await table.locator("th").last().boundingBox())!.width).toBeCloseTo(actionWidth, 0);
    await expect(page.locator(".run-history-panel")).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  expect({run: hash(await get(`/${id}`)), report: hash(await get(`/${id}/report.json`))}).toEqual(before);
  expect(requests.every(request => request.method === "GET")).toBe(true);
  expect(requests.every(request => new URL(request.url).origin === baseURL)).toBe(true);
  expect(errors).toEqual([]);
  writeFileSync(test.info().outputPath(`real-browser-verification.json`), JSON.stringify({baseURL, backend: "http://127.0.0.1:8080", simulatedData: false,
    interceptedResponses: false, before, unchanged: true, measurements, requests, errors}, null, 2));
});
