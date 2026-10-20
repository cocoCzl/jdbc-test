import { expect, test } from "@playwright/test";
import { adapters, connection, driver, page as paged } from "../src/test/fixtures";
import { chooseOption } from "./select";

test("TEST FIXTURE ONLY: source-maintained JDBC scope and explicit compatibility dialect", async ({ page }) => {
  const requests: string[] = [];
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    const path = new URL(route.request().url()).pathname;
    requests.push(`${route.request().method()} ${path}`);
    expect(route.request().method()).toBe("GET");
    const body = path === "/api/adapters" ? adapters : path === "/api/drivers" ? paged([driver])
      : path === "/api/connections" ? paged([connection]) : path === "/api/runs" ? paged([]) : undefined;
    if (!body) throw new Error(`Unexpected request ${path}`);
    await route.fulfill({json: body});
  });
  for (const path of ["/adapters", "/sql-packages"]) {
    await page.goto(path);
    await expect(page.getByText("Page not found", {exact: true})).toBeVisible();
    await expect(page.getByRole("link", {name: "Database adapters", exact: true})).toHaveCount(0);
    await expect(page.getByRole("link", {name: "SQL test packages", exact: true})).toHaveCount(0);
  }
  await page.goto("/connections/new");
  await chooseOption(page, page.getByLabel("Database type", {exact: true}), "postgresql");
  await expect(page.getByLabel("Built-in compatibility dialect")).toHaveCount(0);
  await chooseOption(page, page.getByLabel("Database type", {exact: true}), "compatible");
  await chooseOption(page, page.getByLabel("Built-in compatibility dialect"), "postgresql");
  await expect(page.getByText(/Product identity is observed independently/)).toBeVisible();
  await page.screenshot({path: test.info().outputPath("fixture-source-maintained-connection.png"), fullPage: true});
  expect(requests.some(item => item.includes("sql-packages") || !item.startsWith("GET "))).toBe(false);
});
