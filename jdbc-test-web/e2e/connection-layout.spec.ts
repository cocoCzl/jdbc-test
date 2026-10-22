import { expect, test } from "@playwright/test";
import { connection, driver, page as paged, probe } from "../src/test/fixtures";
import { chooseOption } from "./select";

const adapter = {
  id: "kingbase", name: "KingbaseES", status: "OFFICIAL", origin: "BUILTIN",
  version: "1.0.0", revision: "official-1", dialect: "postgresql",
  namespaceSelection: "schema", requiresDatabase: true, driverClass: "com.kingbase8.Driver",
  fingerprint: "a".repeat(64), fingerprintScope: "MANIFEST",
  matchConditions: { expected_database_product_regex: "^KingbaseES$", expected_driver_name_regex: "^Kingbase8 JDBC Driver$" },
  qualificationNoteKey: "adapter.kingbaseQualification",
  validatedCombinations: [{ databaseVersion: "12.1", driverVersion: "V008R006B0001" }],
};
const fixtureDriver = { ...driver, defaultSqlDialect: "postgresql" as const, name: "Kingbase JDBC", vendor: "Kingbase", version: "9.0.0" };

for (const language of ["en", "zh-CN"]) {
  for (const editing of [false, true]) {
    test(`TEST FIXTURE ONLY: compact connection ${editing ? "edit" : "add"}, test and save in ${language}`, async ({ page }) => {
      const chinese = language === "zh-CN";
      let saved = { ...connection, name: "Kingbase test", databaseType: "KingbaseES", adapterId: "kingbase",
        jdbcUrl: "jdbc:kingbase8://192.0.2.30:4321/test", username: "jdbc_tester",
        executionDatabase: "test", executionSchema: "authorized_schema", defaultDriver: fixtureDriver,
        description: "Retained fixture description" };
      const tests: { path: string; body: Record<string, unknown> }[] = [];
      const saves: { method: string; body: Record<string, unknown> }[] = [];
      const errors: string[] = [];
      page.on("pageerror", error => errors.push(error.message));
      await page.addInitScript(locale => localStorage.setItem("jdbc-test.locale", locale), language);
      await page.route(url => url.pathname.startsWith("/api/"), async route => {
        const path = new URL(route.request().url()).pathname;
        if (path === "/api/adapters") return route.fulfill({ json: [adapter] });
        if (path === "/api/drivers") return route.fulfill({ json: paged([fixtureDriver]) });
        if (path.endsWith("/test")) {
          tests.push({ path, body: route.request().postDataJSON() });
          if (tests.length === 1) return route.fulfill({ status: 422, json: { code: "CONNECTION_TIMEOUT", status: 422, detail: "Fixture timeout", properties: { stage: "ESTABLISHING_CONNECTION", diagnostics: [{ sqlState: "ZZ999", vendorCode: 71, message: "Unknown [REDACTED]", exceptionType: "java.sql.SQLException" }] } } });
          return route.fulfill({ json: probe });
        }
        if (path === "/api/connections" || path === "/api/connections/connection-1") {
          const method = route.request().method();
          if (method !== "GET") {
            const input = route.request().postDataJSON();
            saves.push({ method, body: input });
            saved = { ...saved, ...input, password: undefined, updatedAt: "2026-10-22T03:00:00Z" };
          }
          return route.fulfill({ status: method === "POST" ? 201 : 200, json: saved });
        }
        throw new Error(`Unexpected connection fixture request ${path}`);
      });
      await page.setViewportSize({ width: 1366, height: 768 });
      await page.goto(editing ? "/connections/connection-1" : "/connections/new");
      await expect(page.locator("#connection-name")).toBeVisible();
      if (!editing) {
        await page.locator("#connection-name").fill("Kingbase test");
        await chooseOption(page, page.locator("#database-type"), "kingbase");
        await chooseOption(page, page.locator("#default-driver"), fixtureDriver.id);
        await page.locator("#jdbc-url").fill(saved.jdbcUrl);
        await page.locator("#username").fill(saved.username);
        await page.locator("#execution-database").fill(saved.executionDatabase);
        await page.locator("#execution-schema").fill(saved.executionSchema);
      }
      await expect(page.getByText(chinese ? "适配详情" : "Adapter details", { exact: true })).toHaveCount(0);
      await expect(page.locator("form aside")).toHaveCount(0);
      await expect(page.locator("#database-type")).not.toContainText(chinese ? "官方" : "Official");
      await expect(page.getByText(/270/)).toHaveCount(0);
      await expect(page.getByText("com.kingbase8.Driver", { exact: true })).toHaveCount(0);
      await expect(page.getByText(adapter.fingerprint, { exact: true })).not.toBeVisible();
      const save = page.getByRole("button", { name: chinese ? "保存连接" : "Save connection", exact: true });
      const testConnection = page.getByRole("button", { name: chinese ? "测试连接" : "Test connection", exact: true });
      for (const width of [1366, 375, 320]) {
        await page.setViewportSize({ width, height: width === 1366 ? 768 : 812 });
        await page.evaluate(() => window.scrollTo(0, 0));
        if (width === 1366) {
          await expect(page.locator("#jdbc-url")).toBeInViewport({ ratio: 1 });
          await expect(page.locator("#username")).toBeInViewport({ ratio: 1 });
          await expect(page.locator("#password")).toBeInViewport({ ratio: 1 });
        }
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.evaluate(() => document.fonts.ready);
        await page.screenshot({ path: test.info().outputPath(`fixture-${language}-${editing ? "edit" : "add"}-${width}.png`), fullPage: true, animations: "disabled" });

      }
      if (!editing) await page.locator("#password").fill("fixture-initial-password");
      await testConnection.click();
      await expect(page.getByRole("alert")).toContainText("CONNECTION_TIMEOUT");
      const errorDetails = page.getByText(chinese ? "脱敏错误详情" : "Redacted error details", { exact: true }).locator("..");
      await expect(errorDetails).not.toHaveAttribute("open");
      await errorDetails.locator("summary").click();
      await expect(page.getByText("ZZ999", { exact: true })).toBeVisible();
      await expect(page.getByText("71", { exact: true })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      expect(saves).toEqual([]);
      await page.locator("#password").fill("fixture-transient-password");
      await testConnection.click();
      await expect(page.getByText(chinese ? "连接成功" : "Connected", { exact: true })).toBeVisible();
      await expect(page.locator("#password")).toHaveValue("fixture-transient-password");
      await expect(page.getByRole("alert")).toHaveCount(0);
      expect(tests[0]).toMatchObject({ path: "/api/connections/test" });
      expect(tests[1].body.password).toBe("fixture-transient-password");
      if (editing) { expect(tests[0].body.savedConnectionId).toBe(connection.id); expect(tests[0].body).not.toHaveProperty("password"); }
      else expect(tests[0].body).toMatchObject({ adapterId: "kingbase", driverId: fixtureDriver.id,
        executionDatabase: "test", executionSchema: "authorized_schema", jdbcUrl: saved.jdbcUrl });
      await page.locator("#connection-name").fill("Kingbase saved fixture");
      await save.click();
      await expect(page).toHaveURL(/\/connections\/connection-1$/);
      await expect(page.getByRole("heading", { name: "Kingbase saved fixture", exact: true })).toBeVisible();
      expect(saves).toHaveLength(1);
      expect(saves[0]).toMatchObject({ method: editing ? "PATCH" : "POST", body: {
        name: "Kingbase saved fixture", databaseType: "KingbaseES", adapterId: "kingbase",
        defaultDriverId: fixtureDriver.id, jdbcUrl: saved.jdbcUrl, username: "jdbc_tester",
        executionDatabase: "test", executionSchema: "authorized_schema", savePassword: true, sqlDialect: "postgresql",
        description: editing ? "Retained fixture description" : "",
      } });
      expect(saves[0].body.password).toBe("fixture-transient-password");
      expect(await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage))).not.toContain("fixture-transient-password");
      expect(errors).toEqual([]);
    });
  }

  test(`TEST FIXTURE ONLY: connection compatibility and missing configuration remain visible in ${language}`, async ({ page }) => {
    const chinese = language === "zh-CN";
    await page.addInitScript(locale => localStorage.setItem("jdbc-test.locale", locale), language);
    await page.route(url => url.pathname.startsWith("/api/"), async route => {
      const path = new URL(route.request().url()).pathname;
      if (path === "/api/adapters") return route.fulfill({ json: [adapter] });
      if (path === "/api/drivers") return route.fulfill({ json: paged([]) });
      if (path === "/api/connections/connection-1") return route.fulfill({ json: {
        ...connection, name: "Retired adapter fixture", adapterId: "retired", defaultDriver: null,
      } });
      throw new Error(`Unexpected missing-configuration fixture request ${path}`);
    });
    await page.setViewportSize({ width: 320, height: 812 });
    await page.goto("/connections/new");
    await chooseOption(page, page.locator("#database-type"), "compatible");
    await expect(page.locator("#adapter")).toHaveAttribute("data-value", "");
    await expect(page.getByText(chinese ? "选择数据库支持的兼容方言；这不代表产品兼容性已验证。" : "Choose the dialect your database supports. This does not confirm product compatibility.")).toBeVisible();
    await expect(page.getByRole("link", { name: chinese ? "上传驱动包" : "Upload a driver package" })).toBeVisible();
    await chooseOption(page, page.locator("#adapter"), "kingbase");
    await page.getByRole("button", { name: chinese ? "测试连接" : "Test connection", exact: true }).click();
    await expect(page.locator("#default-driver")).toHaveAttribute("aria-invalid", "true");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.goto("/connections/connection-1");
    await expect(page.getByRole("alert")).toContainText(chinese ? "此连接引用已撤回" : "This connection references a retired");
    await page.getByRole("button", { name: chinese ? "保存连接" : "Save connection", exact: true }).click();
    await expect(page.locator("#database-type")).toBeFocused();
    await expect(page.locator("#database-type-error")).toHaveText(chinese ? "请选择数据库类型。" : "Select a database type.");
  });

  test(`TEST FIXTURE ONLY: dialect overrides, database scopes and saved credentials in ${language}`, async ({ page }) => {
    const chinese = language === "zh-CN";
    let saved: Record<string, unknown> = { ...connection };
    const saves: Record<string, unknown>[] = [];
    const probes: Record<string, unknown>[] = [];
    const scopeAdapters = ["mysql", "postgresql", "oracle", "kingbase"].map(id => ({
      ...adapter, id, name: id, dialect: id === "kingbase" ? "postgresql" : id,
      requiresDatabase: id !== "oracle", namespaceSelection: id === "mysql" ? "catalog" : "schema",
    }));
    await page.addInitScript(locale => localStorage.setItem("jdbc-test.locale", locale), language);
    await page.route(url => url.pathname.startsWith("/api/"), async route => {
      const path = new URL(route.request().url()).pathname;
      if (path === "/api/adapters") return route.fulfill({ json: scopeAdapters });
      if (path === "/api/drivers") return route.fulfill({ json: paged([fixtureDriver]) });
      if (path.endsWith("/test")) { probes.push(route.request().postDataJSON()); return route.fulfill({ json: probe }); }
      if (path.startsWith("/api/connections")) {
        if (route.request().method() !== "GET") {
          const input = route.request().postDataJSON();
          saves.push(input);
          saved = { ...connection, ...input, password: undefined, hasSavedPassword: true, sqlDialect: input.sqlDialect,
            defaultDriver: fixtureDriver, updatedAt: new Date().toISOString() };
        }
        return route.fulfill({ json: saved, status: route.request().method() === "POST" ? 201 : 200 });
      }
      throw new Error(`Unexpected scope fixture request ${path}`);
    });
    for (const adapter of scopeAdapters) {
      await page.goto("/connections/new");
      await page.locator("#connection-name").fill(`TEST FIXTURE ${adapter.id}`);
      await chooseOption(page, page.locator("#database-type"), adapter.id);
      await chooseOption(page, page.locator("#default-driver"), fixtureDriver.id);
      await expect(page.locator("#sql-dialect")).toHaveAttribute("data-value", "postgresql");
      if (adapter.dialect !== "postgresql") {
        await expect(page.getByRole("alert")).toContainText(chinese ? "没有所选 SQL 方言" : "no matching resources");
        await chooseOption(page, page.locator("#sql-dialect"), adapter.dialect);
      }
      await page.locator("#jdbc-url").fill("jdbc:fixture:scope");
      await page.locator("#password").fill("fixture-memory-password");
      const testConnection = page.getByRole("button", { name: chinese ? "测试连接" : "Test connection", exact: true });
      const save = page.getByRole("button", { name: chinese ? "保存连接" : "Save connection", exact: true });
      // Probing does not require scope, while saving explicitly validates the run scope.
      await testConnection.click();
      await expect(page.getByText(chinese ? "连接成功" : "Connected", { exact: true })).toBeVisible();
      await expect(page.locator("#password")).toHaveValue("fixture-memory-password");
      const savesBefore = saves.length;
      await save.click();
      expect(saves.length).toBe(savesBefore);
      if (adapter.requiresDatabase) {
        await expect(page.locator("#execution-database")).toHaveAttribute("aria-invalid", "true");
        await page.locator("#execution-database").fill("test");
      } else await expect(page.locator("#execution-database")).toHaveCount(0);
      if (adapter.namespaceSelection === "schema") await page.locator("#execution-schema").fill("owned_scope");
      else await expect(page.locator("#execution-schema")).toHaveCount(0);
      for (const width of [1366, 375, 320]) {
        await page.setViewportSize({ width, height: width === 1366 ? 768 : 812 });
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      }
      await save.click();
      await expect(page).toHaveURL(/\/connections\/connection-1$/);
      expect(saves.at(-1)).toMatchObject({ sqlDialect: adapter.dialect, savePassword: true, password: "fixture-memory-password" });
      // Reload represents a fresh edit; only the saved state comes back, never the password.
      await page.reload();
      await expect(page.locator("#sql-dialect")).toHaveAttribute("data-value", adapter.dialect);
      await expect(page.locator("#password")).toHaveValue("");
      await expect(page.getByText(chinese ? "密码已保存" : "Password saved", { exact: true })).toBeVisible();
      await page.getByRole("button", { name: chinese ? "测试连接" : "Test connection", exact: true }).click();
      await expect(page.getByText(chinese ? "连接成功" : "Connected", { exact: true })).toBeVisible();
      expect(probes.at(-1)).toMatchObject({ savedConnectionId: connection.id });
      expect(probes.at(-1)).not.toHaveProperty("password");
      expect(await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage))).not.toContain("fixture-memory-password");
    }
  });
}
