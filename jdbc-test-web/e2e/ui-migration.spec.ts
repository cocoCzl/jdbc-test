import { expect, test } from "@playwright/test";
import { catalog, connection, driver, finishedRun, page as paged } from "../src/test/fixtures";
const oracle = "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=oracle-fixture.invalid)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=fixture_service)))?password=fixture-secret";
test("TEST FIXTURE ONLY: CPA foundation representative pages and expanded menu", async ({page}) => {
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    expect(route.request().method()).toBe("GET");
    const path = new URL(route.request().url()).pathname;
    const run = {...finishedRun, profile:"CORE", selectedModules:catalog.modules.map(m=>m.id), connection:{...finishedRun.connection,name:"Oracle Core fixture",jdbcUrl:oracle}};
    const body = path === "/api/test-catalog" ? catalog : path === "/api/drivers" ? paged([driver]) : path === "/api/connections" ? paged([{...connection,jdbcUrl:oracle}]) : path === "/api/runs" ? paged([run]) : path === "/api/runs/run-1" ? run : undefined;
    if(!body) throw new Error(`Unexpected fixture request ${path}`);
    await route.fulfill({json:body});
  });
  await page.goto("/drivers");
  const sort = page.getByRole("combobox",{name:"Sort drivers"});
  await sort.focus(); await page.keyboard.press("ArrowDown");
  await expect(page.getByRole("listbox")).toBeVisible();
  await expect(page.getByRole("option",{name:"Newest first"})).toHaveAttribute("aria-selected","true");
  await page.screenshot({path:"test-results/fixture-cpa-drivers-expanded.png",fullPage:true});
  await page.keyboard.press("Escape"); await expect(sort).toBeFocused();
  await page.goto("/"); await expect(page.locator("h1")).toBeVisible();
  await page.screenshot({path:"test-results/fixture-cpa-dashboard.png",fullPage:true});
  await page.goto("/runs/run-1"); await expect(page.locator("h1")).toHaveText("Core tests · Oracle Core fixture");
  await expect(page.getByRole("region",{name:"Test selection"})).toBeVisible();
  await page.screenshot({path:"test-results/fixture-cpa-core.png",fullPage:true});
  await page.goto("/connections"); await page.getByRole("button",{name:"View URL"}).click();
  await expect(page.getByRole("dialog")).toContainText("SERVICE_NAME=fixture_service");
  await expect(page.getByRole("dialog")).not.toContainText("fixture-secret");
  await page.screenshot({path:"test-results/fixture-cpa-url.png",fullPage:true});
});

test("TEST FIXTURE ONLY: menus, long text, URL copy and bilingual form/wizard state", async ({page, context}) => {
  const requests: {path:string; method:string}[]=[];
  const longName = "Fixture — " + "LongConnection中文".repeat(12);
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    const path = new URL(route.request().url()).pathname;
    requests.push({path,method:route.request().method()}); expect(route.request().method()).toBe("GET");
    const run = {...finishedRun, profile:"CUSTOM", selectedModules:["connection","statement"], connection:{...finishedRun.connection,name:longName,jdbcUrl:oracle}};
    const body = path === "/api/test-catalog" ? catalog : path === "/api/drivers" ? paged([{...driver,name:"Long driver "+"LongName".repeat(30)}]) : path === "/api/drivers/driver-1" ? driver : path === "/api/adapters" ? Array.from({length:30},(_,i)=>({id:`adapter-${i}`,name:`Long adapter option ${i} — JDBC fixture`,status:"OFFICIAL"})) : path === "/api/connections" ? paged([{...connection,name:longName,jdbcUrl:oracle}]) : path === "/api/runs" ? paged([run]) : path === "/api/runs/run-1" ? run : undefined;
    if(!body) throw new Error(`Unexpected fixture ${path}`); await route.fulfill({json:body});
  });
  const switchLanguage = async (locale:"en"|"zh-CN") => {
    await page.getByRole("button",{name:/^(Language|界面语言)$/}).click();
    await page.getByRole("menuitemradio",{name:locale === "en" ? "English" : "简体中文"}).click();
  };
  const noOverflow = async () => expect(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  for(const width of [1440,390]) {
    await page.setViewportSize({width,height:800});
    await page.goto("/drivers");
    const trigger = page.getByRole("combobox"); await trigger.focus(); await page.keyboard.press("ArrowDown");
    await expect(page.getByRole("listbox")).toBeVisible();
    const box = await page.getByRole("listbox").boundingBox(); expect(box!.x).toBeGreaterThanOrEqual(0); expect(box!.x+box!.width).toBeLessThanOrEqual(width);
    await page.keyboard.press("End"); await expect(page.getByRole("option").last()).toBeFocused(); await page.keyboard.press("Enter"); await expect(trigger).toHaveAttribute("data-value","status,asc");
    await switchLanguage("zh-CN"); await expect(trigger).toHaveAttribute("data-value","status,asc"); await noOverflow();
    await page.goto("/runs/run-1"); await expect(page.locator("h1")).toHaveText(`自定义测试 · ${longName}`); await expect(page.getByRole("region",{name:"测试选择"})).toContainText("语句"); await noOverflow();
    await page.screenshot({path:`test-results/fixture-custom-long-zh-${width}.png`,fullPage:true});
    await switchLanguage("en"); await expect(page.locator("h1")).toHaveText(`Custom tests · ${longName}`);
    await page.goto("/runs/new");
    const radio = page.getByRole("radio",{name:longName}); const next=page.getByRole("button",{name:"Next: Choose driver"});
    await radio.focus(); await expect(radio).not.toBeChecked(); await expect(next).toBeDisabled();
    await page.getByRole("button",{name:"Copy",exact:true}).click();
    const copied=await page.evaluate(()=>navigator.clipboard.readText());
    expect(copied).toBe(oracle.replace("fixture-secret","[REDACTED]")); await expect(radio).not.toBeChecked(); await expect(next).toBeDisabled();
    const view=page.getByRole("button",{name:"View URL"}); await view.click(); await expect(page.getByRole("dialog")).toContainText(copied); await page.keyboard.press("Escape"); await expect(view).toBeFocused();
    await radio.check(); await next.click(); await switchLanguage("zh-CN"); await expect(page.getByRole("heading",{name:"选择已验证的驱动"})).toBeVisible();
    await switchLanguage("en"); await page.goto("/connections/new");
    await page.getByLabel("Name",{exact:true}).fill("Unsubmitted fixture input");
    const adapter=page.getByRole("combobox",{name:"Adapter",exact:true}); await adapter.click();
    await page.keyboard.press("End"); await expect(page.getByRole("option").last()).toBeFocused(); await page.keyboard.press("Enter"); await expect(adapter).toHaveAttribute("data-value","adapter-29");
    await noOverflow(); await adapter.click(); await expect(page.getByRole("listbox")).toBeVisible(); await page.screenshot({path:`test-results/fixture-form-menu-en-${width}.png`}); await page.keyboard.press("Escape"); await expect(adapter).toBeFocused();
    await switchLanguage("zh-CN"); await expect(page.getByLabel("名称",{exact:true})).toHaveValue("Unsubmitted fixture input"); await expect(page.getByRole("combobox",{name:"适配包",exact:true})).toHaveAttribute("data-value","adapter-29"); await noOverflow();
    await switchLanguage("en");
  }
  expect(requests.every(request=>request.method === "GET")).toBe(true);
});
