import { expect, test, type Page } from "@playwright/test";
import { adapters, catalog, connection, driver, finishedRun, run, result, page as paged } from "../src/test/fixtures";
import { chooseOption } from "./select";

// Every API is intercepted. These fixtures never run JDBC or contact the backend.
const oracle = "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=fixture.invalid)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=fixture_service)))?password=fixture-secret";
const accounting = {selected:23, started:23, completed:23, passed:17, failed:3, skipped:2, aborted:1, pending:0, running:0, notRun:0, incomplete:0, scenarios:[]};
const history = {...finishedRun, profile:"CORE", status:"FAILED", passed:17, failed:3, skipped:2, accounting,
  selectedModules:["connection","statement"], connection:{...connection, name:"Theme fixture database", jdbcUrl:oracle}};
const toggle = (page: Page) => page.getByRole("button", {name:/^(Theme:|当前主题：)/});
async function dark(page: Page, value: boolean) {
  for (let i=0; i<3; i++) {
    if (await page.locator("html").getAttribute("data-theme") === (value ? "dark" : "white")) return;
    await toggle(page).click();
  }
  await expect(page.locator("html")).toHaveAttribute("data-theme", value ? "dark" : "white");
}
async function cycle(page: Page) { for (let i=0; i<3; i++) await toggle(page).click(); }
async function fixtures(page: Page) {
  const requests: string[] = [];
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    const url = new URL(route.request().url());
    expect(route.request().method()).toBe("GET");
    requests.push(url.pathname+url.search);
    const path = url.pathname;
    const body = path === "/api/test-catalog" ? catalog : path === "/api/adapters" ? adapters
      : path === "/api/drivers" ? paged([driver]) : path === "/api/drivers/driver-1" ? driver
      : path === "/api/connections" ? paged([{...connection,jdbcUrl:oracle}]) : path === "/api/connections/connection-1" ? connection
      : path === "/api/runs" ? paged([history],{page:Number(url.searchParams.get("page")),totalPages:2,totalElements:21})
      : path === "/api/runs/active" ? {...run,id:"active"} : path === "/api/runs/run-1" ? history
      : path.endsWith("/results") ? paged([result]) : path.endsWith("/report") ? {summary:history,failures:[result]}
      : path.endsWith("/report.json") ? {summary:history} : undefined;
    if (!body) throw new Error(`Unexpected theme fixture request ${path}`);
    await route.fulfill({json:body});
  });
  return requests;
}
test.beforeEach(async ({page}) => {
  await page.emulateMedia({colorScheme:"light", reducedMotion:"reduce"});
  await page.addInitScript(() => { if (!localStorage.getItem("jdbc-test.locale")) localStorage.setItem("jdbc-test.locale","en"); });
});

test("theme preferences: keyboard cycle, bilingual tooltip, refresh and OS changes", async ({page}) => {
  await fixtures(page);
  await page.goto("/runs");
  await expect(toggle(page)).toHaveAccessibleName("Theme: System. Switch to Light.");
  await expect(toggle(page).locator(".lucide-monitor")).toHaveCount(1);
  await page.emulateMedia({colorScheme:"dark"});
  await expect(page.locator("html")).toHaveAttribute("data-theme","dark");
  await toggle(page).focus();
  await expect(page.getByRole("tooltip")).toHaveText("Theme: System. Switch to Light.");
  await page.keyboard.press("Enter");
  await expect(toggle(page)).toHaveAccessibleName("Theme: Light. Switch to Dark.");
  await expect(toggle(page).locator(".lucide-sun")).toHaveCount(1);
  await page.emulateMedia({colorScheme:"light"}); await page.emulateMedia({colorScheme:"dark"});
  await expect(page.locator("html")).toHaveAttribute("data-theme","white");
  await page.reload(); await expect(toggle(page)).toHaveAccessibleName("Theme: Light. Switch to Dark.");
  await toggle(page).focus(); await page.keyboard.press("Space");
  await expect(toggle(page).locator(".lucide-moon")).toHaveCount(1);
  await page.emulateMedia({colorScheme:"light"});
  await expect(page.locator("html")).toHaveAttribute("data-theme","dark");
  await page.reload(); await expect(page.locator("html")).toHaveAttribute("data-theme","dark");
  await page.getByRole("button",{name:"Language",exact:true}).click();
  await page.getByRole("menuitemradio",{name:"简体中文"}).click();
  await expect(toggle(page)).toHaveAccessibleName("当前主题：深色。切换为跟随系统。");
  await toggle(page).focus(); await expect(page.getByRole("tooltip")).toHaveText("当前主题：深色。切换为跟随系统。");
  await page.keyboard.press("Enter");
  expect(await page.evaluate(() => localStorage.getItem("jdbc-test.theme"))).toBe("system");
  await page.reload(); await expect(toggle(page)).toHaveAccessibleName("当前主题：跟随系统。切换为浅色。");
  await page.emulateMedia({colorScheme:"dark"});
  await expect(page.locator("html")).toHaveAttribute("data-theme","dark");
  expect(await page.evaluate(() => localStorage.getItem("jdbc-test.theme"))).toBe("system");
});

for (const storage of ["invalid", "blocked", "dark"] as const) {
  test(`first paint and storage fallback: ${storage}`, async ({page}) => {
    await fixtures(page);
    await page.emulateMedia({colorScheme:storage === "dark" ? "light" : "dark"});
    await page.addInitScript(value => {
      if (value === "blocked") Object.defineProperty(window,"localStorage",{get() {throw new DOMException("Disabled","SecurityError");}});
      else localStorage.setItem("jdbc-test.theme",value);
    }, storage);
    // Hold React's module: the HTML bootstrap must paint dark before the app can mount.
    let release!: () => void;
    const ready = new Promise<void>(resolve => { release = resolve; });
    await page.route(/\/(?:src\/main\.tsx|assets\/index-[^/]+\.js)(?:\?|$)/, async route => { await ready; await route.continue(); });
    try {
      await page.goto("/runs",{waitUntil:"commit"});
      await expect(page.locator("html")).toHaveAttribute("data-theme","dark");
      await expect(page.locator("html")).toHaveCSS("background-color","rgb(21, 20, 18)");
      await expect(page.locator("html")).toHaveCSS("color-scheme","dark");
      await expect(page.locator("#root")).toBeEmpty();
    } finally { release(); }
    await expect(toggle(page)).toBeVisible();
    await cycle(page);
    await expect(page.locator("html")).toHaveAttribute("data-theme","dark");
    if(storage !== "dark") {
      await page.emulateMedia({colorScheme:"light"});
      await expect(page.locator("html")).toHaveAttribute("data-theme","white");
    }
  });
}

test("FIXTURE screenshots: overview, history, detail, expanded menu and URL in both themes", async ({page}) => {
  const requests = await fixtures(page);
  await page.setViewportSize({width:1600,height:1000});
  for (const [name,path] of [["overview","/"],["history","/runs"],["detail","/runs/run-1"],["menu","/drivers"],["url","/connections"]]) {
    await page.goto(path);
    await expect(page.locator("h1")).toBeVisible();
    await page.waitForLoadState("networkidle");
    if (name === "overview") await page.waitForTimeout(1100); // ECharts' initial canvas reveal.
    const canvas = name === "overview" ? await page.locator("canvas").elementHandle() : null;
    const geometry = await page.locator(".metric, .jdbc-navigation, .jdbc-table").evaluateAll(elements => elements.map(el=>({width:el.getBoundingClientRect().width,height:el.getBoundingClientRect().height})));
    const before = requests.length;
    for (const mode of ["light","dark"] as const) {
      await dark(page, mode === "dark");
      if(name === "menu") { await page.getByRole("combobox").click(); await expect(page.getByRole("listbox")).toBeVisible(); }
      if(name === "url") { await page.getByRole("button",{name:"View URL"}).click(); await expect(page.getByRole("dialog")).toContainText("fixture_service"); }
      if(name === "overview") { await expect(page.locator("canvas")).toBeVisible(); expect(await canvas!.evaluate(el=>el.isConnected)).toBe(true); }
      await page.mouse.move(0,0);
      await page.screenshot({path:`test-results/theme/fixture-${name}-${mode}.png`,fullPage:true,animations:"disabled"});
      if (name === "menu" || name === "url") {
        const surface = page.locator(name === "menu" ? ".jdbc-menu" : ".jdbc-dialog");
        await expect(surface).toHaveCSS("background-color",mode === "dark" ? "rgb(42, 39, 35)" : "rgb(255, 255, 255)");
        await page.keyboard.press("Escape");
      }
    }
    expect(await page.locator(".metric, .jdbc-navigation, .jdbc-table").evaluateAll(elements => elements.map(el=>({width:el.getBoundingClientRect().width,height:el.getBoundingClientRect().height})))).toEqual(geometry);
    expect(requests.length).toBe(before);
  }
});

test("state survives theme changes: password, driver form, wizard, filters, pagination and chart", async ({page}) => {
  const requests = await fixtures(page);
  await page.goto("/connections/new");
  await page.getByLabel("Name",{exact:true}).fill("Unsaved theme fixture");
  await page.getByLabel("Password",{exact:true}).fill("fixture-password-only");
  await chooseOption(page,page.getByRole("combobox",{name:"Adapter",exact:true}),"postgresql");
  await page.waitForLoadState("networkidle"); let before = requests.length;
  const input = await page.getByLabel("Password",{exact:true}).elementHandle();
  await cycle(page);
  await expect(page.getByLabel("Name",{exact:true})).toHaveValue("Unsaved theme fixture");
  await expect(page.getByLabel("Password",{exact:true})).toHaveValue("fixture-password-only");
  expect(await input!.evaluate(el=>el.isConnected)).toBe(true);
  expect(requests.length).toBe(before);
  await page.goto("/drivers/new");
  await page.getByLabel("Name",{exact:true}).fill("Unsaved driver");
  await page.getByLabel("JAR files").setInputFiles({name:"fixture.jar",mimeType:"application/java-archive",buffer:Buffer.from("theme fixture")});
  await cycle(page);
  await expect(page.getByLabel("Name",{exact:true})).toHaveValue("Unsaved driver");
  await expect(page.getByText("fixture.jar",{exact:true})).toBeVisible();
  await page.goto("/runs/new");
  await page.getByRole("radio",{name:connection.name}).check();
  await page.getByRole("button",{name:"Next: Choose driver"}).click();
  await page.waitForLoadState("networkidle"); before=requests.length;
  await cycle(page);
  await expect(page.getByRole("heading",{name:"Choose a validated driver"})).toBeVisible();
  expect(requests.length).toBe(before);
  await page.goto("/runs");
  await chooseOption(page,page.getByRole("combobox",{name:"Filter run status"}),"FAILED");
  await chooseOption(page,page.getByRole("combobox",{name:"Sort runs"}),"finishedAt,desc");
  await page.getByRole("button",{name:"Next page"}).click();
  await expect(page.getByText("21 records · Page 2 of 2")).toBeVisible();
  await page.waitForLoadState("networkidle"); before=requests.length;
  await cycle(page);
  await expect(page.getByRole("combobox",{name:"Filter run status"})).toHaveAttribute("data-value","FAILED");
  await expect(page.getByRole("combobox",{name:"Sort runs"})).toHaveAttribute("data-value","finishedAt,desc");
  await expect(page.getByText("21 records · Page 2 of 2")).toBeVisible();
  expect(requests.length).toBe(before);
});

declare global { interface Window { themeSources: Array<EventTarget & {closed:boolean}> } }
test("live SSE and open portals survive system changes without extra API requests", async ({page}) => {
  const requests = await fixtures(page);
  await page.addInitScript(() => {
    window.themeSources=[];
    class FixtureEventSource extends EventTarget {
      closed=false;
      onopen: (()=>void) | null=null;
      constructor() { super(); window.themeSources.push(this); queueMicrotask(()=>this.onopen?.()); }
      close() { this.closed=true; }
    }
    Object.assign(window,{EventSource:FixtureEventSource});
  });
  await page.goto("/runs/active");
  await expect(page.getByText("Live updates connected")).toBeVisible();
  await page.evaluate(()=>window.themeSources.at(-1)!.dispatchEvent(new MessageEvent("run.progress",{data:JSON.stringify({runId:"active",completedCases:7,passed:5,failed:1,skipped:1})})));
  await expect(page.getByRole("progressbar")).toHaveAttribute("value","7");
  const sources=await page.evaluate(()=>window.themeSources.length);
  const before=requests.length;
  await cycle(page);
  await expect(page.getByText("Live updates connected")).toBeVisible();
  expect(await page.evaluate(()=>window.themeSources.length)).toBe(sources);
  expect(await page.evaluate(()=>window.themeSources.at(-1)!.closed)).toBe(false);
  await page.getByRole("button",{name:"Cancel run",exact:true}).click();
  await expect(page.getByRole("alertdialog")).toBeVisible();
  await page.emulateMedia({colorScheme:"dark"});
  await expect(page.getByRole("alertdialog")).toHaveCSS("background-color","rgb(42, 39, 35)");
  await expect(page.getByRole("alertdialog")).toBeVisible();
  await page.screenshot({path:"test-results/theme/fixture-confirm-dark.png",fullPage:true});
  await page.keyboard.press("Escape");
  await expect(page.getByRole("progressbar")).toHaveAttribute("value","7");
  expect(requests.length).toBe(before);
  await page.getByRole("button",{name:"View URL"}).click();
  await page.emulateMedia({colorScheme:"light"});
  await expect(page.getByRole("dialog")).toHaveCSS("background-color","rgb(255, 255, 255)");
  await page.emulateMedia({colorScheme:"dark"});
  await expect(page.getByRole("dialog")).toHaveCSS("background-color","rgb(42, 39, 35)");
  await page.keyboard.press("Escape");
  await page.getByRole("link",{name:"Drivers",exact:true}).click();
  await expect.poll(()=>page.evaluate(()=>window.themeSources.at(-1)!.closed)).toBe(true);
});

test("all remaining pages, tabs and mobile navigation render in dark mode", async ({page}) => {
  await fixtures(page);
  await page.emulateMedia({colorScheme:"dark"});
  for (const path of ["/drivers/driver-1","/connections/connection-1","/drivers/new","/runs/new","/missing"]) {
    await page.goto(path); await expect(page.getByRole("main").getByRole("heading").first()).toBeVisible();
    await expect(page.locator("body")).toHaveCSS("background-color","rgb(21, 20, 18)");
    await page.screenshot({path:`test-results/theme/fixture-${path.replaceAll("/","-")}-dark.png`,fullPage:true});
  }
  await page.goto("/runs/run-1");
  for(const tab of ["Test cases","Report"]) {
    await page.getByRole("tab",{name:tab,exact:true}).click();
    await expect(page.getByText(tab === "Report" ? "Captured environment" : "Execute statement",{exact:true})).toBeVisible();
    if (tab === "Report") { await expect(page.locator("canvas")).toBeVisible(); await page.waitForTimeout(1100); }
    await page.screenshot({path:`test-results/theme/fixture-${tab.replaceAll(" ","-")}-dark.png`,fullPage:true});
  }
  await page.getByRole("button",{name:"View report JSON"}).click();
  await expect(page.getByRole("dialog")).toContainText('"summary"');
  await expect(page.getByRole("dialog")).toHaveCSS("background-color","rgb(42, 39, 35)");
  await page.keyboard.press("Escape");
  await page.setViewportSize({width:390,height:844});
  await page.getByRole("button",{name:"Open navigation"}).click();
  await expect(page.getByRole("menu")).toHaveCSS("background-color","rgb(42, 39, 35)");
  await page.screenshot({path:"test-results/theme/fixture-mobile-menu-dark.png",fullPage:true});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
