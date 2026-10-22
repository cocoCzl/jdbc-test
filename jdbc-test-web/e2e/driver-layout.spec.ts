import { expect, test } from "@playwright/test";
import { adapters, driver, driverPresets, page as paged } from "../src/test/fixtures";
import { chooseOption } from "./select";

for (const language of ["en", "zh-CN"]) {
  test(`TEST FIXTURE ONLY: driver check summary and technical details in ${language}`, async ({ page }) => {
    const chinese = language === "zh-CN";
    const sha256 = "1250ce0c47cb68da8385ed6ee62ea79258a577473c463d29f5c8dbe045321037";
    const fileName = "kingbase8-9.0.0.jar";
    const fixture = {
      ...driver, name: "KingBase JDBC", vendor: "Kingbase", version: "9.0.0",
      driverClass: "com.kingbase8.Driver", urlPrefix: "jdbc:kingbase8://192.0.2.20:4321/existing_database",
      dataSources: {},
      artifacts: [{ ...driver.artifacts[0], fileName, sha256 }],
    };
    await page.addInitScript(locale => localStorage.setItem("jdbc-test.locale", locale), language);
    await page.route(url => url.pathname.startsWith("/api/"), async route => {
      const path = new URL(route.request().url()).pathname;
      if (path === "/api/adapters") return route.fulfill({ json: adapters });
      if (path === "/api/drivers") return route.fulfill({ json: paged([fixture]) });
      if (path === "/api/drivers/driver-1") return route.fulfill({ json: fixture });
      if (path === "/api/drivers/driver-1/validate") return route.fulfill({ json: {
        status: "VALIDATED", driverClass: fixture.driverClass,
        majorVersion: 0, minorVersion: 0, jdbcCompliant: false,
        loadedArtifact: { fileName, sha256, implementationVersion: "V009R002C016", bundleVersion: "0.0.0.V009R002C016" },
      } });
      throw new Error(`Unexpected fixture request ${path}`);
    });
    await page.setViewportSize({ width: 1366, height: 768 });
    await page.goto("/drivers/driver-1");
    const advanced = page.getByRole("group", { name: chinese ? "高级接口配置" : "Advanced interface configuration" });
    await expect(advanced).not.toHaveAttribute("open");
    await expect(advanced.getByText("DataSource", { exact: true })).not.toBeVisible();
    await advanced.locator("summary").click();
    await expect(advanced.getByText(chinese ? "未配置" : "Not configured", { exact: true })).toHaveCount(3);
    await advanced.locator("summary").click();
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.getByRole("button", { name: chinese ? "检查驱动文件" : "Check driver files" }).click();
    const result = page.getByRole("region", { name: chinese ? "加载检查通过" : "Load check passed" });
    await expect(result.locator("details")).not.toHaveAttribute("open");
    await expect(result).toBeInViewport({ ratio: 1 });
    const fileVersion = result.getByText(chinese ? "文件声明版本" : "Version declared in file", { exact: true }).locator("+ dd");
    await expect(fileVersion).toHaveText("V009R002C016");
    await expect(fileVersion).toBeVisible();
    await expect(result.getByText(chinese ? "本次未测试" : "Not tested", { exact: true })).toHaveCount(2);
    await expect(result.getByText("0.0", { exact: true })).not.toBeVisible();
    await expect(result.getByText("false", { exact: true })).not.toBeVisible();
    await expect(page.locator("[data-sonner-toast]")).toHaveCount(0);
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: test.info().outputPath(`fixture-${language}-desktop.png`), fullPage: true, animations: "disabled" });
    const details = result.locator("summary");
    await details.focus();
    await page.keyboard.press("Enter");
    await expect(result.getByText("0.0", { exact: true })).toBeVisible();
    await expect(result.getByText("false", { exact: true })).toBeVisible();
    await expect(result.getByText(sha256, { exact: true })).toBeVisible();
    await page.screenshot({ path: test.info().outputPath(`fixture-${language}-desktop-details.png`), fullPage: true, animations: "disabled" });
    await page.keyboard.press("Space");
    await expect(result.getByText("0.0", { exact: true })).not.toBeVisible();
    for (const width of [375, 320]) {
      await page.setViewportSize({ width, height: 812 });
      await page.evaluate(() => window.scrollTo(0, 0));
      const title = page.getByRole("heading", { name: fixture.name, exact: true });
      expect(await title.evaluate(element => element.getBoundingClientRect().height <= parseFloat(getComputedStyle(element).lineHeight) * 2)).toBe(true);
      await expect(result.getByRole("heading", { name: chinese ? "加载检查通过" : "Load check passed", exact: true })).toBeInViewport({ ratio: 1 });
      await expect(fileVersion).toBeVisible();
      await expect(result.getByRole("link", { name: chinese ? "配置数据库连接" : "Configure connection" })).toBeVisible();
      await page.screenshot({ path: test.info().outputPath(`fixture-${language}-mobile-${width}.png`), fullPage: true, animations: "disabled" });
      await details.click();
      await expect(result.getByText("0.0", { exact: true })).toBeVisible();
      await expect(result.getByText("false", { exact: true })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({ path: test.info().outputPath(`fixture-${language}-mobile-${width}-details.png`), fullPage: true, animations: "disabled" });
      await details.click();
      await expect(result.getByText("0.0", { exact: true })).not.toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    }
    await result.getByRole("link", { name: chinese ? "配置数据库连接" : "Configure connection" }).click();
    await expect(page).toHaveURL(/\/connections\/new$/);
    await chooseOption(page, page.getByLabel(chinese ? "驱动包" : "Driver package", { exact: true }), fixture.id);
    await expect(page.getByLabel("JDBC URL", { exact: true })).toHaveValue("");
    await page.getByLabel("JDBC URL", { exact: true }).fill("jdbc:kingbase8://192.0.2.21:4321/connection_database");
    await expect(page.getByLabel("JDBC URL", { exact: true })).toHaveValue("jdbc:kingbase8://192.0.2.21:4321/connection_database");
  });

  test(`TEST FIXTURE ONLY: driver advanced configuration and dependency notes in ${language}`, async ({ page }) => {
    const chinese = language === "zh-CN";
    const fixture = {
      ...driver, name: "Oracle JDBC", driverClass: "oracle.jdbc.OracleDriver",
      urlPrefix: "jdbc:oracle:thin:@//192.0.2.20:1521/existing_database",
      dataSources: {
        dataSource: { className: "oracle.jdbc.pool.OracleDataSource", urlProperty: "URL", userProperty: "user", passwordProperty: "password" },
        connectionPoolDataSource: { className: "oracle.jdbc.pool.OracleConnectionPoolDataSource" },
        xaDataSource: { className: "oracle.jdbc.xa.client.OracleXADataSource" },
      },
    };
    const updates: unknown[] = [];
    await page.addInitScript(locale => localStorage.setItem("jdbc-test.locale", locale), language);
    await page.route(url => url.pathname.startsWith("/api/"), async route => {
      const path = new URL(route.request().url()).pathname;
      if (path === "/api/drivers/driver-1") {
        if (route.request().method() === "PATCH") {
          const input = route.request().postDataJSON();
          updates.push(input);
          Object.assign(fixture, input);
        }
        return route.fulfill({ json: fixture });
      }
      if (path === "/api/drivers/driver-1/validate") return route.fulfill({ json: {
        status: "VALIDATED", driverClass: fixture.driverClass, majorVersion: 21, minorVersion: 13,
        jdbcCompliant: true, warnings: ["ORACLE_I18N_MISSING", "ORACLE_XML_MISSING"],
      } });
      throw new Error(`Unexpected fixture request ${path}`);
    });
    await page.goto("/drivers/driver-1");
    await page.getByRole("button", { name: chinese ? "检查驱动文件" : "Check driver files" }).click();
    const result = page.getByRole("region", { name: chinese ? "加载检查通过" : "Load check passed" });
    const warning = result.getByRole("alert");
    await expect(result.locator("details")).not.toHaveAttribute("open");
    await expect(warning).toBeVisible();
    await expect(warning).toContainText("orai18n.jar");
    await expect(warning).toContainText("xdb.jar");
    await expect(warning).toContainText("xmlparserv2.jar");
    const advanced = page.getByRole("group", { name: chinese ? "高级接口配置" : "Advanced interface configuration" });
    await expect(advanced).not.toHaveAttribute("open");
    await expect(advanced.getByText(fixture.dataSources.dataSource.className, { exact: true })).not.toBeVisible();
    await expect(page.locator("[data-sonner-toast]")).toHaveCount(0);
    for (const width of [1366, 375, 320]) {
      await page.setViewportSize({ width, height: width === 1366 ? 768 : 812 });
      await advanced.locator("summary").click();
      for (const provider of Object.values(fixture.dataSources)) {
        await expect(advanced.getByText(provider.className, { exact: true })).toBeVisible();
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({ path: test.info().outputPath(`fixture-${language}-advanced-${width}.png`), fullPage: true, animations: "disabled" });
      await advanced.locator("summary").click();
    }
    await page.getByRole("button", { name: chinese ? "编辑信息" : "Edit information" }).click();
    const editor = page.locator("form");
    await expect(editor.getByLabel(chinese ? "JDBC URL 前缀" : "JDBC URL prefix", { exact: true })).toHaveValue(fixture.urlPrefix);
    await chooseOption(page, editor.locator("#sql-dialect"), "oracle");
    const editorAdvanced = editor.locator("details");
    await expect(editorAdvanced).not.toHaveAttribute("open");
    await editorAdvanced.locator("summary").click();
    const urlProperty = editor.getByLabel(chinese ? "URL 属性名" : "URL property name", { exact: true }).first();
    await expect(urlProperty).toHaveValue("URL");
    await urlProperty.fill("jdbcUrl");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: test.info().outputPath(`fixture-${language}-editor-320.png`), fullPage: true, animations: "disabled" });
    await editor.getByRole("button", { name: chinese ? "保存信息" : "Save information" }).click();
    await expect(editor).toHaveCount(0);
    expect(updates).toEqual([{
      name: fixture.name, vendor: fixture.vendor, version: fixture.version,
      driverClass: fixture.driverClass, urlPrefix: fixture.urlPrefix,
      dataSources: fixture.dataSources, defaultSqlDialect: "oracle",
    }]);
    expect(fixture.dataSources.dataSource.urlProperty).toBe("jdbcUrl");
  });
}

test("TEST FIXTURE ONLY: first-screen file picker, file operations and unchanged upload", async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 });
  const uploads: string[] = [];
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/drivers/presets") return route.fulfill({ json: driverPresets });
    if (path === "/api/drivers" && route.request().method() === "POST") {
      uploads.push(route.request().postData() ?? "");
      return route.fulfill({ status: 201, json: driver });
    }
    if (path === "/api/drivers/driver-1") return route.fulfill({ json: driver });
    throw new Error(`Unexpected fixture request ${path}`);
  });
  await page.goto("/drivers/new");
  const choose = page.getByRole("button", { name: "Choose JAR files", exact: true });
  await expect(choose).toBeInViewport({ ratio: 1 });
  expect(await page.evaluate(() => window.scrollY)).toBe(0);
  await expect(page.getByText("Please choose JAR files first")).toBeVisible();
  const filesSection = page.getByRole("region", { name: "Driver artifacts", exact: true });
  expect(await filesSection.evaluate(element => element.parentElement?.firstElementChild === element)).toBe(true);
  await expect(page.locator("details[open]")).toHaveCount(0);
  const chooser = page.waitForEvent("filechooser");
  await choose.click();
  await (await chooser).setFiles([
    { name: "driver.jar", mimeType: "application/java-archive", buffer: Buffer.from("driver fixture") },
    { name: "dependency.jar", mimeType: "application/java-archive", buffer: Buffer.from("dependency fixture") },
  ]);
  await expect(page.getByText("2 artifacts", { exact: true })).toBeVisible();
  await page.getByLabel("Name", { exact: true }).fill("Layout fixture");
  await page.getByLabel("Registered version", { exact: true }).fill("1.2.3");
  await chooseOption(page, page.getByLabel("Driver preset", { exact: true }), "postgresql");
  await chooseOption(page, page.getByLabel("Driver preset", { exact: true }), "mysql");
  await expect(page.getByText("driver.jar", { exact: true })).toBeVisible();
  await expect(page.getByLabel("Name", { exact: true })).toHaveValue("Layout fixture");
  const drop = async (name: string) => {
    const dataTransfer = await page.evaluateHandle(filename => {
      const transfer = new DataTransfer();
      transfer.items.add(new File(["fixture"], filename));
      return transfer;
    }, name);
    await page.getByRole("group", { name: "Driver artifacts", exact: true }).dispatchEvent("drop", { dataTransfer });
    await dataTransfer.dispose();
  };
  await drop("bad.txt");
  await page.getByRole("button", { name: "Confirm upload", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Only .jar");
  expect(uploads).toHaveLength(0);
  await page.getByRole("button", { name: "Remove bad.txt", exact: true }).click();
  await drop("dropped.jar");
  await expect(page.getByText("3 artifacts", { exact: true })).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  const summaryBox = await page.locator("form > aside").boundingBox();
  const navigationBox = await page.getByRole("navigation").boundingBox();
  expect(summaryBox!.y).toBeGreaterThanOrEqual(navigationBox!.y + navigationBox!.height);
  expect(summaryBox!.y + summaryBox!.height).toBeLessThanOrEqual(768);
  await page.getByRole("button", { name: "Remove dependency.jar", exact: true }).click();
  await page.getByRole("button", { name: "Confirm upload", exact: true }).click();
  await expect(page).toHaveURL(/\/drivers\/driver-1$/);
  expect(uploads).toHaveLength(1);
  expect(uploads[0]).toContain('filename="driver.jar"');
  expect(uploads[0]).toContain('filename="dropped.jar"');
  expect(uploads[0]).not.toContain('filename="dependency.jar"');
  expect(uploads[0]).toContain('"version":"1.2.3"');
});

test("TEST FIXTURE ONLY: Chinese desktop first viewport and narrow-screen order", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("jdbc-test.locale", "zh-CN"));
  await page.route("**/api/drivers/presets", route => route.fulfill({ json: driverPresets }));
  await page.setViewportSize({ width: 1366, height: 768 });
  await page.goto("/drivers/new");
  const choose = page.getByRole("button", { name: "选择 JAR 文件", exact: true });
  await expect(choose).toBeInViewport({ ratio: 1 });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: test.info().outputPath("fixture-add-driver-1366x768.png"), animations: "disabled" });
  const input = page.getByLabel("JAR 文件", { exact: true });
  await input.setInputFiles({ name: "kept.jar", mimeType: "application/java-archive", buffer: Buffer.from("fixture") });
  await page.getByLabel("名称", { exact: true }).fill("布局验证");
  for (const width of [375, 320]) {
    await page.setViewportSize({ width, height: 812 });
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(choose).toBeInViewport({ ratio: 1 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    const tops = await page.locator('form > div > section, form > div > details, form > aside').evaluateAll(elements => elements.map(element => element.getBoundingClientRect().top));
    expect(tops).toHaveLength(4);
    expect(tops).toEqual([...tops].sort((a, b) => a - b));
    await expect(page.getByText("kept.jar", { exact: true })).toBeVisible();
    await expect(page.getByLabel("名称", { exact: true })).toHaveValue("布局验证");
  }
  await page.screenshot({ path: test.info().outputPath("fixture-add-driver-mobile.png"), fullPage: true });
});
