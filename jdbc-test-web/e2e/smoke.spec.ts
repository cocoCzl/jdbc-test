import { expect, test } from "@playwright/test";
import {
  catalog,
  connection,
  driver,
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
    await expect(page.getByText("Not run 16", {exact: true})).toBeVisible();
    await expect(page.getByText("Incomplete 1", {exact: true})).toBeVisible();
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
    await page.getByRole("button", {name: "View / copy URL"}).click();
    await expect(page.getByRole("dialog")).toContainText("SERVICE_NAME=fixture_service");
    await expect(page.getByRole("dialog")).not.toContainText("fixture-only");
    await noOverflow();
    await page.keyboard.press("Escape");
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
  await page.getByLabel("Version", { exact: true }).fill("42.7.10");
  await page
    .getByLabel("JAR files")
    .setInputFiles({
      name: "postgresql.jar",
      mimeType: "application/java-archive",
      buffer: Buffer.from("mocked artifact"),
    });
  await page.getByRole("button", { name: "Upload driver package" }).click();
  await page.getByRole("button", { name: "Validate driver" }).click();
  await expect(page.getByText("Validated in an isolated Worker")).toBeVisible();
  await page.goto("/connections/new");
  await page.getByLabel("Name", { exact: true }).fill("Test database");
  await page.getByLabel("Database type", { exact: true }).fill("VendorDB");
  await page.getByLabel("Adapter", { exact: true }).selectOption("postgresql");
  await page
    .getByLabel("Driver package", { exact: true })
    .selectOption(driver.id);
  await page.getByLabel("JDBC URL", { exact: true }).fill(connection.jdbcUrl);
  await page
    .getByRole("button", { name: "Test connection", exact: true })
    .click();
  await expect(page.getByText("Connected", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Save connection" }).click();
  await page.getByRole("link", { name: "Start run", exact: true }).click();
  await page.getByRole("button", { name: /^Next:/ }).click();
  await page.getByRole("button", { name: /^Next:/ }).click();
  await page.getByRole("button", { name: /Custom Choose/ }).click();
  await page.getByRole("checkbox", { name: "Statement 23 scenarios" }).check();
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
  await page.getByRole("button", { name: "View raw JSON" }).click();
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
  for (const width of [1280, 1024, 768]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");
    await expect(page.getByText("No test runs yet")).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    if (width < 1024) {
      await page.getByRole("button", { name: "Open navigation" }).click();
      await expect(
        page.getByRole("link", { name: "Drivers", exact: true }),
      ).toBeInViewport();
      await page
        .getByRole("button", { name: "Close navigation", exact: true })
        .click();
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
