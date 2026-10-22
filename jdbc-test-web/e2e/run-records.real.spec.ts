import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { chooseOption } from "./select";

const id = process.env.JDBC_TEST_HISTORICAL_RUN_ID ?? "";
const hash = (body: unknown) => createHash("sha256").update(JSON.stringify(body)).digest("hex");
test("REAL DATA, GET ONLY: historical Kingbase evidence and responsive UI", async ({ page, request }) => {
  test.skip(process.env.JDBC_TEST_RUN_RECORDS_READONLY !== "true" || !id, "Explicit local read-only verification only");
  const backend = process.env.JDBC_TEST_REAL_API ?? "http://127.0.0.1:8080";
  const get = async (path: string) => {
    const response = await request.get(`${backend}/api/runs/${id}${path}`);
    expect(response.ok()).toBe(true); return response.json();
  };
  const original = await get("");
  expect(original.status).toBe("FAILED");
  expect(original.failureReason).toBe("WORKER_EXECUTION_FAILED");
  expect(original.executionOutcome).toBe("COMPLETED");
  const filters = [];
  for (const group of ["", "WAITING", "IN_PROGRESS", "COMPLETED", "ABNORMAL", "CANCELLED"]) {
    const response = await request.get(`${backend}/api/runs${group ? `?statusGroup=${group}` : ""}`);
    expect(response.status()).toBe(200);
    const data = await response.json();
    if (group === "COMPLETED") expect(data.items.some((run: {id: string}) => run.id === id)).toBe(true);
    if (group === "ABNORMAL") expect(data.items.some((run: {id: string}) => run.id === id)).toBe(false);
    filters.push({ group: group || "ALL", status: response.status(), total: data.totalElements });
  }
  const reportSummary = await get("/report");
  expect(reportSummary.summary.executionOutcome).toBe(original.executionOutcome);
  expect(reportSummary.summary.historicalOutcome).toBe(original.historicalOutcome);
  const before = { run: hash(original), report: hash(await get("/report.json")) };
  const errors: string[] = [];
  const pageRequests: string[] = [];
  const capture = async (options: NonNullable<Parameters<typeof page.screenshot>[0]>) => {
    await expect(page.locator("main")).toHaveCSS("opacity", "1");
    if (options.fullPage) await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ ...options, animations: "disabled" });
  };
  page.on("pageerror", error => errors.push(error.message));
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    expect(route.request().method()).toBe("GET");
    const url = new URL(route.request().url());
    pageRequests.push(url.pathname);
    const response = await route.fetch({ url: `${backend}${url.pathname}${url.search}` });
    await route.fulfill({ response });
  });
  for (const locale of ["zh-CN", "en"]) {
    await page.addInitScript(locale => { localStorage.setItem("jdbc-test.locale", locale); localStorage.setItem("jdbc-test.theme", "light"); }, locale);
    const zh = locale === "zh-CN";
    await page.goto(`/runs/${id}`);
    await expect(page.locator(".jdbc-page-header .status-badge")).toHaveText(zh ? "已完成 · 存在未通过用例" : "Completed · unsuccessful cases");
    await expect(page.locator('[data-metric="' + (zh ? "失败" : "Failed") + '"]')).toContainText("10");
    await expect(page.locator(".run-technical")).not.toHaveAttribute("open");
    for (const width of [1366, 1920, 375, 320]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await capture({ path: test.info().outputPath(`REAL-kingbase-detail-${locale}-${width}.png`), fullPage: true, animations: "disabled" });
    }
    expect(pageRequests.filter(path => path.endsWith("/report.json"))).toHaveLength(0);
    await expect(page.getByRole("region", {name: zh ? "执行统计" : "Execution accounting"}).locator("details")).toHaveCount(0);
    await expect(page.locator(".run-statistics-note")).not.toHaveAttribute("open");
    await expect(page.getByRole("region", {name: zh ? "执行统计" : "Execution accounting"})).not.toContainText(zh ? "旧版" : "older version");
    for (const width of [1366, 375, 320]) {
      await page.setViewportSize({width, height: 900});
      const viewUrl = page.getByRole("button", {name: zh ? "查看 URL" : "View URL", exact: true});
      await viewUrl.click();
      const dialog = page.getByRole("dialog");
      await expect(dialog).toContainText("jdbc:kingbase8://");
      await expect(dialog.getByRole("button", {name: /^(Language|界面语言)$/})).toHaveCount(0);
      const title = await dialog.getByRole("heading").boundingBox();
      const close = dialog.getByRole("button", {name: zh ? "关闭弹窗" : "Close dialog"});
      const box = await close.boundingBox();
      expect(title!.x + title!.width).toBeLessThan(box!.x);
      await capture({path: test.info().outputPath(`REAL-url-${locale}-${width}.png`), fullPage: false});
      await close.click();
      await expect(viewUrl).toBeFocused();
    }
    await page.setViewportSize({ width: 1366, height: 900 });
    await page.locator(".run-technical > summary").click();
    await expect(page.getByText("^KingbaseES$", {exact: true})).toHaveCount(0);
    for (const width of [1366, 375, 320]) {
      await page.setViewportSize({width, height: 900});
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await capture({ path: test.info().outputPath(`REAL-technical-${locale}-${width}.png`), fullPage: true });
    }
    await page.setViewportSize({width: 1366, height: 900});
    await page.getByRole("button", {name: zh ? "查看原始配置" : "View original configuration", exact: true}).click();
    await expect(page.getByRole("dialog")).toContainText("KingbaseES");
    await expect(page.getByRole("dialog").getByRole("button", {name: /^(Language|界面语言)$/})).toHaveCount(0);
    await page.keyboard.press("Escape");
    await expect(page.getByRole("button", {name: zh ? "查看原始配置" : "View original configuration", exact: true})).toBeFocused();
    await page.getByRole("button", {name: zh ? "查看未通过用例" : "View unsuccessful cases", exact: true}).click();
    await expect(page.locator("tbody tr")).toHaveCount(10);
    const filter = page.getByRole("combobox", { name: zh ? "筛选结果状态" : "Filter result status" });
    await chooseOption(page, filter, "PASSED");
    await expect(page.locator("tbody tr")).toHaveCount(20);
    await page.getByRole("button", {name: zh ? "查看未通过用例" : "View unsuccessful cases", exact: true}).click();
    await expect(page.locator("tbody tr")).toHaveCount(10);
    for (const status of ["PASSED", "FAILED", "SKIPPED"]) {
      await chooseOption(page, filter, status);
      await expect(page.locator("tbody tr").first()).toBeVisible();
      await capture({ path: test.info().outputPath(`REAL-kingbase-cases-${status}-${locale}.png`), fullPage: true });
      await page.getByRole("button", { name: zh ? "查看详情" : "View details", exact: true }).first().click();
      await expect(page.getByRole("dialog")).toBeVisible();
      if (status === "PASSED") await expect(page.getByRole("dialog").getByText(zh ? "堆栈" : "Stack trace", { exact: true })).toHaveCount(0);
      await capture({ path: test.info().outputPath(`REAL-kingbase-case-${status}-${locale}.png`), fullPage: false });
      await page.keyboard.press("Escape");
    }
    await page.getByRole("tab", { name: zh ? "报告" : "Report", exact: true }).click();
    await expect(page.locator(".status-badge").filter({ hasText: zh ? "已完成 · 存在未通过用例" : "Completed · unsuccessful cases" })).toHaveCount(2);
    await page.getByRole("link", { name: zh ? "返回运行记录" : "Back to run records" }).click();
    await expect(page.locator("tbody tr").first()).toBeVisible();
    await expect(page.getByRole("columnheader")).toHaveText(zh
      ? ["任务名称", "状态", "连接", "驱动", "进度", "结果", "耗时", "开始时间", "操作"]
      : ["Task name", "Status", "Connection", "Driver", "Progress", "Results", "Duration", "Started", "Actions"]);
    const statusCell = page.locator("tbody tr").filter({has: page.locator(`a[href="/runs/${id}"]`)}).locator("td").nth(1);
    await expect(statusCell).toHaveText(zh ? "已完成 · 存在未通过用例" : "Completed · unsuccessful cases");
    const filterStatus = page.getByRole("combobox", {name: zh ? "筛选运行状态" : "Filter run status"});
    for (const group of ["WAITING", "IN_PROGRESS", "COMPLETED", "ABNORMAL", "CANCELLED", ""]) {
      const response = page.waitForResponse(response => {
        const url = new URL(response.url());
        return url.pathname === "/api/runs" && (url.searchParams.get("statusGroup") ?? "") === group;
      });
      await chooseOption(page, filterStatus, group);
      expect((await response).status()).toBe(200);
      if (group === "" || group === "COMPLETED") await expect(statusCell).toBeVisible();
      else await expect(page.locator(`tbody a[href="/runs/${id}"]`)).toHaveCount(0);
    }
    for (const width of [1366, 1920, 375, 320]) {
      await page.setViewportSize({width, height: 900});
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      if (width >= 1366) expect(await page.locator(".run-table--history").evaluate(table => table.parentElement!.scrollWidth <= table.parentElement!.clientWidth)).toBe(true);
      await capture({ path: test.info().outputPath(`REAL-records-${locale}-${width}.png`), fullPage: true });
    }
    await page.setViewportSize({width: 1366, height: 900});
    const eye = page.getByRole("link", {name: zh ? "查看详情" : "View details", exact: true}).first();
    await eye.focus();
    await expect(page.getByRole("tooltip")).toContainText(zh ? "查看详情" : "View details");
    await eye.press("Enter");
    await expect(page.locator(".jdbc-page-header .status-badge")).toHaveText(zh ? "已完成 · 存在未通过用例" : "Completed · unsuccessful cases");
    await page.reload();
    await expect(page.locator(".jdbc-page-header .status-badge")).toHaveText(zh ? "已完成 · 存在未通过用例" : "Completed · unsuccessful cases");

  }
  expect({ run: hash(await get("")), report: hash(await get("/report.json")) }).toEqual(before);
  expect(errors).toEqual([]);
  writeFileSync(test.info().outputPath(`real-readonly-verification.json`), JSON.stringify({ id, before, unchanged: true, apiMethods: ["GET"], simulatedData: false, filters, errors }, null, 2));
});
