import { expect, test, type Page } from "@playwright/test";
import { adapters, catalog, connection, driver, finishedRun, page as paged, result } from "../src/test/fixtures";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const fixture = {...finishedRun, status: "FAILED", connection: {...finishedRun.connection, name: "TEST FIXTURE — retained layout / no database execution"}, totalCases: 12, completedCases: 6, passed: 2, failed: 1, skipped: 2,
  accounting: {selected: 12, started: 7, completed: 6, passed: 2, failed: 1, skipped: 2, aborted: 1, pending: 0, running: 0, notRun: 4, incomplete: 2, scenarios: []}};
async function mockReadOnly(page: Page) {
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    const url = new URL(route.request().url());
    expect(route.request().method()).toBe("GET");
    let body: unknown;
    if (url.pathname === "/api/test-catalog") body = catalog;
    else if (url.pathname === "/api/adapters") body = adapters;
    else if (url.pathname === "/api/runs") body = paged([fixture]);
    else if (url.pathname === "/api/runs/run-1") body = fixture;
    else if (url.pathname.endsWith("/results")) body = paged([{...result, status: "SKIPPED", message: "capability arrays=false; skipSource=adapter"}]);
    else if (url.pathname.endsWith("/report.json")) body = {status: "FAILED", profile: "FULL", scenarioId: result.scenarioId, SQLState: "08001", message: "original fixture evidence"};
    else if (url.pathname.endsWith("/report")) body = {summary: fixture, failures: [result]};
    else if (url.pathname === "/api/connections") body = paged([{...connection, name: "Long connection " + "LongEnglishName".repeat(20), jdbcUrl: "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=fixture.invalid)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=" + "LongServiceName".repeat(30) + ")))"}]);
    else if (url.pathname === "/api/drivers") body = paged([driver]);
    else if (url.pathname === "/api/drivers/driver-1") body = driver;
    else throw new Error(`Unhandled read-only fixture: ${url.pathname}`);
    await route.fulfill({json: body});
  });
}
async function switchTo(page: Page, language: "en" | "zh-CN") {
  await page.getByRole("button", {name: /^(Language|界面语言)$/}).click();
  await page.getByRole("menuitemradio", {name: language === "en" ? "English" : "简体中文"}).click();
  await expect(page.locator("html")).toHaveAttribute("lang", language);
}

test("TEST FIXTURE ONLY: initial language, persistence, unsupported browser language and blocked storage", async ({browser}) => {
  for (const [locale, stored, expected, denied] of [["zh-CN", null, "zh-CN", false], ["en-US", null, "en", false], ["fr-FR", "invalid", "en", false], ["zh-CN", "en", "en", false], ["zh-CN", null, "zh-CN", true]] as const) {
    const context = await browser.newContext({locale});
    if (denied) await context.addInitScript(() => { Object.defineProperty(window, "localStorage", {get() { throw new Error("fixture blocked storage"); }}); });
    else if (stored) await context.addInitScript(value => localStorage.setItem("jdbc-test.locale", value), stored);
    const page = await context.newPage(); await mockReadOnly(page);
    await page.goto("/");
    await expect(page.locator("html")).toHaveAttribute("lang", expected);
    await expect(page.getByRole("heading", {name: expected === "en" ? "Workspace overview" : "工作区概览"})).toBeVisible();
    if (!denied && !stored) {
      await switchTo(page, expected === "en" ? "zh-CN" : "en");
      const manual = expected === "en" ? "zh-CN" : "en";
      await page.reload(); await expect(page.locator("html")).toHaveAttribute("lang", manual);
      expect(await page.evaluate(() => Object.keys(localStorage))).toEqual(["jdbc-test.locale"]);
    } else if (denied) await switchTo(page, "en");
    await context.close();
  }
});

test("TEST FIXTURE ONLY: bilingual Dashboard, menu, semantic Run Detail, skipped reason and original JSON", async ({page}) => {
  await mockReadOnly(page);
  const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
  await page.goto("/"); await expect(page.getByRole("heading", {name: "Workspace overview"})).toBeVisible();
  await expect(page.getByRole("img")).toHaveAccessibleName(/2 passed, 1 failed, 2 skipped, 1 aborted/);
  // Let the initial ECharts reveal finish before capturing evidence.
  await page.waitForTimeout(1100);
  await page.screenshot({path: "test-results/fixture-dashboard-en.png", fullPage: true});
  await page.evaluate(() => { (window as Window & {fixtureMarker?: number}).fixtureMarker = 1234; });
  await switchTo(page, "zh-CN");
  expect(await page.evaluate(() => (window as Window & {fixtureMarker?: number}).fixtureMarker)).toBe(1234);
  await expect(page.getByRole("img")).toHaveAccessibleName(/2 通过，1 失败，2 已跳过，1 已中止/);
  await page.screenshot({path: "test-results/fixture-dashboard-zh-CN.png", fullPage: true});
  const gradients = await page.locator("[data-metric]").evaluateAll(cards => cards.map(card => getComputedStyle(card).backgroundImage));
  expect(gradients.every(gradient => gradient.includes("linear-gradient"))).toBe(true);
  expect(new Set(gradients).size).toBe(4);
  await page.getByRole("button", {name: "界面语言"}).click();
  await expect(page.getByRole("menuitemradio", {name: "简体中文"})).toHaveAttribute("aria-checked", "true");
  await page.screenshot({path: "test-results/fixture-language-menu.png", fullPage: true});
  await page.keyboard.press("Escape");
  await page.goto("/runs/run-1");
  await expect(page.getByText("运行失败", {exact: true})).toBeVisible();
  await expect(page.locator('[data-stat="未执行"]')).toContainText("4");
  await expect(page.locator('[data-stat="已开始但未完成"]')).toContainText("2");
  await expect(page.getByRole("progressbar")).toHaveAttribute("value", "6");
  await page.getByText("统计说明", {exact:true}).click();
  await expect(page.getByText(/包含明确跳过/)).toBeVisible();
  await page.screenshot({path: "test-results/fixture-run-detail-zh-CN.png", fullPage: true});
  await switchTo(page, "en");
  await expect(page.getByText("Run failed", {exact: true})).toBeVisible();
  await expect(page.getByRole("progressbar")).toHaveAttribute("value", "6");
  await page.screenshot({path: "test-results/fixture-run-detail-en.png", fullPage: true});
  await page.getByRole("tab", {name: "Test cases"}).click();
  await page.getByRole("button", {name: "Skip reason"}).click();
  await page.keyboard.press("Escape");
  await switchTo(page, "zh-CN");
  await page.getByRole("button", {name: "跳过原因"}).click();
  await expect(page.getByRole("dialog")).toContainText("capability arrays=false; skipSource=adapter");
  await expect(page.getByRole("dialog")).toContainText("能力禁用属于配置");
  await page.keyboard.press("Escape");
  await page.getByRole("tab", {name: "报告", exact: true}).click();
  await page.getByRole("button", {name: "查看报告 JSON"}).click();
  const raw = await page.getByRole("dialog").locator("pre").textContent();
  await page.keyboard.press("Escape");
  await switchTo(page, "en");
  await page.getByRole("button", {name: "View report JSON"}).click();
  expect(await page.getByRole("dialog").locator("pre").textContent()).toBe(raw);
  await expect(page.getByRole("dialog")).toContainText('"SQLState": "08001"');
  expect(errors).toEqual([]);
});

test("TEST FIXTURE ONLY: Chinese and long English/URL layouts at 1440, 768 and 390 pixels", async ({page}) => {
  await mockReadOnly(page);
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({width, height: 900});
    for (const language of ["en", "zh-CN"] as const) {
      await page.goto("/"); await switchTo(page, language);
      for (const route of ["/", "/runs/run-1", "/runs/new"]) {
        await page.goto(route);
        await expect(page.locator("h1")).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await expect(page.getByRole("button", {name: /^(Language|界面语言)$/})).toBeInViewport();
      }
      await page.getByRole("button", {name: language === "en" ? "View URL" : "查看 URL"}).click();
      await expect(page.getByRole("dialog")).toContainText("LongServiceName");
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.keyboard.press("Escape");
    }
  }
});

test("TEST FIXTURE ONLY: browser loads the freshly built bundle digest", async ({page}) => {
  test.skip(process.env.JDBC_TEST_E2E_PREVIEW !== "true", "Requires built preview assets");
  await mockReadOnly(page); await page.goto("/");
  const script = await page.locator('script[type="module"]').getAttribute("src");
  expect(script).toMatch(/^\/assets\/index-.+\.js$/);
  const response = await page.request.get(script!);
  const loaded = await response.body(); const built = readFileSync(`dist${script}`);
  expect(createHash("sha256").update(loaded).digest("hex")).toBe(createHash("sha256").update(built).digest("hex"));
});
