import { expect, test } from "@playwright/test";
import { adapters, catalog, connection, driver, page as paged } from "../src/test/fixtures";
import { chooseOption } from "./select";

// Fixture-only API. This suite never submits a run or contacts JDBC.
for (const language of ["zh-CN", "en"]) for (const theme of ["light", "dark"]) for (const count of [1, 3]) {
  test(`FIXTURE: compact selection ${language} ${theme} ${count} rows`, async ({ page, context }) => {
    const zh = language === "zh-CN";
    const url = `jdbc:postgresql://fixture.invalid:5432/test?applicationName=${"long-name-".repeat(22)}&password=fixture-secret`;
    const items = [{ ...connection, jdbcUrl: url, sqlDialect: "postgresql" },
      { ...connection, id: "second", name: `Long connection ${"LongName".repeat(8)}`, databaseType: "KingbaseES", lastConnectionTestStatus: "SUCCEEDED", lastConnectionTestAt: driver.updatedAt },
      { ...connection, id: "third", name: "Third connection", lastConnectionTestStatus: "FAILED", lastConnectionTestAt: driver.updatedAt }].slice(0, count);
    let posts = 0;
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    await page.addInitScript(({ language, theme }) => { localStorage.setItem("jdbc-test.locale", language); localStorage.setItem("jdbc-test.theme", theme); }, { language, theme });
    await page.route(url => url.pathname.startsWith("/api/"), async route => {
      expect(route.request().method()).toBe("GET");
      if (route.request().method() !== "GET") posts++;
      const url = new URL(route.request().url());
      const body = url.pathname === "/api/adapters" ? [{ ...adapters[0], version: "1.0.0", revision: "official-1", fingerprint: "a".repeat(64), matchConditions: { expected_database_product_regex: "^FixtureDB$" } }, adapters[1]]
        : url.pathname === "/api/connections" ? paged(url.searchParams.get("adapterId") === "vendor" ? [] : items)
        : url.pathname === "/api/drivers" ? paged([driver]) : url.pathname === "/api/drivers/driver-1" ? driver
        : url.pathname === "/api/test-catalog" ? catalog : undefined;
      if (!body) throw new Error(`Unexpected fixture ${url.pathname}`);
      return route.fulfill({ json: body });
    });
    await page.goto("/runs/new");
    const rows = page.locator(".run-connection-row");
    await expect(rows).toHaveCount(count);
    await expect(page.getByText(zh ? "适配详情" : "Adapter details", { exact: true })).toHaveCount(0);
    await expect(page.getByText("^FixtureDB$", { exact: true })).toHaveCount(0);
    await expect(page.getByText("1.0.0 (official-1)", { exact: true })).toHaveCount(0);
    await expect(rows.first().locator("time")).toHaveCount(0);
    await expect(rows.first().getByText(zh ? "未测试" : "Not tested", { exact: true })).toBeVisible();
    const radio = page.getByRole("radio", { name: connection.name });
    await rows.first().getByRole("button", { name: zh ? "查看 URL" : "View URL", exact: true }).click();
    await expect(page.getByRole("dialog")).not.toContainText("fixture-secret");
    await expect(page.getByRole("dialog")).toContainText("[REDACTED]");
    await page.keyboard.press("Escape");
    await rows.first().getByRole("button", { name: zh ? "复制脱敏 URL" : "Copy sanitized URL", exact: true }).click();
    expect(await page.evaluate(() => navigator.clipboard.readText())).not.toContain("fixture-secret");
    await expect(radio).not.toBeChecked();
    await rows.first().locator(".run-connection-type").click();
    await expect(radio).toBeChecked();
    await expect(page.locator(".wizard-current-connection")).toContainText(connection.name);
    if (count === 3) {
      await radio.focus(); await page.keyboard.press("ArrowDown");
      await expect(page.getByRole("radio", { name: items[1].name })).toBeChecked();
      await radio.check();
    }
    await expect(page.locator("[data-sonner-toast]")).toHaveCount(0);
    for (const width of [1366, 1920, 375, 320]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      const list = await page.locator(".run-connection-list").boundingBox();
      const toolbar = await page.locator(".run-connection-toolbar").boundingBox();
      expect(Math.abs(list!.x - toolbar!.x)).toBeLessThan(1);
      if (width >= 1024) {
        const row = await rows.first().boundingBox();
        expect(row!.height).toBeLessThan(110);
        const filter = await page.locator("#run-adapter-filter").boundingBox();
        expect(filter!.width).toBeGreaterThanOrEqual(220); expect(filter!.width).toBeLessThanOrEqual(280);
        expect(filter!.x).toBeGreaterThan(toolbar!.x + toolbar!.width / 2);
        const name = await rows.first().locator(".run-connection-name").boundingBox();
        const address = await rows.first().locator(".run-connection-url").boundingBox();
        expect(address!.x).toBeGreaterThan(name!.x + name!.width);
      }
      await page.screenshot({ path: test.info().outputPath(`fixture-${language}-${theme}-${count}-rows-${width}.png`), fullPage: true, animations: "disabled" });
    }
    await chooseOption(page, page.locator("#run-adapter-filter"), "vendor");
    await expect(page.getByText(zh ? "没有符合筛选的连接" : "No matching connections", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: /^(Next:|下一步：)/ })).toBeDisabled();
    await expect(page.locator(".wizard-current-connection")).toContainText(connection.name);
    await expect(page.getByRole("status").filter({ hasText: connection.name })).toBeVisible();
    await page.getByRole("button", { name: zh ? "清除筛选" : "Clear filter", exact: true }).click();
    await expect(radio).toBeChecked();
    await expect(page.getByRole("button", { name: /^(Next:|下一步：)/ })).toBeEnabled();
    await page.getByRole("button", { name: /^(Next:|下一步：)/ }).click();
    await page.getByRole("button", { name: /^(Next:|下一步：)/ }).click();
    await page.getByRole("button", { name: /^(Next:|下一步：)/ }).click();
    await expect(page.getByText(/SQL (?:dialect|方言): postgresql/)).toBeVisible();
    expect(posts).toBe(0);
  });
}
