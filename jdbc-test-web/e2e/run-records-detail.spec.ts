import { expect, test } from "@playwright/test";
import { adapters, catalog, connection, driver, finishedRun, page as paged, result, run } from "../src/test/fixtures";
import type { Run, RunStatus, RunStatusGroup, ResultStatus } from "../src/types/api";
import { chooseOption } from "./select";

const failure: Run = {
  ...finishedRun, id: "case-failures", status: "FAILED", passed: 2, failed: 1, skipped: 1,
  totalCases: 4, completedCases: 4, failureReason: "TEST_FAILURE", workerTerminationReason: "TEST_FAILURE",
  workerReportValidated: true, sqlDialect: "postgresql", executionIssues: [],
  executionScope: { database: "test", schema: "owned_scope" },
  accounting: { selected: 4, started: 3, completed: 4, passed: 2, failed: 1, skipped: 1, aborted: 0,
    notRun: 0, incomplete: 0, pending: 0, running: 0, scenarios: [] },
  cleanupEvidence: { status: "SUCCESS", attempted: 2, confirmedDeleted: 2, retainedObjects: [] },
  connection: { ...finishedRun.connection, jdbcUrl: `jdbc:postgresql://fixture.invalid:5432/test?applicationName=${"long-name-".repeat(12)}&password=FIXTURE-SECRET` },
  adapterSnapshot: { id: "kingbase", name: "KingbaseES", status: "OFFICIAL", dialect: "postgresql",
    matchConditions: { expected_database_product_regex: "^KingbaseES$" }, fingerprint: "abcdef" },
};
const records: Run[] = [
  { ...run, id: "active", name: "FIXTURE · A deliberately long saved task name / 用于验证截断和悬停完整名称 / 2026-10-22", currentTestCase: "statement.execute", currentModule: "statement", completedCases: 5, passed: 5, durationMs: 12000,
    sqlDialect: "postgresql", executionScope: { database: "test", schema: "owned_scope" },
    accounting: { selected: 23, started: 6, completed: 5, passed: 5, failed: 0, skipped: 0, aborted: 0, pending: 17, running: 1, notRun: 17, incomplete: 1, scenarios: [] },
    cleanupEvidence: { status: "NOT_EXECUTED" } },
  { ...run, id: "reporting", status: "GENERATING_REPORT", completedCases: 23 },
  { ...finishedRun, id: "all-pass" }, failure,
  { ...finishedRun, id: "exception", status: "FAILED", failureReason: "REPORT_INCONSISTENT", workerReportValidated: false },
  { ...finishedRun, id: "cancelled", status: "CANCELLED" },
  { ...run, id: "queued", status: "QUEUED", startedAt: null },
];
const statuses: ResultStatus[] = ["PASSED", "FAILED", "SKIPPED", "ABORTED"];
const cases = statuses.map(status => ({ ...result, id: status, displayName: `FIXTURE ${status}`, status,
  message: status === "PASSED" ? null : `Recorded ${status} reason`, stackTrace: status === "FAILED" ? "Recorded stack" : null }));
const groups: Partial<Record<RunStatusGroup, RunStatus[]>> = {
  WAITING: ["QUEUED"], IN_PROGRESS: ["PREPARING", "PROBING", "RUNNING", "GENERATING_REPORT"],
  CANCELLED: ["CANCELLED"],
};

for (const locale of ["zh-CN", "en"]) for (const theme of ["light", "dark"]) {
  test(`FIXTURE: lifecycle, cases, records and responsive layout ${locale} ${theme}`, async ({ page }) => {
    const zh = locale === "zh-CN";
    const errors: string[] = [];
    const capture = async (options: NonNullable<Parameters<typeof page.screenshot>[0]>) => {
      await expect(page.locator("main")).toHaveCSS("opacity", "1");
      if (options.fullPage) await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({ ...options, animations: "disabled" });
    };
    page.on("pageerror", error => errors.push(error.message));
    await page.addInitScript(({ locale, theme }) => {
      localStorage.setItem("jdbc-test.locale", locale); localStorage.setItem("jdbc-test.theme", theme);
      class FixtureEvents extends EventTarget {
        onopen: (() => void) | null = null; onerror = null;
        constructor() { super(); (window as unknown as { runEvents: FixtureEvents }).runEvents = this; queueMicrotask(() => this.onopen?.()); }
        close() {}
      }
      Object.defineProperty(window, "EventSource", { value: FixtureEvents });
    }, { locale, theme });
    let live = records[0];
    let emptyRecords = false;
    await page.route(url => url.pathname.startsWith("/api/"), route => {
      expect(route.request().method()).toBe("GET");
      const url = new URL(route.request().url());
      if (url.pathname === "/api/runs") {
        const group = url.searchParams.get("statusGroup") as RunStatusGroup;
        return route.fulfill({ json: paged(emptyRecords ? [] : group ? records.filter(record =>
          group === "COMPLETED" ? ["all-pass", "case-failures"].includes(record.id)
          : group === "ABNORMAL" ? record.id === "exception" : groups[group]?.includes(record.status)) : records) });
      }
      const id = url.pathname.split("/")[3];
      const selected = id === "active" ? live : records.find(record => record.id === id)!;
      expect(selected).toBeTruthy();
      if (url.pathname.endsWith("/results")) {
        const status = url.searchParams.get("status");
        return route.fulfill({ json: paged(status ? cases.filter(item => item.status === status) : cases) });
      }
      if (url.pathname.endsWith("/report")) return route.fulfill({ json: { summary: selected, failures: cases.filter(item => item.status === "FAILED") } });
      return route.fulfill({ json: selected });
    });
    await page.goto("/runs");
    await expect(page.locator("tbody tr")).toHaveCount(7);
    await expect(page.getByRole("columnheader")).toHaveText(zh
      ? ["任务名称", "状态", "连接", "驱动", "进度", "结果", "耗时", "开始时间", "操作"]
      : ["Task name", "Status", "Connection", "Driver", "Progress", "Results", "Duration", "Started", "Actions"]);
    const firstName = page.locator("tbody tr").first().locator("td").nth(0).getByRole("link");
    await expect(firstName).toHaveAttribute("title", records[0].name!);
    await expect(page.locator("tbody tr").first().locator("td").nth(1).locator(".status-badge")).toBeVisible();
    const eye = page.locator("tbody tr").first().getByRole("link", {name: zh ? "查看详情" : "View details", exact: true});
    await eye.focus();
    await expect(page.getByRole("tooltip")).toContainText(zh ? "查看详情" : "View details");
    await page.keyboard.press("Escape");
    const filters = page.getByRole("combobox", {name: zh ? "筛选运行状态" : "Filter run status"});
    await chooseOption(page, filters, "IN_PROGRESS");
    await expect(page.locator("tbody tr")).toHaveCount(2);
    await chooseOption(page, filters, "COMPLETED");
    await expect(page.locator("tbody tr")).toHaveCount(2);
    await chooseOption(page, filters, "");
    await expect(page.locator("tbody tr")).toHaveCount(7);
    for (const width of [1366, 1920, 375, 320]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await capture({ path: test.info().outputPath(`fixture-records-${locale}-${theme}-${width}.png`), fullPage: true });
    }
    for (const selected of records) {
      await page.setViewportSize({ width: 1366, height: 900 });
      await page.goto(`/runs/${selected.id}`);
      await expect(page.getByRole("progressbar")).toBeVisible();
      const section = page.getByRole("region", { name: zh ? "执行统计" : "Execution accounting" });
      if (selected.status === "RUNNING" || selected.status === "GENERATING_REPORT") {
        await expect(section.locator("p").first()).not.toContainText(zh ? "已完成" : "Completed");
        await expect(section).toContainText(zh ? "已处理" : "Processed");
      }
      if (selected.id === "case-failures") await expect(page.locator(".jdbc-page-header .status-badge")).toContainText(zh ? "已完成 · 存在未通过用例" : "Completed · unsuccessful cases");
      if (selected.id === "exception") await expect(page.getByRole("alert")).toContainText(zh ? "报告与持久化执行证据不一致" : "The report did not match");
      if (selected.id === "cancelled") await expect(page.getByRole("button", { name: zh ? "取消运行" : "Cancel run" })).toHaveCount(0);
      for (const width of [1366, 375, 320]) {
        await page.setViewportSize({ width, height: 900 });
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        if (["active", "reporting", "exception"].includes(selected.id) && zh && theme === "light")
          await capture({ path: test.info().outputPath(`FIXTURE-${selected.id}-${locale}-${width}.png`), fullPage: true });
      }
    }
    await page.goto("/runs/active");
    live = { ...live, status: "GENERATING_REPORT", completedCases: 23 };
    await page.evaluate(() => (window as unknown as { runEvents: EventTarget }).runEvents.dispatchEvent(new MessageEvent("run.status", { data: JSON.stringify({ runId: "active", status: "GENERATING_REPORT", completedCases: 23 }) })));
    await expect(page.getByText(zh ? "正在生成报告" : "Generating report", { exact: true }).first()).toBeVisible();
    live = { ...finishedRun, id: "active" };
    await page.evaluate(() => (window as unknown as { runEvents: EventTarget }).runEvents.dispatchEvent(new MessageEvent("run.finished", { data: JSON.stringify({ runId: "active", status: "SUCCEEDED" }) })));
    await expect(page.locator(".jdbc-page-header .status-badge")).toHaveText(zh ? "已完成" : "Completed");
    await page.reload();
    await expect(page.locator(".jdbc-page-header .status-badge")).toHaveText(zh ? "已完成" : "Completed");
    await page.goto("/runs/case-failures");
    await expect(page.locator(".run-technical")).not.toHaveAttribute("open");
    await expect(page.getByText("^KingbaseES$", { exact: true })).not.toBeVisible();
    for (const width of [1366, 1920, 375, 320]) {
      await page.setViewportSize({ width, height: 900 });
      await capture({ path: test.info().outputPath(`fixture-detail-${locale}-${theme}-${width}.png`), fullPage: true, animations: "disabled" });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    }
    for (const width of [1366, 375, 320]) {
      await page.setViewportSize({width, height: 900});
      const viewUrl = page.getByRole("button", {name: zh ? "查看 URL" : "View URL", exact: true});
      await viewUrl.click();
      const dialog = page.getByRole("dialog");
      await expect(dialog).not.toContainText("FIXTURE-SECRET");
      await expect(dialog).toContainText("[REDACTED]");
      await expect(dialog.getByRole("button", {name: /^(Language|界面语言)$/})).toHaveCount(0);
      const title = await dialog.getByRole("heading").boundingBox();
      const close = await dialog.getByRole("button", {name: zh ? "关闭弹窗" : "Close dialog"}).boundingBox();
      expect(title!.x + title!.width).toBeLessThan(close!.x);
      expect(await dialog.evaluate(node => node.scrollWidth <= node.clientWidth)).toBe(true);
      await capture({path: test.info().outputPath(`fixture-url-${locale}-${theme}-${width}.png`), fullPage: false});
      await page.keyboard.press("Escape");
      await expect(viewUrl).toBeFocused();
    }
    await page.getByRole("tab", { name: zh ? "测试用例" : "Test cases", exact: true }).click();
    await expect(page.locator("tbody tr")).toHaveCount(4);
    for (const status of statuses) {
      await page.getByRole("row").filter({ hasText: `FIXTURE ${status}` }).getByRole("button", { name: zh ? "查看详情" : "View details" }).click();
      const dialog = page.getByRole("dialog");
      await expect(dialog).toContainText(zh ? "用例结论" : "Case conclusion");
      if (status === "PASSED") await expect(dialog.getByText(zh ? "堆栈" : "Stack trace")).toHaveCount(0);
      if (status === "FAILED") await expect(dialog.getByText("Recorded stack")).not.toBeVisible();
      await capture({ path: test.info().outputPath(`fixture-case-${status}-${locale}-${theme}.png`), fullPage: false });
      await page.keyboard.press("Escape");
    }
    for (const width of [1366, 375, 320]) {
      await page.setViewportSize({ width, height: 900 });
      const toolbar = page.locator(".run-result-toolbar");
      const selects = await toolbar.getByRole("combobox").all();
      const rects = await Promise.all(selects.map(select => select.boundingBox()));
      expect(rects.every(rect => rect!.height === rects[0]!.height)).toBe(true);
      if (width === 1366) expect(rects.every(rect => rect!.y === rects[0]!.y)).toBe(true);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await capture({ path: test.info().outputPath(`fixture-cases-${locale}-${theme}-${width}.png`), fullPage: true });
    }
    await chooseOption(page, page.getByRole("combobox", { name: zh ? "筛选结果状态" : "Filter result status" }), "SKIPPED");
    await expect(page.locator("tbody tr")).toHaveCount(1);
    await page.getByRole("link", { name: zh ? "返回运行记录" : "Back to run records" }).click();
    await expect(page.locator("tbody tr")).toHaveCount(7);
    emptyRecords = true;
    await page.goto("/runs");
    await expect(page.getByRole("heading", { name: zh ? "尚无测试运行" : "No test runs yet" })).toBeVisible();
    await expect(page.getByRole("button", { name: zh ? "下一页" : "Next page" })).toHaveCount(0);
    await chooseOption(page, filters, "COMPLETED");
    await expect(page.getByRole("heading", { name: zh ? "没有符合条件的运行" : "No matching runs" })).toBeVisible();
    expect(errors).toEqual([]);
  });
}

for (const locale of ["zh-CN", "en"]) {
  test(`FIXTURE: editable task name survives steps and is submitted ${locale}`, async ({page}) => {
    const zh = locale === "zh-CN";
    await page.addInitScript(locale => localStorage.setItem("jdbc-test.locale", locale), locale);
    const submitted: Record<string, unknown>[] = [];
    await page.route(url => url.pathname.startsWith("/api/"), route => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (request.method() === "POST") {
        submitted.push(request.postDataJSON());
        return route.fulfill({status: 422, json: {code: "INVALID_SELECTION", status: 422}});
      }
      return route.fulfill({json: path === "/api/connections" ? paged([connection])
        : path === "/api/adapters" ? adapters : path === "/api/test-catalog" ? catalog
        : path === "/api/drivers" ? paged([driver]) : driver});
    });
    await page.goto("/runs/new");
    await page.getByRole("radio", {name: connection.name, exact: true}).check();
    const next = () => page.getByRole("button", {name: /^(Next:|下一步：)/}).click();
    await next(); await next(); await next();
    const field = page.getByRole("textbox", {name: zh ? "任务名称" : "Task name", exact: true});
    await expect(field).toHaveValue(new RegExp(connection.name));
    await field.fill("");
    await expect(page.getByRole("alert")).toContainText(zh ? "请输入任务名称" : "Enter a task name");
    await field.fill("FIXTURE 自定义 regression");
    await page.getByRole("button", {name: zh ? "上一步" : "Back", exact: true}).click();
    await next(); await expect(field).toHaveValue("FIXTURE 自定义 regression");
    for (const width of [1366, 375, 320]) {
      await page.setViewportSize({width, height: 900});
      await expect(page.locator("main")).toHaveCSS("opacity", "1");
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({path: test.info().outputPath(`FIXTURE-task-name-${locale}-${width}.png`), fullPage: true, animations: "disabled"});
    }
    await page.getByRole("checkbox", {name: zh ? /我了解/ : /I understand/}).check();
    await page.getByRole("button", {name: zh ? "开始测试运行" : "Start test run", exact: true}).click();
    await expect.poll(() => submitted.length).toBe(1);
    expect(submitted[0].name).toBe("FIXTURE 自定义 regression");
  });
}

for (const locale of ["zh-CN", "en"]) {
  test(`FIXTURE: bounded history columns and multiple long names ${locale}`, async ({page}) => {
    const {checkRunRecordWidths} = await import("./run-record-widths");
    const rows = Array.from({length: 6}, (_, index) => ({
      ...finishedRun, id: `width-fixture-${index}`,
      name: `FIXTURE ${index} · ${"Long saved task name / 长任务名称 · ".repeat(7)}`,
      connection: {...finishedRun.connection, name: `${"Long connection / 连接 ".repeat(index + 1)}`},
      driver: {...finishedRun.driver, name: `${"Vendor JDBC driver ".repeat(index + 1)}`},
      passed: 245, failed: 10, skipped: 15, aborted: 0,
    }));
    await page.addInitScript(locale => {localStorage.setItem("jdbc-test.locale", locale); localStorage.setItem("jdbc-test.theme", "light");}, locale);
    await page.route(url => url.pathname.startsWith("/api/"), route => {
      expect(route.request().method()).toBe("GET");
      return route.fulfill({json: paged(rows)});
    });
    await page.goto("/runs");
    await expect(page.locator("tbody tr")).toHaveCount(6);
    const measurements = [];
    for (const width of [1280, 1366, 1440, 1920, 2048]) {
      await page.setViewportSize({width, height: 900});
      measurements.push(await checkRunRecordWidths(page));
      const name = page.locator(".run-identity-link").first();
      await expect(name).toHaveAttribute("title", rows[0].name);
      expect(await name.evaluate(node => node.scrollWidth > node.clientWidth)).toBe(true);
      await expect(page.locator("main")).toHaveCSS("opacity", "1");
      await page.screenshot({path: test.info().outputPath(`FIXTURE-multirow-${locale}-${width}.png`), fullPage: true, animations: "disabled"});
    }
    for (const width of [375, 320]) {
      await page.setViewportSize({width, height: 900});
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    }
    console.log("FIXTURE rendered widths", locale, JSON.stringify(measurements));
  });
}
