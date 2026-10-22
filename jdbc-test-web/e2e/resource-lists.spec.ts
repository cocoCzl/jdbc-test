import { expect, test } from "@playwright/test";
import { adapters, catalog, connection, driver, page as paged, probe } from "../src/test/fixtures";

const drivers = [driver,
  { ...driver, id: "75b9052a-2222-4333-8444-555555555555", name: "Kingbase JDBC", vendor: "Kingbase", driverClass: "com.kingbase8.Driver", version: "9.0.0", status: "PENDING_VALIDATION" },
  { ...driver, id: "75b9052a-3333-4333-8444-555555555555", name: `Vendor driver ${"LongName".repeat(9)}`, driverClass: "vendor.jdbc.compatibility.longnamespace.Driver", status: "INVALID" },
];
const connections = [connection,
  { ...connection, id: "1c7b0c3c-2222-4333-8444-555555555555", name: "Kingbase read-only connection", databaseType: "KingbaseES", adapterId: "kingbase", jdbcUrl: "jdbc:kingbase8://fixture.invalid:4321/test", lastConnectionTestStatus: "SUCCEEDED", lastConnectionTestAt: driver.updatedAt },
  { ...connection, id: "1c7b0c3c-3333-4333-8444-555555555555", name: `Vendor connection ${"LongName".repeat(8)}`, jdbcUrl: `jdbc:postgresql://fixture.invalid:5432/test?applicationName=${"long-url-".repeat(24)}`, lastConnectionTestStatus: "FAILED", lastConnectionTestAt: driver.updatedAt },
];
for (const language of ["en", "zh-CN"]) {
  for (const theme of ["light", "dark"]) {
    for (const resource of ["drivers", "connections"]) {
      test(`FIXTURE: ${resource} multirow alignment and icons ${language} ${theme}`, async ({ page }) => {
        const zh = language === "zh-CN";
        await page.addInitScript(({ language, theme }) => {
          localStorage.setItem("jdbc-test.locale", language); localStorage.setItem("jdbc-test.theme", theme);
        }, { language, theme });
        await page.route(url => url.pathname.startsWith("/api/"), async route => {
          expect(route.request().method()).toBe("GET");
          const path = new URL(route.request().url()).pathname;
          if (path === "/api/drivers") return route.fulfill({ json: paged(drivers) });
          if (path === "/api/connections") return route.fulfill({ json: paged(connections) });
          throw new Error(`Unexpected fixture request ${path}`);
        });
        await page.goto(`/${resource}`);
        const rows = page.locator("tbody tr");
        await expect(rows).toHaveCount(3);
        for (const item of resource === "drivers" ? drivers : connections) await expect(page.locator("table")).not.toContainText(item.id);
        for (const width of [1366, 1920, 375, 320]) {
          await page.setViewportSize({ width, height: 900 });
          const header = await page.locator("thead th:last-child").boundingBox();
          expect(header).not.toBeNull();
          expect(Math.abs(header!.width - (resource === "drivers" ? 128 : 176))).toBeLessThan(1);
          for (let i = 0; i < 3; i++) {
            const row = rows.nth(i);
            const actions = row.locator(".resource-action");
            await expect(actions).toHaveCount(resource === "drivers" ? 2 : 3);
            const first = await actions.first().boundingBox();
            const last = await actions.last().boundingBox();
            const cell = await row.locator("td:last-child").boundingBox();
            expect(Math.abs((first!.x + last!.x + last!.width) / 2 - (header!.x + header!.width / 2))).toBeLessThan(1);
            expect(Math.abs(first!.y + first!.height / 2 - (cell!.y + cell!.height / 2))).toBeLessThan(1);
            for (const button of await actions.all()) {
              const box = await button.boundingBox();
              expect(box!.width).toBeCloseTo(44, 2); expect(box!.height).toBeCloseTo(44, 2);
              await expect(button).toHaveText("");
              await expect(button.locator("svg")).toHaveCSS("width", "18px");
            }
            await expect(actions.first()).toHaveCSS("color", theme === "light" ? "rgb(37, 99, 235)" : "rgb(139, 182, 255)");
            await expect(actions.last()).toHaveCSS("color", theme === "light" ? "rgb(198, 87, 70)" : "rgb(241, 176, 166)");
          }
          expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
          if (width < 768) {
            await page.locator("table").evaluate(table => { table.parentElement!.scrollLeft = table.scrollWidth; });
            await expect(rows.first().locator(".resource-action").last()).toBeInViewport();
          }
          await page.screenshot({ path: test.info().outputPath(`fixture-${resource}-${language}-${theme}-${width}.png`), fullPage: true });
        }
        const firstRow = rows.first();
        const firstAction = firstRow.locator(".resource-action").first();
        await firstAction.focus();
        await expect(page.getByRole("tooltip")).toHaveText(zh
          ? `${resource === "drivers" ? "检查驱动文件：" : "测试连接："}${resource === "drivers" ? driver.name : connection.name}`
          : `${resource === "drivers" ? "Check driver files: " : "Test connection: "}${resource === "drivers" ? driver.name : connection.name}`);
        for (const action of await firstRow.locator(".resource-action").all()) {
          await action.focus();
          await expect(page.getByRole("tooltip")).toHaveText((await action.getAttribute("aria-label"))!);
          await action.hover();
          await expect(action).not.toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
        }
        if (resource === "connections") await expect(firstRow.getByRole("link", { name: zh ? `开始运行：${connection.name}` : `Start run: ${connection.name}` })).toHaveAttribute("href", `/runs/new?connectionId=${connection.id}`);
      });
    }
  }

  test(`FIXTURE: pending actions, keyboard reasons, delete references and technical ID ${language}`, async ({ page, context }) => {
    const zh = language === "zh-CN";
    let release!: () => void;
    let pending = new Promise<void>(resolve => { release = resolve; });
    const writes: string[] = [];
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    await page.addInitScript(language => localStorage.setItem("jdbc-test.locale", language), language);
    await page.route(url => url.pathname.startsWith("/api/"), async route => {
      const path = new URL(route.request().url()).pathname;
      if (route.request().method() !== "GET") writes.push(path);
      if (route.request().method() === "DELETE") return route.fulfill({ status: 409, json: { code: path.includes("drivers") ? "DRIVER_IN_USE" : "CONNECTION_IN_USE" } });
      if (path.endsWith("/validate")) { await pending; return route.fulfill({ json: { status: "VALIDATED", driverClass: driver.driverClass, majorVersion: 0, minorVersion: 0, jdbcCompliant: false } }); }
      if (path.endsWith("/test")) { await pending; return route.fulfill({ json: probe }); }
      if (path === "/api/drivers") return route.fulfill({ json: paged(drivers) });
      if (path === "/api/connections") return route.fulfill({ json: paged(connections) });
      if (path === "/api/drivers/driver-1") return route.fulfill({ json: driver });
      if (path === "/api/connections/connection-1") return route.fulfill({ json: connection });
      if (path === "/api/adapters") return route.fulfill({ json: adapters });
      if (path === "/api/test-catalog") return route.fulfill({ json: catalog });
      throw new Error(`Unexpected fixture request ${path}`);
    });
    await page.goto("/drivers");
    const check = page.getByRole("button", { name: zh ? `检查驱动文件：${driver.name}` : `Check driver files: ${driver.name}`, exact: true });
    await check.click();
    await expect(check).toBeDisabled(); await expect(check).toHaveAttribute("aria-busy", "true");
    await expect(check.locator(".animate-spin")).toHaveCount(1);
    const other = page.locator("tbody tr").nth(1).locator(".resource-action-trigger").first();
    await other.focus(); await expect(page.getByRole("tooltip")).toContainText(zh ? "正在检查" : "Checking");
    release(); await expect(check).toBeEnabled();
    expect(writes.filter(path => path.endsWith("/validate"))).toHaveLength(1);
    for (const resource of ["drivers", "connections"]) {
      await page.goto(`/${resource}`);
      await page.locator("tbody tr").first().locator(".resource-action").last().click();
      await expect(page).toHaveURL(new RegExp(`/${resource}$`));
      const dialog = page.getByRole("alertdialog");
      expect(writes.filter(path => path === `/api/${resource}/${resource === "drivers" ? driver.id : connection.id}`)).toHaveLength(0);
      await dialog.getByRole("button", { name: resource === "drivers" ? zh ? "删除驱动" : "Delete driver" : zh ? "删除连接" : "Delete connection", exact: true }).click();
      await expect(dialog).not.toBeVisible();
      await expect(page.getByText(resource === "drivers" ? /DRIVER_IN_USE/ : /CONNECTION_IN_USE/)).toBeVisible();
      const item = resource === "drivers" ? driver : connection;
      await page.getByRole("link", { name: item.name, exact: true }).click();
      const technical = page.locator("details").filter({ has: page.locator("summary", { hasText: zh ? /^技术信息$/ : /^Technical information$/ }) });
      await expect(technical).not.toHaveAttribute("open");
      await technical.locator("summary").click();
      await expect(technical.locator("code")).toHaveText(item.id);
      await technical.getByRole("button", { name: zh ? "复制内部 ID" : "Copy Internal ID" }).click();
      expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(item.id);
    }
    await page.goto("/connections");
    const testConnection = page.getByRole("button", { name: zh ? `测试连接：${connection.name}` : `Test connection: ${connection.name}`, exact: true });
    await testConnection.click();
    pending = new Promise<void>(resolve => { release = resolve; });
    await page.getByRole("dialog").getByRole("button", { name: zh ? "测试连接" : "Test connection", exact: true }).click();
    await page.keyboard.press("Escape");
    await expect(testConnection).toBeDisabled(); await expect(testConnection).toHaveAttribute("aria-busy", "true");
    release(); await expect(testConnection).toBeEnabled();
    expect(writes.filter(path => path.endsWith("/test"))).toHaveLength(1);
    await page.getByRole("link", { name: zh ? `开始运行：${connection.name}` : `Start run: ${connection.name}` }).click();
    await expect(page).toHaveURL(`/runs/new?connectionId=${connection.id}`);
    expect(writes.filter(path => path === "/api/runs")).toHaveLength(0);
  });
}
