import { expect, test } from "@playwright/test";
import {
  catalog,
  connection,
  driver,
  finishedRun,
  page as paged,
  probe,
} from "../src/test/fixtures";

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
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: /Custom Choose/ }).click();
  await page.getByRole("checkbox", { name: "Statement 23 scenarios" }).check();
  await expect(page.getByText("23 scenarios selected")).toBeVisible();
  await page.getByRole("button", { name: "Continue" }).click();
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
