import { expect, test, type Page } from "@playwright/test";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { chooseOption } from "./select";
import type { Run } from "../src/types/api";

const fullRuns = [
  ["mysql", "6bcf0d97-3161-4cf3-8371-3009cfb4dd14", 172, 30],
  ["postgresql", "1a9a8846-6fb0-4a02-8950-8c1c9ff666f7", 198, 4],
  ["oracle", "b04ab9cc-51a7-4931-81b0-c9e7a78791f6", 189, 13],
] as const;
const coreId = "1aada571-04ea-41dd-b026-de776fa5a5b9";
const hash = (value: unknown) => createHash("sha256").update(JSON.stringify(value)).digest("hex");
async function language(page: Page, locale: "en" | "zh-CN") {
  await page.getByRole("button", {name:/^(Language|界面语言)$/}).click();
  await page.getByRole("menuitemradio",{name:locale === "en" ? "English" : "简体中文"}).click();
  await expect(page.locator("html")).toHaveAttribute("lang",locale);
}

test("PRESERVED DATABASE COPY: bilingual real history, shared components, read-only evidence", async ({page}) => {
  test.skip(process.env.JDBC_TEST_HISTORY_READONLY !== "true", "Requires verified historical viewer; never starts or initializes QA");
  const writes: string[]=[]; const sse: string[]=[]; const errors: string[]=[];
  page.on("pageerror",error => errors.push(error.message));
  await page.route(url => url.pathname.startsWith("/api/"), async route => {
    if(route.request().method() !== "GET") {writes.push(route.request().url()); await route.abort(); return;}
    if(route.request().url().endsWith("/events")) sse.push(route.request().url());
    await route.continue();
  });
  await page.goto("/"); await expect(page.locator("h1")).toHaveText("Workspace overview");
  const script = await page.locator('script[type="module"]').getAttribute("src");
  expect(script).toMatch(/^\/assets\/index-.+\.js$/);
  const loaded = await (await page.request.get(script!)).body();
  expect(createHash("sha256").update(loaded).digest("hex")).toBe(createHash("sha256").update(readFileSync(`dist${script}`)).digest("hex"));
  const listed = await (await page.request.get("/api/runs?size=20")).json() as {items:Run[]};
  expect(listed.items).toHaveLength(15);
  const oracleFull=listed.items.find(run=>run.id===fullRuns[2][1])!;
  const evidence: unknown[]=[];
  for(const locale of ["en","zh-CN"] as const) {
    await language(page,locale);
    for(const [name,path] of [["dashboard","/"],["drivers","/drivers"],["connections","/connections"],["history","/runs"],["driver-upload","/drivers/new"],["connection-new","/connections/new"],["wizard","/runs/new"],["driver-detail",`/drivers/${oracleFull.driver.id}`]] as const) {
      await page.goto(path); await expect(page.locator("h1")).toBeVisible();
      await expect(page.getByRole("status").filter({hasText:/Loading|加载/})).toHaveCount(0);
      if (name === "dashboard") {
        await expect(page.getByRole("img").locator("canvas")).toBeVisible();
        // Capture the finished chart rather than its initial animation frame.
        await page.waitForTimeout(1100);
      }
      await page.screenshot({path:`test-results/history-${name}-${locale}.png`,fullPage:true});
    }
    await page.goto("/drivers"); await page.getByRole("combobox").click();
    await expect(page.getByRole("listbox")).toBeVisible(); await page.screenshot({path:`test-results/history-drivers-expanded-${locale}.png`}); await page.keyboard.press("Escape");
    await page.goto("/runs"); await expect(page.getByRole("row").nth(1)).toBeVisible();
    await page.getByRole("combobox",{name:locale === "en" ? "Filter run status" : "筛选运行状态"}).click();
    await expect(page.getByRole("listbox")).toBeVisible();
    await page.screenshot({path:`test-results/history-status-expanded-${locale}.png`}); await page.keyboard.press("Escape");
    await page.goto("/connections");
    const oracleRow=page.getByRole("row").filter({hasText:/oracle/i}).first();
    await oracleRow.getByRole("button",{name:locale === "en" ? "View URL" : "查看 URL"}).click();
    await expect(page.getByRole("dialog").locator("pre")).toContainText("DESCRIPTION"); await page.screenshot({path:`test-results/history-oracle-url-${locale}.png`,fullPage:true}); await page.keyboard.press("Escape");
    await page.goto(`/runs/${coreId}`); await expect(page.locator("h1")).toContainText(locale === "en" ? "Core tests ·" : "核心测试 ·");
    await expect(page.locator("h1")).not.toContainText(" + "); await expect(page.getByRole("region",{name:locale === "en" ? "Test selection" : "测试选择"})).toBeVisible();
    await page.screenshot({path:`test-results/history-oracle-core-${locale}.png`,fullPage:true});
    for(const [database,id,passed,skipped] of fullRuns) {
      const baseline=await (await page.request.get(`/api/runs/${id}`)).json() as Run;
      expect([baseline.totalCases,baseline.passed,baseline.failed,baseline.skipped]).toEqual([202,passed,0,skipped]); expect(baseline.status).toBe("SUCCEEDED");
      await page.goto(`/runs/${id}`);
      const metric = (en:string,zh:string) => page.locator(`[data-metric=${JSON.stringify(locale === "en" ? en : zh)}]`);
      await expect(metric("Total","总计")).toContainText("202"); await expect(metric("Passed","通过")).toContainText(String(passed)); await expect(metric("Skipped","已跳过")).toContainText(String(skipped));
      await expect(page.getByRole("progressbar")).toHaveAttribute("value","202");
      await page.screenshot({path:`test-results/history-${database}-full-${locale}.png`,fullPage:true});
      await page.getByRole("tab",{name:locale === "en" ? "Test cases" : "测试用例"}).click();
      await chooseOption(page,page.getByRole("combobox",{name:locale === "en" ? "Filter result status" : "筛选结果状态"}),"SKIPPED");
      const apiResults = await (await page.request.get(`/api/runs/${id}/results?status=SKIPPED&size=20`)).json();
      expect(apiResults.totalElements).toBe(skipped);
      const reason=apiResults.items[0].message;
      await page.getByRole("button",{name:locale === "en" ? "Skip reason" : "跳过原因"}).first().click();
      if(reason) await expect(page.getByRole("dialog")).toContainText(reason);
      await language(page,locale === "en" ? "zh-CN" : "en");
      expect(new URL(page.url()).pathname).toBe(`/runs/${id}`); await expect(page.getByRole("dialog")).toBeVisible();
      if(reason) await expect(page.getByRole("dialog")).toContainText(reason);
      await page.keyboard.press("Escape");
      await expect(page.getByRole("combobox").first()).toHaveAttribute("data-value","SKIPPED");
      await language(page,locale);
      await page.screenshot({path:`test-results/history-${database}-results-${locale}.png`,fullPage:true});
      await page.getByRole("tab",{name:locale === "en" ? "Report" : "报告",exact:true}).click();
      await expect(page.getByRole("heading",{name:locale === "en" ? "Captured environment" : "运行环境快照"})).toBeVisible();
      await expect(page.getByRole("img").locator("canvas")).toBeVisible();
      await page.waitForTimeout(1100);
      await page.screenshot({path:`test-results/history-${database}-report-${locale}.png`,fullPage:true});
      const raw=await (await page.request.get(`/api/runs/${id}/report.json`)).json();
      expect(raw.source).toBe("PERSISTED_WORKER_EVENTS");
      await page.getByRole("button",{name:locale === "en" ? "View report JSON" : "查看报告 JSON"}).click();
      await expect(page.getByRole("dialog")).toContainText(locale === "en" ? "Data source: run events saved in the database." : "数据来源：数据库中保存的运行事件。");
      await expect(page.getByRole("dialog")).toContainText(locale === "en" ? "original report.json file is unavailable" : "原始 report.json 文件不可用");
      await language(page,locale === "en" ? "zh-CN" : "en");
      expect(hash(await (await page.request.get(`/api/runs/${id}/report.json`)).json())).toBe(hash(raw));
      expect(hash(await (await page.request.get(`/api/runs/${id}`)).json())).toBe(hash(baseline));
      await page.keyboard.press("Escape"); await language(page,locale);
      evidence.push({database,id,locale,passed,skipped,snapshotSha256:hash(baseline),jsonSha256:hash(raw),jsonSource:raw.source});
    }
  }
  await page.setViewportSize({width:390,height:844}); await page.goto(`/runs/${coreId}`);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.getByRole("button",{name:"打开导航"}).click(); await page.screenshot({path:"test-results/history-mobile-navigation-zh-CN.png",fullPage:true}); await page.keyboard.press("Escape");
  expect(writes).toEqual([]); expect(sse).toEqual([]); expect(errors).toEqual([]);
  writeFileSync("test-results/history-readonly-evidence.json",JSON.stringify({source:"Preserved database copy; no target tests",bundle:script,evidence,writes,sse,errors},null,2));
});
