import { expect, test, type Page } from "@playwright/test";
import { adapters, catalog, connection, driver, driverPresets, finishedRun, page as paged } from "../src/test/fixtures";
import { chooseOption } from "./select";

test.setTimeout(30_000);
const before = process.env.JDBC_TEST_LAYOUT_CAPTURE === "before";
async function fixtureApi(page: Page, mode = "data") {
  const submissions: Record<string, unknown>[] = [];
  const target = mode === "no-drivers" ? { ...connection, defaultDriver: null } : connection;
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/runs" && route.request().method() === "POST") {
      submissions.push(route.request().postDataJSON());
      return route.fulfill({ status: 201, json: finishedRun });
    }
    const body = url.pathname === "/api/adapters" ? adapters
      : url.pathname === "/api/drivers/presets" ? driverPresets
      : url.pathname === "/api/drivers" ? paged(mode === "no-drivers" ? [] : [driver, { ...driver, id: "driver-2", name: "Second JDBC" }])
      : url.pathname.startsWith("/api/drivers/") ? driver
      : url.pathname === "/api/connections" ? paged(mode === "no-connections" || url.searchParams.get("adapterId") === "vendor" ? [] : [target, { ...target, id: "connection-2", name: "Second database" }])
      : url.pathname.startsWith("/api/connections/") ? target
      : url.pathname === "/api/test-catalog" ? mode === "no-cases" ? { ...catalog, scenarios: [], totalScenarioCount: 0 } : catalog
      : url.pathname === "/api/runs/run-1" ? finishedRun : paged([]);
    return route.fulfill({ json: body });
  });
  return submissions;
}
async function ready(page: Page) {
  await page.evaluate(() => document.fonts.ready);
  await expect(page.locator("main")).toHaveCSS("opacity", "1");
}
async function screenshot(page: Page, name: string) {
  await ready(page);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: test.info().outputPath(`${before ? "before" : "after"}/fixture-${name}.png`), animations: "disabled" });
}
async function aligned(page: Page, selector: string) {
  if (before) return;
  const nav = await page.getByRole("navigation").boundingBox();
  const box = await page.locator(selector).boundingBox();
  expect(Math.abs(box!.x - nav!.x)).toBeLessThanOrEqual(2);
  expect(Math.abs(box!.width - nav!.width)).toBeLessThanOrEqual(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}

for (const language of ["zh-CN", "en"]) for (const width of [1366, 1920, 375, 320]) {
  test(`TEST FIXTURE ONLY: wide forms ${language} ${width}`, async ({ page }) => {
    const zh = language === "zh-CN";
    await page.emulateMedia({ reducedMotion: "reduce", colorScheme: "light" });
    await page.addInitScript(locale => localStorage.setItem("jdbc-test.locale", locale), language);
    const submissions = await fixtureApi(page);
    await page.setViewportSize({ width, height: width >= 1000 ? 900 : 812 });
    await page.goto("/drivers/new");
    await chooseOption(page, page.locator("#driver-preset"), "kingbase");
    await chooseOption(page, page.locator("#sql-dialect"), "postgresql");
    await page.locator("#driver-name").fill("Kingbase JDBC");
    await page.locator("#driver-version").fill("9.0.0");
    await page.getByLabel(zh ? "JAR 文件" : "JAR files", { exact: true }).setInputFiles({ name: "kingbase-fixture.jar", mimeType: "application/java-archive", buffer: Buffer.from("TEST FIXTURE ONLY") });
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(page.getByRole("button", { name: zh ? "选择 JAR 文件" : "Choose JAR files" })).toBeInViewport();
    await aligned(page, "main > div");
    await expect(page.locator("details[open]")).toHaveCount(0);
    if (!before && width >= 1000) {
      const summary = await page.locator("form > aside").boundingBox();
      expect(summary!.width).toBeGreaterThanOrEqual(280);
      expect(summary!.width).toBeLessThanOrEqual(320);
    }
    await screenshot(page, `add-driver-${language}-${width}`);
    await page.locator("#driver-class").scrollIntoViewIfNeeded();
    await expect(page.locator("#sql-dialect")).toHaveText("PostgreSQL");
    await screenshot(page, `add-driver-filled-${language}-${width}`);
    await page.goto("/runs/new");
    await expect(page.getByRole("radio", { name: connection.name })).toBeVisible();
    for (let step = 0; step < 4; step++) {
      await aligned(page, "main > div");
      if (!before) {
        await aligned(page, ".wizard-steps");
        await aligned(page, ".wizard-panel");
        await aligned(page, ".wizard-actions");
      }
      await screenshot(page, `new-run-step-${step + 1}-${language}-${width}`);
      if (step === 0) await page.getByRole("radio", { name: connection.name }).check();
      if (step === 2) {
        await page.getByRole("checkbox", { name: zh ? "连接（Connection）" : "Connection", exact: true }).uncheck();
        await page.getByRole("searchbox").fill("statement.case_1");
        await page.getByRole("searchbox").clear();
      }
      if (step < 3) await page.getByRole("button", { name: /^(Next:|下一步：)/ }).click();
    }
    await page.getByRole("checkbox", { name: zh ? /我了解/ : /I understand/ }).check();
    await page.getByRole("button", { name: zh ? "开始测试运行" : "Start test run", exact: true }).click();
    await expect.poll(() => submissions.length).toBe(1);
    expect(submissions[0]).toMatchObject({ connectionId: connection.id, driverId: driver.id, profile: "CUSTOM", destructiveConsent: true });
    expect(submissions[0].includeScenarios).toHaveLength(23);
  });
}

for (const language of ["en", "zh-CN"]) {
  test(`TEST FIXTURE ONLY: compact empty states and clear filter ${language}`, async ({ page }) => {
    test.skip(before);
    const zh = language === "zh-CN";
    await page.setViewportSize({ width: 320, height: 812 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.addInitScript(locale => localStorage.setItem("jdbc-test.locale", locale), language);
    await fixtureApi(page, "no-connections");
    await page.goto("/runs/new");
    await expect(page.getByRole("link", { name: zh ? "添加连接" : "Add connection", exact: true })).toHaveAttribute("href", "/connections/new");
    await expect(page.getByText(/(?:Page 1 of|第 1 页)/)).toHaveCount(0);
    const empty = page.locator(".jdbc-empty");
    expect((await empty.boundingBox())!.height).toBeLessThan(230);
    await screenshot(page, `new-run-empty-${language}-320`);
    await chooseOption(page, page.locator("#run-adapter-filter"), "vendor");
    await expect(page.getByRole("link", { name: zh ? "添加连接" : "Add connection", exact: true })).toHaveCount(0);
    await screenshot(page, `new-run-filter-empty-${language}-320`);
    await page.getByRole("button", { name: zh ? "清除筛选" : "Clear filter" }).click();
    await expect(page.locator("#run-adapter-filter")).toHaveText(zh ? "全部类型" : "All types");
  });
}

for (const language of ["en", "zh-CN"]) for (const mode of ["no-drivers", "no-cases"]) {
  test(`TEST FIXTURE ONLY: unavailable ${mode} cannot advance ${language}`, async ({ page }) => {
    test.skip(before);
    const zh = language === "zh-CN";
    await page.setViewportSize({ width: 375, height: 812 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.addInitScript(locale => localStorage.setItem("jdbc-test.locale", locale), language);
    await fixtureApi(page, mode);
    await page.goto("/runs/new");
    await page.getByRole("radio", { name: connection.name }).check();
    await page.getByRole("button", { name: /^(Next:|下一步：)/ }).click();
    if (mode === "no-drivers") {
      await expect(page.getByRole("link", { name: zh ? "上传驱动包" : "Upload a driver package" })).toHaveAttribute("href", "/drivers/new");
      await expect(page.getByText(/(?:Page 1 of|第 1 页)/)).toHaveCount(0);
    } else {
      await page.getByRole("button", { name: /^(Next:|下一步：)/ }).click();
      await expect(page.getByRole("searchbox")).toBeVisible();
    }
    await expect(page.getByRole("button", { name: /^(Next:|下一步：)/ })).toBeDisabled();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await screenshot(page, `new-run-${mode}-${language}-375`);
  });
}

for (const language of ["en", "zh-CN"]) for (const width of [1920, 320]) {
  test(`TEST FIXTURE ONLY: dark forms and four steps ${language} ${width}`, async ({ page }) => {
    test.skip(before);
    await page.setViewportSize({ width, height: width === 1920 ? 900 : 812 });
    await page.emulateMedia({ reducedMotion: "reduce", colorScheme: "dark" });
    await page.addInitScript(locale => { localStorage.setItem("jdbc-test.locale", locale); localStorage.setItem("jdbc-test.theme", "dark"); }, language);
    await fixtureApi(page);
    await page.goto("/drivers/new");
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    await aligned(page, "main > div");
    await screenshot(page, `add-driver-dark-${language}-${width}`);
    const primary = page.getByRole("button", { name: language === "en" ? "Confirm upload" : "确认上传" });
    expect(await primary.evaluate(element => getComputedStyle(element).backgroundColor)).not.toBe("rgb(139, 134, 128)");
    await page.goto("/runs/new?connectionId=connection-1");
    for (let step = 0; step < 4; step++) {
      await aligned(page, ".wizard-panel");
      await aligned(page, ".wizard-actions");
      await expect(page.locator('[aria-current="step"]')).toHaveCount(1);
      await screenshot(page, `new-run-step-${step + 1}-dark-${language}-${width}`);
      if (step < 3) await page.getByRole("button", { name: /^(Next:|下一步：)/ }).click();
    }
  });
}
