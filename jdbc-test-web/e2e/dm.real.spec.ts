import { createHash } from "node:crypto";
import { expect, test } from "@playwright/test";

// Use IDs created by the authorized disposable acceptance platform; never infer a target.
test.skip(process.env.JDBC_TEST_DM_REAL !== "true", "Requires explicit DM acceptance configuration");

test("REAL DM: exact saved scope, probe, bilingual history and report", async ({ page, request }) => {
  const connectionId = process.env.JDBC_TEST_DM_CONNECTION_ID;
  const runId = process.env.JDBC_TEST_DM_RUN_ID;
  const schema = process.env.DM_SCHEMA;
  if (!connectionId || !runId || !schema) throw new Error("Explicit DM connection, run and schema are required");
  const reportPath = `/api/runs/${runId}/report.json`;
  const before = await request.get(reportPath);
  expect(before.status()).toBe(200);
  const hash = (text: string) => createHash("sha256").update(text).digest("hex");
  const beforeHash = hash(await before.text());
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  for (const locale of ["en", "zh-CN"]) {
    const zh = locale === "zh-CN";
    await page.addInitScript(locale => localStorage.setItem("jdbc-test.locale", locale), locale);
    await page.goto(`/connections/${connectionId}`);
    await expect(page.locator("#database-type")).toHaveText(/达梦.*Dameng/);
    await expect(page.locator("#execution-schema")).toHaveValue(schema);
    await expect(page.locator("#execution-database")).toHaveCount(0);
    const qualification = page.getByText(zh ? /^项目正式维护的适配。/ : /^Project-maintained adapter\./);
    await expect(qualification).toBeVisible();
    await expect(qualification).toContainText("8.1.3.162");
    await expect(qualification).toContainText("8.1.3.62");
    const probed = page.waitForResponse(response => response.url().endsWith("/api/connections/test"));
    await page.getByRole("button", { name: zh ? "测试连接" : "Test connection", exact: true }).click();
    const response = await probed;
    expect(response.status()).toBe(200);
    expect((await response.json()).databaseProductName).toBe("DM DBMS");
    const draft = response.request().postDataJSON();
    expect(draft.savedConnectionId).toBe(connectionId);
    expect(draft).not.toHaveProperty("password");
    expect(draft.jdbcUrl).not.toMatch(/password=/i);
    const saved = page.waitForResponse(response => response.url().endsWith(`/api/connections/${connectionId}`)
      && response.request().method() === "PATCH");
    await page.getByRole("button", { name: zh ? "保存连接" : "Save connection", exact: true }).click();
    expect((await saved).status()).toBe(200);
    await page.reload();
    await expect(page.locator("#execution-schema")).toHaveValue(schema);
    await page.screenshot({ path: test.info().outputPath(`REAL-DM-connection-${locale}.png`), fullPage: true });
    await page.goto(`/runs/${runId}`);
    await expect(page.getByRole("heading", { level: 1 })).toContainText("DM");
    await page.getByRole("tab", { name: zh ? "测试用例" : "Test cases", exact: true }).click();
    await expect(page.locator("table tbody tr").first()).toBeVisible();
    await page.getByRole("tab", { name: zh ? "报告" : "Report", exact: true }).click();
    await page.getByRole("button", { name: zh ? "查看报告 JSON" : "View report JSON", exact: true }).click();
    await expect(page.getByRole("dialog")).toContainText('"cleanupEvidence"');
    await page.getByRole("button", { name: zh ? "关闭弹窗" : "Close dialog", exact: true }).click();
    await page.screenshot({ path: test.info().outputPath(`REAL-DM-report-${locale}.png`), fullPage: true });
  }
  const after = await request.get(reportPath);
  expect(hash(await after.text())).toBe(beforeHash);
  expect(errors).toEqual([]);
});
