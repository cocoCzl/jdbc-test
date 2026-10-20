import { expect, test } from "@playwright/test";
import { driver, driverPresets } from "../src/test/fixtures";
import { chooseOption } from "./select";

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
  await page.screenshot({ path: "../.local/driver-layout-20261020/fixture-add-driver-1366x768.png", animations: "disabled" });
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
  await page.screenshot({ path: "../.local/driver-layout-20261020/fixture-add-driver-mobile.png", fullPage: true });
});
