import { expect, test } from "@playwright/test";
test("TEST FIXTURE ONLY: nested menu scrolling, disabled, focus, tooltip, toast, errors", async ({page}) => {
  await page.addInitScript(() => localStorage.setItem("jdbc-test.locale", "en"));
  const api: string[]=[];
  await page.route(url=>url.pathname.startsWith("/api/"),route=>{api.push(route.request().url()); return route.abort();});
  for(const width of [1440,390]) {
    await page.setViewportSize({width,height:800}); await page.goto("/e2e/fixtures/primitives.html");
    const help=page.getByRole("button",{name:"Help fixture"}); await help.focus(); await expect(page.getByRole("tooltip")).toBeVisible(); await page.keyboard.press("Escape");
    await expect(page.getByRole("button",{name:"Disabled fixture"})).toBeDisabled(); await expect(page.getByRole("button",{name:"Loading fixture"})).toHaveAttribute("aria-busy","true");
    await page.getByRole("button",{name:"Show toast"}).click(); await expect(page.getByText("Fixture error toast")).toBeVisible();
    const open=page.getByRole("button",{name:"Open modal"}); await open.click();
    const select=page.getByRole("combobox",{name:"Nested selection"}); await select.focus(); await page.keyboard.press("ArrowDown");
    await expect(page.getByRole("option",{name:"Disabled option"})).toHaveAttribute("aria-disabled","true");
    await page.keyboard.press("End"); await expect(page.getByRole("option").last()).toBeFocused();
    const box=await page.getByRole("listbox").boundingBox(); expect(box!.x).toBeGreaterThanOrEqual(0); expect(box!.x+box!.width).toBeLessThanOrEqual(width); expect(box!.y+box!.height).toBeLessThanOrEqual(800);
    await page.screenshot({path:`test-results/fixture-modal-select-${width}.png`});
    await page.keyboard.press("Enter"); await expect(select).toHaveAttribute("data-value","option-49");
    await select.click(); await page.keyboard.press("Escape"); await expect(page.getByRole("dialog")).toBeVisible(); await expect(select).toBeFocused();
    await page.getByRole("button",{name:"Language"}).click(); await page.getByRole("menuitemradio",{name:"简体中文"}).click(); await expect(select).toHaveAttribute("data-value","option-49");
    await page.keyboard.press("Escape"); await expect(open).toBeFocused(); await expect(page.getByText("Submit count: 0")).toBeVisible();
    await expect(page.getByRole("alert")).toContainText("发生错误，请重试。");
    await expect(page.getByRole("alert")).not.toContainText("Fixture unavailable");
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }
  expect(api).toEqual([]);
});

test("TEST FIXTURE ONLY: dark shared states and live theme changes inside open portals", async ({page}) => {
  await page.emulateMedia({colorScheme:"dark",reducedMotion:"reduce"});
  await page.route(url=>url.pathname.startsWith("/api/"),route=>route.abort());
  await page.goto("/e2e/fixtures/primitives.html");
  await page.getByRole("button",{name:"Help fixture"}).focus();
  await expect(page.getByRole("tooltip")).toHaveCSS("background-color","rgb(42, 39, 35)");
  await page.keyboard.press("Escape");
  await page.getByRole("button",{name:"Show toast"}).click();
  await expect(page.locator("[data-sonner-toast]")).toHaveCSS("background-color","rgb(65, 39, 35)");
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByText("Fixture empty",{exact:true})).toBeVisible();
  await expect(page.getByText("Fixture loading",{exact:true})).toBeVisible();
  await expect(page.getByRole("button",{name:"Disabled fixture"})).toBeDisabled();
  await page.screenshot({path:"test-results/theme/fixture-shared-states-dark.png",fullPage:true});
  await page.getByRole("button",{name:"Open modal"}).click();
  await page.getByRole("combobox").click();
  await expect(page.getByRole("option",{name:"Disabled option"})).toHaveAttribute("aria-disabled","true");
  await expect(page.getByRole("listbox")).toHaveCSS("background-color","rgb(42, 39, 35)");
  const selected = page.getByRole("option",{selected:true});
  await expect(selected).toHaveCSS("background-color","rgb(46, 42, 38)");
  await page.emulateMedia({colorScheme:"light"});
  await expect(page.getByRole("listbox")).toHaveCSS("background-color","rgb(255, 255, 255)");
  // Radix temporarily aria-hides the underlying dialog while its modal listbox is open.
  await expect(page.getByRole("dialog",{includeHidden:true})).toBeVisible();
  await page.emulateMedia({colorScheme:"dark"});
  await expect(page.getByRole("listbox")).toHaveCSS("background-color","rgb(42, 39, 35)");
  await page.screenshot({path:"test-results/theme/fixture-nested-menu-dark.png",fullPage:true});
});
