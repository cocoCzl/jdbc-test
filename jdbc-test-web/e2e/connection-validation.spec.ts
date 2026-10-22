import { expect, test } from "@playwright/test";
import { adapters, connection, driver, page as paged, probe } from "../src/test/fixtures";
import { chooseOption } from "./select";

// Every API is intercepted. Credentials are disposable fixtures; no JDBC is invoked.
for (const language of ["en", "zh-CN"]) {
  for (const theme of ["white", "dark"]) {
    test(`FIXTURE: validation, autofill paint/state, pending actions and edit ${language} ${theme}`, async ({ page }) => {
      const zh = language === "zh-CN";
      const secret = "fixture-autofilled-secret";
      const requests: { path: string; body: Record<string, unknown> }[] = [];
      const browserErrors: string[] = [];
      page.on("pageerror", error => browserErrors.push(error.message));
      let release!: () => void;
      const pending = new Promise<void>(resolve => { release = resolve; });
      let saved = { ...connection, sqlDialect: "postgresql", defaultDriver: { ...driver, defaultSqlDialect: "mysql" } };
      await page.addInitScript(({ language, theme }) => {
        localStorage.setItem("jdbc-test.locale", language);
        localStorage.setItem("jdbc-test.theme", theme === "white" ? "light" : "dark");
      }, { language, theme });
      await page.route(url => url.pathname.startsWith("/api/"), async route => {
        const path = new URL(route.request().url()).pathname;
        if (path === "/api/adapters") return route.fulfill({ json: adapters });
        // No explicit database association: a PG dialect alone cannot identify the database.
        if (path === "/api/drivers") return route.fulfill({ json: paged([{ ...driver, driverClass: "vendor.CustomDriver", defaultSqlDialect: "postgresql" }]) });
        if (route.request().method() !== "GET") requests.push({ path, body: route.request().postDataJSON() });
        if (path.endsWith("/test")) { await pending; return route.fulfill({ json: probe }); }
        if (path === "/api/connections" || path === "/api/connections/connection-1") {
          if (route.request().method() !== "GET") {
            const input = route.request().postDataJSON();
            saved = { ...saved, ...input, password: undefined, hasSavedPassword: true };
          }
          return route.fulfill({ status: route.request().method() === "POST" ? 201 : 200, json: saved });
        }
        throw new Error(`Unexpected fixture ${path}`);
      });
      await page.goto("/connections/new");
      await chooseOption(page, page.locator("#default-driver"), driver.id);
      await expect(page.locator("#sql-dialect")).toHaveAttribute("data-value", "postgresql");
      await expect(page.locator("#database-type")).toHaveAttribute("data-value", "");
      const testButton = () => page.getByRole("button", { name: zh ? "测试连接" : "Test connection", exact: true });
      const saveButton = () => page.getByRole("button", { name: zh ? "保存连接" : "Save connection", exact: true });
      await testButton().click();
      await expect(page.locator("#database-type")).toBeFocused();
      await expect(page.locator("#database-type-error")).toHaveText(zh ? "请选择数据库类型。" : "Select a database type.");
      expect(requests).toHaveLength(0);
      for (const width of [1366, 375, 320]) {
        await page.setViewportSize({ width, height: 900 });
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.screenshot({ path: test.info().outputPath(`fixture-missing-type-${language}-${theme}-${width}.png`), fullPage: true });
      }
      await chooseOption(page, page.locator("#database-type"), "postgresql");
      await page.locator("#jdbc-url").fill("jdbc:postgresql://fixture.invalid/test");
      await expect(page.locator("#username")).toHaveAttribute("autocomplete", "username");
      await expect(page.locator("#password")).toHaveAttribute("autocomplete", "current-password");
      // Emulate a password manager that writes DOM values without React input events.
      // Force Chromium's real autofill pseudo state to exercise its UA paint and our theme override.
      await page.evaluate(secret => {
        const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
        setter.call(document.querySelector("#username"), "fixture-autofilled-user");
        setter.call(document.querySelector("#password"), secret);
      }, secret);
      const session = await page.context().newCDPSession(page);
      await session.send("DOM.enable"); await session.send("CSS.enable");
      const { root } = await session.send("DOM.getDocument");
      for (const selector of ["#username", "#password"]) {
        const { nodeId } = await session.send("DOM.querySelector", { nodeId: root.nodeId, selector });
        await session.send("CSS.forcePseudoState", { nodeId, forcedPseudoClasses: ["autofill"] });
      }
      const fillStyles = await page.locator("#username").evaluate(el => ({
        autofill: el.matches(":autofill"),
        shadow: getComputedStyle(el).boxShadow,
        text: getComputedStyle(el).webkitTextFillColor,
      }));
      expect(fillStyles.autofill).toBe(true);
      expect(fillStyles.shadow).toContain("inset");
      expect(fillStyles.shadow).toContain(theme === "white" ? "rgb(255, 255, 255)" : "rgb(29, 27, 24)");
      expect(fillStyles.text).toBe(theme === "white" ? "rgb(45, 42, 38)" : "rgb(246, 244, 241)");
      await page.locator("#password").focus();
      await expect(page.locator("#password")).toHaveCSS("border-color", theme === "white" ? "rgb(37, 99, 235)" : "rgb(139, 182, 255)");
      for (const width of [1366, 375, 320]) {
        await page.setViewportSize({ width, height: 900 });
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.screenshot({ path: test.info().outputPath(`fixture-autofill-${language}-${theme}-${width}.png`), fullPage: true });
      }
      await testButton().click();
      await expect(page.getByRole("button", { name: zh ? "正在测试连接…" : "Testing connection…", exact: true })).toBeDisabled();
      await expect(saveButton()).toBeDisabled();
      await expect.poll(() => requests.length).toBe(1);
      expect(requests[0].body).toMatchObject({ databaseType: "PostgreSQL", adapterId: "postgresql", username: "fixture-autofilled-user", password: secret });
      release();
      await expect(page.getByText(zh ? "连接成功" : "Connected", { exact: true })).toBeVisible();
      await expect(page.locator("#password")).toHaveValue(secret);
      await expect(testButton()).toBeEnabled();
      // Scope and name are save-only constraints, not prerequisites for a pure probe.
      await saveButton().click();
      await expect(page.locator("#connection-name")).toBeFocused();
      await expect(page.locator("#execution-database")).toHaveAttribute("aria-invalid", "true");
      await page.locator("#connection-name").fill("FIXTURE saved autofill");
      await page.locator("#execution-database").fill("test");
      await page.locator("#execution-schema").fill("owned_scope");
      await saveButton().click();
      await expect(page).toHaveURL(/\/connections\/connection-1$/);
      expect(requests.at(-1)?.body).toMatchObject({ password: secret, savePassword: true, sqlDialect: "postgresql" });
      await page.reload();
      await expect(page.locator("#password")).toHaveValue("");
      await expect(page.getByText(zh ? "密码已保存" : "Password saved", { exact: true })).toBeVisible();
      await expect(page.locator("#sql-dialect")).toHaveAttribute("data-value", "postgresql");
      await testButton().click();
      await expect(page.getByText(zh ? "连接成功" : "Connected", { exact: true })).toBeVisible();
      expect(requests.at(-1)?.body).toMatchObject({ savedConnectionId: connection.id });
      expect(requests.at(-1)?.body).not.toHaveProperty("password");
      expect(await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage))).not.toContain(secret);
      expect(browserErrors).toEqual([]);
    });
  }
}
