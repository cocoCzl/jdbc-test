import { test, expect } from "@playwright/test";
import { adapters, catalog, connection, driver, finishedRun, page as paged } from "../src/test/fixtures";

test("TEST FIXTURE ONLY: exact scope survives refresh, search and narrow bilingual review", async ({ page }) => {
  const requests: Record<string, unknown>[] = [];
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/runs" && route.request().method() === "POST") {
      requests.push(route.request().postDataJSON());
      return route.fulfill({ json: finishedRun, status: 201 });
    }
    const body = url.pathname === "/api/test-catalog" ? catalog
      : url.pathname === "/api/adapters" ? adapters
      : url.pathname === "/api/drivers/driver-1" ? driver
      : url.pathname === "/api/drivers" ? paged([driver])
      : url.pathname === "/api/connections/connection-1" ? connection
      : url.pathname === "/api/connections" ? paged([connection])
      : url.pathname === "/api/runs/run-1" ? finishedRun : paged([]);
    return route.fulfill({ json: body });
  });
  await page.goto("/runs/new?connectionId=connection-1");
  await page.getByRole("button", { name: "Next: Choose driver" }).click();
  await page.getByRole("button", { name: "Next: Select tests" }).click();
  await expect(page.getByText("40 scenarios selected")).toBeVisible();
  await expect(page.locator("details[open]")).toHaveCount(0);
  await page.getByRole("checkbox", { name: "Connection", exact: true }).uncheck();
  await expect(page.getByText("23 scenarios selected")).toBeVisible();
  await page.getByText("Show / hide cases", { exact: true }).last().click();
  await page.getByRole("checkbox", { name: /\(statement\.case_0\)$/ }).uncheck();
  await expect(page.getByRole("checkbox", { name: "Statement", exact: true })).toHaveAttribute("aria-checked", "mixed");
  await page.getByRole("searchbox").fill("statement.case_1");
  await page.getByRole("searchbox").clear();
  await expect(page.getByRole("checkbox", { name: /\(statement\.case_0\)$/ })).not.toBeChecked();
  await page.getByRole("button", { name: "Clear all tests" }).click();
  await expect(page.getByRole("button", { name: "Next: Review run" })).toBeDisabled();
  await page.getByRole("checkbox", { name: /\(statement\.case_1\)$/ }).check();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole("button", { name: "Language", exact: true }).click();
  await page.getByRole("menuitemradio", { name: "简体中文" }).click();
  await expect(page.getByRole("heading", { name: /标准 JDBC 测试/ })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath("scope-fixture-zh-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "下一步：核对运行" }).click();
  await expect(page.getByText("目录 40 · 已选 1 · 用户排除 39")).toBeVisible();
  await page.getByRole("checkbox", { name: /我了解/ }).check();
  await page.getByRole("button", { name: "开始测试运行" }).click();
  await expect.poll(() => requests.length).toBe(1);
  expect(requests[0].includeScenarios).toEqual(["statement.case_1"]);
  expect(requests[0].excludeScenarios).toHaveLength(39);
  expect(requests[0].profile).toBe("CUSTOM");
});
