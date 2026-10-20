import { chooseOption } from "./select";
import { expect, test } from "@playwright/test";
import {
  adapters,
  catalog,
  connection,
  driver,
  driverPresets,
  finishedRun,
  page as paged,
  probe,
} from "../src/test/fixtures";

test("TEST FIXTURE ONLY: long text, pagination, masked Oracle URL and execution states", async ({ page }) => {
  const name = `Fixture connection ${"LongName".repeat(24)}`;
  const oracle = "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST=oracle-fixture.invalid)(PORT=1521)))(CONNECT_DATA=(SERVER=DEDICATED)(SERVICE_NAME=fixture_service)))?password=fixture-only";
  const error = `Fixture startup interruption: ${"LongDiagnostic".repeat(60)}`;
  const accounting = {selected: 23, started: 7, completed: 6, passed: 2, failed: 1, skipped: 2, aborted: 1, pending: 0, running: 0, notRun: 16, incomplete: 1, scenarios: []};
  const interrupted = {...finishedRun, status: "INTERRUPTED", connection: {...finishedRun.connection, name}, completedCases: 6, passed: 2, failed: 1, skipped: 2, accounting, failureReason: error};
  const requests: string[] = [];
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    const url = new URL(route.request().url()); requests.push(url.pathname);
    expect(route.request().method()).toBe("GET");
    let body: unknown;
    if (url.pathname === "/api/test-catalog") body = catalog;
    else if (url.pathname === "/api/adapters") body = adapters;
    else if (url.pathname === "/api/runs") body = paged([interrupted], {page: Number(url.searchParams.get("page")), totalPages: 2, totalElements: 21});
    else if (url.pathname.endsWith("/events")) return route.fulfill({contentType: "text/event-stream", body: ""});
    else if (url.pathname === "/api/runs/active") body = {...interrupted, id: "active", status: "RUNNING", accounting: {...accounting, pending: 16, running: 1, notRun: 0, incomplete: 0}};
    else if (url.pathname === "/api/runs/legacy") body = {...finishedRun, id: "legacy"};
    else if (url.pathname === "/api/runs/run-1") body = interrupted;
    else if (url.pathname === "/api/connections") body = paged([{...connection, name, databaseType: "Oracle", adapterId: "oracle", jdbcUrl: oracle}], {page: Number(url.searchParams.get("page")), totalPages: 2, totalElements: 13});
    else if (url.pathname === "/api/drivers/driver-1") body = driver;
    else if (url.pathname === "/api/drivers") body = paged([driver]);
    else throw new Error(`Unexpected fixture request ${url.pathname}`);
    await route.fulfill({json: body});
  });
  const noOverflow = async () => expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  for (const width of [1440, 1280, 768]) {
    await page.setViewportSize({width, height: 900});
    await page.goto("/");
    await expect(page.getByRole("table").getByRole("link")).toHaveCount(1);
    await expect(page.locator(".run-result-metric").filter({has:page.getByText("Not run", {exact:true})}).locator(".run-result-value")).toHaveText("16");
    await expect(page.locator(".run-result-metric").filter({has:page.getByText("Incomplete", {exact:true})}).locator(".run-result-value")).toHaveText("1");
    await noOverflow();
    await page.goto("/runs");
    await page.getByRole("button", {name: "Next page"}).click();
    await expect(page.getByText("21 records · Page 2 of 2")).toBeVisible();
    expect(requests.filter(p => /^\/api\/runs\//.test(p))).toHaveLength(0);
    await noOverflow();
    await page.getByRole("table").getByRole("link").click();
    await expect(page.getByRole("alert")).toHaveText(error);
    await expect(page.locator('[data-stat="Not run"]')).toContainText("16");
    await expect(page.locator('[data-stat="Incomplete"]')).toContainText("1");
    await noOverflow();
    await page.screenshot({path: `test-results/fixture-interrupted-${width}.png`, fullPage: true});
    await page.goto("/runs/new");
    await page.getByRole("button", {name: "View URL"}).click();
    await expect(page.getByRole("dialog")).toContainText("SERVICE_NAME=fixture_service");
    await expect(page.getByRole("dialog")).not.toContainText("fixture-only");
    await noOverflow();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("button", {name:"View URL"})).toBeFocused();
    const radio = page.getByRole("radio", {name});
    await expect(radio).not.toBeChecked();
    await radio.focus(); await page.keyboard.press("Space");
    await expect(radio).toBeChecked();
    await expect(radio).toBeFocused();
    expect(await radio.evaluate(el => getComputedStyle(el).boxShadow)).not.toBe("none");
    await page.getByRole("button", {name: "Next page"}).click();
    await expect(page.getByText("13 records · Page 2 of 2")).toBeVisible();
    await noOverflow();
    await page.screenshot({path: `test-results/fixture-wizard-${width}.png`, fullPage: true});
    requests.length = 0;
  }
  await page.goto("/runs/active");
  await expect(page.locator('[data-stat="Pending"]')).toContainText("16");
  await expect(page.locator('[data-stat="Running"]')).toContainText("1");
  await expect(page.locator('[data-stat="Not run"]')).toHaveCount(0);
  await expect(page.getByRole("progressbar")).toHaveAttribute("value", "6");
  await page.goto("/runs/legacy");
  await expect(page.locator('[data-stat="Not run"]')).toContainText("—");
  await expect(page.getByText(/Legacy run:/)).toBeVisible();
});

test("API-mocked browser flow: upload → connection → Custom Statement → report", async ({
  page,
}) => {
  await page.route(
    (url) => url.pathname.startsWith("/api/"),
    async (route) => {
      const path = new URL(route.request().url()).pathname;
      let body: unknown;
      const method = route.request().method();
      if (path === "/api/drivers")
        body = method === "POST" ? driver : paged([driver]);
      else if (path === "/api/drivers/presets") body = driverPresets;
      else if (path.endsWith("/validate"))
        body = {
          status: "VALIDATED",
          driverClass: driver.driverClass,
          majorVersion: 42,
          minorVersion: 7,
          jdbcCompliant: false,
        };
      else if (path === "/api/drivers/driver-1") body = driver;
      else if (path === "/api/adapters")
        body = [{ id: "postgresql", name: "PostgreSQL", status: "OFFICIAL" }];
      else if (path.endsWith("/test")) body = probe;
      else if (path === "/api/connections")
        body = method === "POST" ? connection : paged([connection]);
      else if (path === "/api/connections/connection-1") body = connection;
      else if (path === "/api/test-catalog") body = catalog;
      else if (path.endsWith("/report.json"))
        body = { summary: { passed: 23 } };
      else if (path.endsWith("/report"))
        body = { summary: finishedRun, failures: [] };
      else if (path.endsWith("/results")) body = paged([]);
      else if (path === "/api/runs")
        body = method === "POST" ? finishedRun : paged([finishedRun]);
      else if (path === "/api/runs/run-1") body = finishedRun;
      else throw new Error(`Unexpected mocked request ${path}`);
      await route.fulfill({
        status:
          method === "POST" &&
          ["/api/drivers", "/api/connections", "/api/runs"].includes(path)
            ? 201
            : 200,
        json: body,
      });
    },
  );
  await page.goto("/drivers/new");
  await page.getByLabel("Name", { exact: true }).fill("PostgreSQL JDBC");
  await page.getByLabel("Vendor", { exact: true }).fill("PostgreSQL");
  await page.getByLabel("Registered version", { exact: true }).fill("42.7.10");
  await page
    .getByLabel("JAR files")
    .setInputFiles({
      name: "postgresql.jar",
      mimeType: "application/java-archive",
      buffer: Buffer.from("mocked artifact"),
    });
  await page.getByRole("button", { name: "Upload driver package" }).click();
  await page.getByRole("button", { name: "Validate driver" }).click();
  await expect(page.getByText("Driver loaded and metadata read in an isolated Worker")).toBeVisible();
  await page.goto("/connections/new");
  await page.getByLabel("Name", { exact: true }).fill("Test database");
  await chooseOption(page, page.getByLabel("Database type", { exact: true }), "postgresql");
  await chooseOption(page, page.getByLabel("Driver package", { exact: true }), driver.id);
  await page.getByLabel("JDBC URL", { exact: true }).fill(connection.jdbcUrl);
  await page
    .getByRole("button", { name: "Test connection", exact: true })
    .click();
  await expect(page.getByText("Connected", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Save connection" }).click();
  await page.getByRole("link", { name: "Start run", exact: true }).click();
  await page.getByRole("button", { name: /^Next:/ }).click();
  await page.getByRole("button", { name: /^Next:/ }).click();
  await page.getByRole("checkbox", { name: "Connection", exact: true }).uncheck();
  await expect(page.getByText("23 scenarios selected")).toBeVisible();
  await page.getByRole("button", { name: /^Next:/ }).click();
  await expect(
    page.getByRole("button", { name: "Start test run" }),
  ).toBeDisabled();
  await page.getByRole("checkbox", { name: /I understand/ }).check();
  await page.getByRole("button", { name: "Start test run" }).click();
  await expect(page.getByText("23 / 23", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "Report", exact: true }).click();
  await expect(page.getByText("Captured environment")).toBeVisible();
  await page.getByRole("button", { name: "View report JSON" }).click();
  await expect(page.getByRole("dialog")).toContainText('"passed": 23');
});

test("API-mocked empty Dashboard and responsive navigation at 1280 / 1024 / tablet", async ({
  page,
}) => {
  await page.route(
    (url) => url.pathname.startsWith("/api/"),
    (route) =>
      route.fulfill({
        json: route.request().url().includes("test-catalog")
          ? catalog
          : paged([]),
      }),
  );
  for (const width of [1280, 1024, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");
    await expect(page.getByText("No test runs yet")).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    if (width < 768) {
      await page.getByRole("button", { name: "Open navigation" }).click();
      await expect(
        page.getByRole("menuitem", { name: "Drivers", exact: true }),
      ).toBeInViewport();
      await page.keyboard.press("Escape");
      await expect(page.getByRole("button", {name:"Open navigation"})).toBeFocused();
    } else
      await expect(
        page.getByRole("link", { name: "Drivers", exact: true }),
      ).toBeInViewport();
    await page.screenshot({
      path: `test-results/dashboard-empty-${width}.png`,
      fullPage: true,
    });
  }
});

test("TEST FIXTURE ONLY: adapter declarations, filtered deep link and empty saved probe", async ({ page }) => {
  const declarations = ["gaussdb", "mysql", "oracle", "postgresql", "sqlserver", "private-db"].map(id => ({
    id, name: `Fixture ${id}`, status: "CANDIDATE", dialect: "postgresql",
    namespaceSelection: "schema", requiresDatabase: true, driverClass: "fixture.Driver",
    recommendedMavenCoordinate: null, validatedCombinations: [],
  }));
  const requests: URL[] = [];
  const probes: unknown[] = [];
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    const url = new URL(route.request().url()); requests.push(url);
    const path = url.pathname;
    if (path.endsWith("/test")) {
      probes.push(route.request().postDataJSON());
      return route.fulfill({json: probe});
    }
    expect(route.request().method()).toBe("GET");
    const body = path === "/api/adapters" ? declarations : path === "/api/test-catalog" ? catalog
      : path === "/api/drivers" ? paged([]) : path === "/api/drivers/driver-1" ? driver
      : path === "/api/connections/connection-1" ? connection
      : path === "/api/connections" ? (url.searchParams.get("adapterId") === "mysql" ? paged([])
        : paged([connection], {page: Number(url.searchParams.get("page")), totalElements: 25, totalPages: 3}))
      : path === "/api/runs" ? paged([]) : undefined;
    if (!body) throw new Error(`Unexpected adapter fixture request ${path}`);
    await route.fulfill({json: body});
  });
  await page.goto("/connections/new");

  for (const item of declarations) {
    await chooseOption(page, page.getByLabel("Database type", {exact: true}), item.id);
    await expect(page.getByLabel("Database type", {exact: true})).toHaveAttribute("data-value", item.id);
    await expect(page.getByText("No validated version combinations declared.")).toBeVisible();
  }
  await expect(page.getByRole("link", {name: "Upload a driver package"})).toBeVisible();
  expect(probes).toEqual([]);
  await page.goto("/connections/connection-1");
  await page.getByLabel("Use an empty password explicitly").check();
  await page.getByRole("button", {name: "Test saved connection"}).click();
  await expect(page.getByText("Connected", {exact:true})).toBeVisible();
  expect(probes).toEqual([{password: ""}]);
  await page.goto("/runs/new?connectionId=connection-1");
  await page.getByRole("button", {name: "Next page"}).click();
  await chooseOption(page, page.getByLabel("Filter connections by database adapter"), "mysql");
  await expect(page.getByText("No matching connections")).toBeVisible();
  await expect(page.getByRole("button", {name: "Next: Choose driver"})).toBeDisabled();
  expect(requests.some(url => url.pathname === "/api/connections" && url.searchParams.get("adapterId") === "mysql"
    && url.searchParams.get("page") === "0")).toBe(true);
  await page.getByRole("button", {name: /^Theme:/}).click();
  await expect(page.getByLabel("Filter connections by database adapter")).toHaveAttribute("data-value", "mysql");
  await page.setViewportSize({width: 390, height: 844});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({path: "test-results/fixture-adapter-filter-mobile.png", fullPage: true});
  await page.getByRole("button", {name: "Clear adapter filter"}).click();
  await expect(page.getByRole("radio", {name: connection.name})).toBeChecked();
});


test("TEST FIXTURE ONLY: bilingual driver presets preserve edits and render at desktop/mobile widths", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.route("**/api/drivers/presets", (route) => route.fulfill({ json: driverPresets }));
  for (const locale of ["en", "zh-CN"]) {
    await page.addInitScript((language) => localStorage.setItem("jdbc-test.locale", language), locale);
    await page.goto("/drivers/new");
    const chinese = locale === "zh-CN";
    const preset = page.getByLabel(chinese ? "驱动预设" : "Driver preset", { exact: true });
    const vendor = page.getByLabel(chinese ? "厂商" : "Vendor", { exact: true });
    const driverClass = page.locator("#driver-class");
    const prefix = page.locator("#driver-prefix");
    await chooseOption(page, preset, "postgresql");
    await expect(vendor).toHaveValue("PostgreSQL");
    await expect(driverClass).toHaveValue("org.postgresql.Driver");
    await expect(prefix).toHaveValue("jdbc:postgresql:");
    await driverClass.fill("");
    await prefix.fill("jdbc:private:");
    await chooseOption(page, preset, "mysql");
    await expect(vendor).toHaveValue("MySQL");
    await expect(driverClass).toHaveValue("");
    await expect(prefix).toHaveValue("jdbc:private:");
    await chooseOption(page, preset, "custom");
    await expect(vendor).toHaveValue("");
    await expect(prefix).toHaveValue("jdbc:private:");
    await chooseOption(page, preset, "oracle");
    await page.getByRole("button", { name: chinese ? "恢复当前预设默认值" : "Restore current preset defaults" }).click();
    await expect(driverClass).toHaveValue("oracle.jdbc.OracleDriver");
    await expect(prefix).toHaveValue("jdbc:oracle:");
    await chooseOption(page, preset, "kingbase");
    await expect(vendor).toHaveValue("Kingbase");
    await expect(driverClass).toHaveValue("com.kingbase8.Driver");
    await expect(prefix).toHaveValue("jdbc:kingbase8:");
    await expect(page.locator("#driver-name")).toHaveValue("");
    await expect(page.locator("#driver-version")).toHaveValue("");
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.evaluate(() => window.scrollTo(0, 0));
      await expect(page.getByRole("heading", { name: chinese ? "添加驱动" : "Add a driver", exact: true })).toBeVisible();
      await expect(preset).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.screenshot({ path: `test-results/fixture-kingbase-presets-${locale}-${width}.png`, fullPage: true });
    }
  }
  expect(errors).toEqual([]);
});

test("TEST FIXTURE ONLY: Kingbase official explains qualification in both languages", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  const kingbase = {
    id: "kingbase", name: "KingbaseES", status: "OFFICIAL", dialect: "postgresql",
    namespaceSelection: "schema", requiresDatabase: true, driverClass: "com.kingbase8.Driver",
    validatedCombinations: [{ databaseVersion: "12.1", driverVersion: "V008R006B0001" }],
    qualificationNoteKey: "adapter.kingbaseQualification",
  };
  const kingbaseDriver = {
    ...driver, name: "Kingbase fixture driver", vendor: "Kingbase", version: "V008R006B0001",
    driverClass: "com.kingbase8.Driver", urlPrefix: "jdbc:kingbase8:",
  };
  await page.route("**/api/adapters", (route) => route.fulfill({ json: [...adapters, kingbase] }));
  await page.route(/\/api\/drivers(?:\?.*)?$/, (route) => route.fulfill({ json: paged([kingbaseDriver]) }));
  for (const locale of ["en", "zh-CN"]) {
    await page.addInitScript((language) => localStorage.setItem("jdbc-test.locale", language), locale);
    await page.goto("/connections/new");
    const chinese = locale === "zh-CN";
    await chooseOption(page, page.locator("#database-type"), "kingbase");
    await expect(page.locator("#database-type")).toHaveAttribute("data-value", "kingbase");
    await expect(page.getByText(chinese ? /正式平台适配仅限已验证组合/ : /Official platform integration for the validated combination only/)).toBeVisible();
    await expect(page.locator("#database-type")).toContainText(chinese ? "KingbaseES · 官方" : "KingbaseES · Official");
    await expect(page.getByText(chinese ? "数据库 12.1 · 驱动 V008R006B0001" : "Database 12.1 · Driver V008R006B0001")).toBeVisible();
    await chooseOption(page, page.locator("#default-driver"), kingbaseDriver.id);
    await page.locator("#connection-name").fill("TEST FIXTURE — Kingbase official");
    await page.locator("#jdbc-url").fill("jdbc:kingbase8://fixture.invalid:4321/test");
    await page.locator("#execution-database").fill("test");
    await page.locator("#execution-schema").fill("dedicated_fixture_schema");
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.evaluate(() => window.scrollTo(0, 0));
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.screenshot({ path: test.info().outputPath(`fixture-kingbase-official-${locale}-${width}.png`) });
    }
  }
});
