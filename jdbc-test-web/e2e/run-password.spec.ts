import { expect, test } from "@playwright/test";
import { adapters, catalog, connection, driver, page as paged } from "../src/test/fixtures";

for (const language of ["zh-CN", "en"]) for (const theme of ["light", "dark"]) {
  test(`FIXTURE ONLY: run password modes ${language} ${theme}`, async ({ page }) => {
    const zh = language === "zh-CN";
    const names = zh ? { title: "本次运行密码", saved: "使用已保存的密码", other: "输入其他密码", empty: "使用空密码", password: "密码", back: "上一步" }
      : { title: "Password for this run", saved: "Use saved password", other: "Enter another password", empty: "Use empty password", password: "Password", back: "Back" };
    const noSaved = { ...connection, id: "no-saved", name: "Fixture without saved password", hasSavedPassword: false };
    const submissions: Record<string, unknown>[] = [];
    await page.emulateMedia({ reducedMotion: "reduce", colorScheme: theme === "dark" ? "dark" : "light" });
    await page.addInitScript(({ language, theme }) => {
      localStorage.setItem("jdbc-test.locale", language); localStorage.setItem("jdbc-test.theme", theme);
    }, { language, theme });
    await page.route(url => url.pathname.startsWith("/api/"), async route => {
      const request = route.request(); const url = new URL(request.url());
      if (request.method() === "POST" && url.pathname === "/api/runs") {
        submissions.push(request.postDataJSON());
        // Keep the review page visible to verify secret cleanup after a rejected submission.
        return route.fulfill({ status: 422, json: { code: "INVALID_SELECTION", status: 422, detail: "Fixture rejection" } });
      }
      expect(request.method()).toBe("GET");
      const body = url.pathname === "/api/connections" ? paged([connection, noSaved])
        : url.pathname === "/api/connections/no-saved" ? noSaved
        : url.pathname === "/api/adapters" ? adapters : url.pathname === "/api/test-catalog" ? catalog
        : url.pathname === "/api/drivers" ? paged([driver]) : url.pathname === "/api/drivers/driver-1" ? driver : undefined;
      if (!body) throw new Error(`Unexpected fixture endpoint: ${url.pathname}`);
      return route.fulfill({ json: body });
    });
    const next = () => page.getByRole("button", { name: /^(Next:|下一步：)/ }).click();
    for (const mode of ["saved", "other", "empty"] as const) {
      await page.goto("/runs/new");
      await page.getByRole("radio", { name: connection.name, exact: true }).check();
      await next();
      const group = page.getByRole("radiogroup", { name: names.title, exact: true });
      const saved = group.getByRole("radio", { name: names.saved, exact: true });
      const other = group.getByRole("radio", { name: names.other, exact: true });
      const empty = group.getByRole("radio", { name: names.empty, exact: true });
      await expect(saved).toBeChecked();
      await expect(page.locator("#run-password")).toHaveCount(0);
      await saved.focus(); await page.keyboard.down("ArrowRight");
      await expect(other).toBeChecked();
      await page.keyboard.up("ArrowRight");
      await expect(page.getByRole("button", { name: /^(Next:|下一步：)/ })).toBeDisabled();
      await page.locator("#run-password").fill("fixture-hidden-old-password");
      await empty.click();
      await expect(page.locator("#run-password")).toHaveCount(0);
      await group.getByRole("radio", { name: names[mode], exact: true }).click();
      if (mode === "other") {
        await expect(page.locator("#run-password")).toHaveValue("");
        await page.locator("#run-password").fill("fixture-current-run-password");
        await next(); await page.getByRole("button", { name: names.back, exact: true }).click();
        await expect(other).toBeChecked();
        await expect(page.locator("#run-password")).toHaveValue("fixture-current-run-password");
      }
      await expect(page.getByText(zh ? /目标探测|vendor 名称/ : /Target probing|vendor names/)).toHaveCount(0);
      for (const width of [1366, 1920, 375, 320]) {
        await page.setViewportSize({ width, height: 900 });
        await page.evaluate(() => document.fonts.ready);
        await expect(page.locator("main")).toHaveCSS("opacity", "1");
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        const section = await page.locator(".run-password").boundingBox();
        const drivers = await page.getByRole("group", { name: zh ? "驱动包" : "Driver package", exact: true }).boundingBox();
        expect(Math.abs(section!.x - drivers!.x)).toBeLessThan(1);
        expect(section!.y).toBeGreaterThan(drivers!.y + drivers!.height);
        const rects = await group.getByRole("radio").evaluateAll(nodes => nodes.map(node => ({ width: node.getBoundingClientRect().width, height: node.getBoundingClientRect().height, color: getComputedStyle(node).color })));
        expect(rects).toEqual(Array(3).fill(rects[0])); expect(rects[0].width).toBe(16); expect(rects[0].height).toBe(16);
        const labels = await page.locator(".run-password-option").all();
        if (width >= 1024) expect((await labels[0].boundingBox())!.y).toBe((await labels[2].boundingBox())!.y);
        else expect((await labels[2].boundingBox())!.y).toBeGreaterThan((await labels[0].boundingBox())!.y);
        if (mode === "other") expect((await page.locator("#run-password").boundingBox())!.width).toBeLessThanOrEqual(420);
        await page.evaluate(() => window.scrollTo(0, 0));
        await page.screenshot({ path: test.info().outputPath(`fixture-${language}-${theme}-${mode}-${width}.png`), fullPage: true, animations: "disabled" });
      }
      await next(); await next();
      await expect(page.getByText(`${names.title}: ${names[mode]}`, { exact: true })).toBeVisible();
      await expect(page.locator("main")).not.toContainText("fixture-current-run-password");
      await page.getByRole("checkbox", { name: zh ? /我了解/ : /I understand/ }).check();
      const submitted = submissions.length;
      await page.getByRole("button", { name: zh ? "开始测试运行" : "Start test run", exact: true }).click();
      await expect.poll(() => submissions.length).toBe(submitted + 1);
      if (mode === "saved") expect(submissions.at(-1)).not.toHaveProperty("password");
      else expect(submissions.at(-1)?.password).toBe(mode === "empty" ? "" : "fixture-current-run-password");
      expect(JSON.stringify(submissions.at(-1))).not.toContain("fixture-hidden-old-password");
      expect(await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage))).not.toContain("fixture-current-run-password");
    }
    // A preset loaded asynchronously must derive password availability from that actual connection.
    await page.goto("/runs/new?connectionId=no-saved"); await next();
    const group = page.getByRole("radiogroup", { name: names.title, exact: true });
    await expect(group.getByRole("radio", { name: names.saved, exact: true })).toBeDisabled();
    await expect(group.getByRole("radio", { name: names.saved, exact: true })).toHaveAccessibleDescription(zh ? "此连接未保存密码。" : "This connection has no saved password.");
    await expect(group.getByRole("radio", { name: names.other, exact: true })).toBeChecked();
    await expect(page.locator("#run-password")).toHaveValue("");
    await page.locator("#run-password").fill("fixture-previous-connection-password");
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(page.locator("main")).toHaveCSS("opacity", "1");
    await page.screenshot({ path: test.info().outputPath(`fixture-${language}-${theme}-no-saved-320.png`), fullPage: true, animations: "disabled" });
    await page.getByRole("button", { name: names.back, exact: true }).click();
    await page.getByRole("radio", { name: connection.name, exact: true }).check(); await next();
    await expect(group.getByRole("radio", { name: names.saved, exact: true })).toBeChecked();
    await group.getByRole("radio", { name: names.other, exact: true }).click();
    await expect(page.locator("#run-password")).toHaveValue("");
    await page.getByRole("button", { name: names.back, exact: true }).click();
    await page.getByRole("radio", { name: noSaved.name, exact: true }).check(); await next();
    await expect(group.getByRole("radio", { name: names.other, exact: true })).toBeChecked();
    await expect(page.locator("#run-password")).toHaveValue("");
    expect(submissions).toHaveLength(3);
  });
}
