import { expect, test } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";
import { basename, resolve } from "node:path";
import { chooseOption } from "./select";

const driverJar = process.env.JDBC_TEST_DRIVER_DIALECT_JAR;

// An explicitly provisioned, empty disposable platform is required; never use the normal service.
test.skip(process.env.JDBC_TEST_DRIVER_DIALECT_REAL !== "true" || !driverJar
  || process.env.JDBC_TEST_E2E_BASE_URL !== "http://127.0.0.1:8081",
"Requires the disposable platform on 8081");

let createdDriverId: string | undefined;
test.afterEach(async ({ request }) => {
  if (createdDriverId) {
    const response = await request.delete(`/api/drivers/${createdDriverId}`);
    expect(response.ok()).toBe(true);
    createdDriverId = undefined;
  }
});

test("real frontend and PostgreSQL: explicit driver dialect survives upload, refresh and edits", async ({ page, request }) => {
  const output = test.info().outputPath("driver-dialect");
  mkdirSync(`${output}/screenshots`, { recursive: true });
  expect((await (await request.get("/api/drivers?size=100")).json()).totalElements).toBe(0);
  await page.addInitScript(() => {
    if (!localStorage.getItem("jdbc-test.locale")) localStorage.setItem("jdbc-test.locale", "zh-CN");
  });
  await page.addInitScript(() => {
    const send = window.fetch;
    window.fetch = async (input, init) => {
      const metadata = init?.body instanceof FormData ? init.body.get("metadata") : null;
      if (metadata instanceof Blob) {
        (window as Window & { uploadedMetadata?: string }).uploadedMetadata = await metadata.text();
      }
      return send(input, init);
    };
  });
  await page.goto("/drivers/new");
  await chooseOption(page, page.locator("#sql-dialect"), "postgresql");
  await chooseOption(page, page.locator("#driver-preset"), "postgresql");
  await chooseOption(page, page.locator("#driver-preset"), "kingbase");
  await page.getByRole("button", { name: "恢复当前预设默认值" }).click();
  await page.locator("#driver-name").fill("Kingbase · 持久化验证");
  await page.locator("#driver-version").fill("9.0.0");
  await page.getByLabel("JAR 文件", { exact: true }).setInputFiles(resolve(driverJar!));
  await expect(page.locator("#sql-dialect")).toHaveText("PostgreSQL");
  const created = page.waitForResponse(response => response.url().endsWith("/api/drivers") && response.request().method() === "POST");
  await page.getByRole("button", { name: "确认上传" }).click();
  const response = await created;
  expect(response.status()).toBe(201);
  const uploaded = await response.json();
  createdDriverId = uploaded.id;
  const metadata = await page.evaluate(() => (window as Window & { uploadedMetadata?: string }).uploadedMetadata);
  expect(JSON.parse(metadata!).defaultSqlDialect).toBe("postgresql");
  writeFileSync(`${output}/real-upload-metadata.json`, metadata!);
  expect(uploaded.defaultSqlDialect).toBe("postgresql");
  expect(uploaded.status).toBe("PENDING_VALIDATION");
  writeFileSync(`${output}/real-upload.json`, JSON.stringify(uploaded, null, 2));
  const id = uploaded.id;
  await expect(page).toHaveURL(new RegExp(`/drivers/${id}$`));
  const dialect = page.getByText("默认 SQL 方言", { exact: true }).locator("+ dd");
  await expect(dialect).toHaveText("PostgreSQL");
  await page.reload();
  await expect(dialect).toHaveText("PostgreSQL");
  await page.goto("/drivers");
  await page.getByRole("link", { name: uploaded.name, exact: true }).click();
  await expect(dialect).toHaveText("PostgreSQL");
  const edit = () => page.getByRole("button", { name: "编辑信息" }).click();
  const save = () => page.getByRole("button", { name: "保存信息" }).click();
  const stored = async () => (await (await request.get(`/api/drivers/${id}`)).json());
  await edit();
  await expect(page.locator("#sql-dialect")).toHaveText("PostgreSQL");
  await save();
  await expect(page.locator("form")).toHaveCount(0);
  expect((await stored()).defaultSqlDialect).toBe("postgresql");
  await edit();
  await chooseOption(page, page.locator("#sql-dialect"), "");
  await save();
  await expect(dialect).toHaveText("跟随连接配置");
  expect((await stored()).defaultSqlDialect).toBeNull();
  await edit();
  await chooseOption(page, page.locator("#sql-dialect"), "postgresql");
  await save();
  await expect(dialect).toHaveText("PostgreSQL");
  for (const locale of ["zh-CN", "en"]) {
    await page.evaluate(language => localStorage.setItem("jdbc-test.locale", language), locale);
    await page.reload();
    for (const width of [1366, 375, 320]) {
      await page.setViewportSize({ width, height: width === 1366 ? 768 : 812 });
      await expect(page.getByText(locale === "en" ? "Default SQL dialect" : "默认 SQL 方言", { exact: true }).locator("+ dd")).toHaveText("PostgreSQL");
      await expect(page.getByText(basename(driverJar!), { exact: true })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await expect(page.locator("details[open]")).toHaveCount(0);
      if (width === 1366) await expect(page.getByRole("heading", { name: locale === "en" ? "Artifacts" : "驱动文件", exact: true })).toBeInViewport();
      await page.evaluate(() => document.fonts.ready);
      await expect(page.locator("main")).toHaveCSS("opacity", "1");
      await page.screenshot({ path: `${output}/screenshots/real-${locale}-${width}.png`, fullPage: true, animations: "disabled" });
      const hashes = page.getByRole("group", { name: locale === "en" ? "File fingerprints" : "文件指纹" });
      await hashes.locator("summary").click();
      await expect(hashes.getByText(uploaded.packageFingerprint, { exact: true })).toBeVisible();
      await expect(hashes.getByRole("button")).toHaveCount(2);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await hashes.locator("summary").click();
    }
  }
  // Driver file loading opens no target database and must preserve the dialect.
  await page.getByRole("button", { name: "Check driver files" }).click();
  const result = page.getByRole("region", { name: "Load check passed" });
  await expect(result).toBeVisible();
  await expect(result.locator("details")).not.toHaveAttribute("open");
  await result.locator("summary").click();
  await expect(result.getByText("0.0", { exact: true })).toBeVisible();
  await expect(result.getByText("false", { exact: true })).toBeVisible();
  expect((await stored()).defaultSqlDialect).toBe("postgresql");
  writeFileSync(`${output}/real-final.json`, JSON.stringify(await stored(), null, 2));
});
