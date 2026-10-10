import { expect, test, type Page } from "@playwright/test";

const runId = "b04ab9cc-51a7-4931-81b0-c9e7a78791f6";

async function setLanguage(page: Page, locale: "en" | "zh-CN") {
  await page.getByRole("button", {name: /^(Language|界面语言)$/}).click();
  await page.getByRole("menuitemradio", {name: locale === "en" ? "English" : "简体中文"}).click();
  await expect(page.locator("html")).toHaveAttribute("lang", locale);
}

test("preserved viewer has no global badge and identifies report JSON", async ({page}) => {
  test.skip(process.env.JDBC_TEST_HISTORY_READONLY !== "true", "Requires the preserved GET-only history viewer");
  const writes: string[] = [];
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    if (route.request().method() !== "GET") {
      writes.push(route.request().url());
      await route.abort();
      return;
    }
    await route.continue();
  });
  await page.goto("/");
  await expect(page.locator('meta[name="jdbc-environment"]')).toHaveAttribute("content", "history-copy");
  const runs = await (await page.request.get("/api/runs?size=20")).json();
  expect(runs.items).toHaveLength(15);
  const run = await (await page.request.get(`/api/runs/${runId}`)).json();
  expect([run.totalCases, run.passed, run.failed, run.skipped]).toEqual([202, 189, 0, 13]);

  for (const locale of ["zh-CN", "en"] as const) {
    await setLanguage(page, locale);
    for (const path of ["/", "/drivers", "/connections", "/runs", "/runs/new", `/runs/${runId}`]) {
      await page.goto(path);
      await expect(page.locator("h1")).toBeVisible();
      await expect(page.locator(".jdbc-header")).not.toContainText(/历史数据副本|Historical data copy/);
      await expect(page.locator("details.history-environment")).toHaveCount(0);
      await expect(page.getByRole("button", {name: locale === "en" ? "Language" : "界面语言"})).toBeVisible();
    }
    await page.getByRole("link", {name: locale === "en" ? "Drivers" : "驱动管理", exact: true}).click();
    await expect(page).toHaveURL(/\/drivers$/);
    await page.getByRole("link", {name: locale === "en" ? "Run history" : "运行历史", exact: true}).click();
    await expect(page).toHaveURL(/\/runs$/);
    await page.goto(`/runs/${runId}`);
    await page.getByRole("tab", {name: locale === "en" ? "Report" : "报告", exact: true}).click();
    await page.getByRole("button", {name: locale === "en" ? "View report JSON" : "查看报告 JSON"}).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByRole("heading", {name: locale === "en" ? "Database evidence JSON" : "数据库证据 JSON"})).toBeVisible();
    await expect(dialog).toContainText(locale === "en"
      ? "Data source: run events saved in the database."
      : "数据来源：数据库中保存的运行事件。");
    await expect(dialog).toContainText(locale === "en"
      ? "The original report.json file is unavailable in this preserved history copy."
      : "此历史查看副本的原始 report.json 文件不可用。");
    await expect(dialog.getByRole("button", {name: locale === "en" ? "Download database evidence JSON" : "下载数据库证据 JSON"})).toBeVisible();
    const raw = JSON.parse(await dialog.locator("pre").textContent() ?? "null");
    expect(raw.source).toBe("PERSISTED_WORKER_EVENTS");
    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
  }
  expect(writes).toEqual([]);
  expect(errors).toEqual([]);
});
