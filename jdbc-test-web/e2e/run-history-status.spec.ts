import { expect, test } from "@playwright/test";
import { finishedRun, page as paged, result } from "../src/test/fixtures";
import type { Run, RunStatus, RunStatusGroup } from "../src/types/api";
import { chooseOption } from "./select";

// TEST FIXTURE ONLY. HTTP pagination/filtering is simulated here; backend predicates
// are independently exercised by RunStatusFilteringTest against real H2 queries.
const groups: Record<RunStatusGroup, RunStatus[]> = {
  WAITING: ["QUEUED"], IN_PROGRESS: ["PREPARING", "PROBING", "RUNNING", "GENERATING_REPORT"],
  COMPLETED: ["SUCCEEDED"], ABNORMAL: ["FAILED", "INTERRUPTED"], CANCELLED: ["CANCELLED"],
};
const states = Object.values(groups).flat();
const history: Run[] = Array.from({ length: 45 }, (_, index) => ({
  ...finishedRun, id: `fixture-${index}`, status: states[index % states.length],
  connection: { ...finishedRun.connection, name: `TEST FIXTURE database ${index}` },
  passed: 20, failed: 2, skipped: 1,
  failureReason: ["FAILED", "INTERRUPTED"].includes(states[index % states.length]) ? "WORKER_EXITED_UNEXPECTEDLY" : null,
}));

for (const locale of ["en", "zh-CN"] as const) {
  for (const width of [1440, 390]) {
    test(`TEST FIXTURE: lifecycle filter, keyboard and rows ${locale} ${width}px`, async ({ page }) => {
      const requests: URL[] = [];
      await page.setViewportSize({ width, height: 900 });
      await page.addInitScript(language => localStorage.setItem("jdbc-test.locale", language), locale);
      await page.route(url => url.pathname.startsWith("/api/"), async route => {
        expect(route.request().method()).toBe("GET");
        const url = new URL(route.request().url());
        if (url.pathname === "/api/runs") {
          requests.push(url);
          const group = url.searchParams.get("statusGroup") as RunStatusGroup | null;
          expect(url.searchParams.has("status")).toBe(false);
          const filtered = group === "CANCELLED" ? [] : group ? history.filter(run => groups[group].includes(run.status)) : history;
          const number = Number(url.searchParams.get("page"));
          return route.fulfill({ json: paged(filtered.slice(number * 20, (number + 1) * 20), {
            page: number, totalElements: filtered.length, totalPages: Math.ceil(filtered.length / 20),
          }) });
        }
        const completed = { ...history[5], status: "SUCCEEDED" };
        if (url.pathname.endsWith("/report")) return route.fulfill({ json: { summary: completed, failures: [result] } });
        if (url.pathname.startsWith("/api/runs/")) return route.fulfill({ json: completed });
        return route.fulfill({ json: paged([]) });
      });
      await page.goto("/runs");
      const chinese = locale === "zh-CN";
      const trigger = page.getByRole("combobox", { name: chinese ? "筛选运行状态" : "Filter run status" });
      await expect(page.getByRole("row")).toHaveCount(21);
      await trigger.focus();
      await page.keyboard.press("Enter");
      const labels = chinese ? ["全部状态", "等待中", "进行中", "已完成", "异常结束", "已取消"]
        : ["All statuses", "Waiting", "In progress", "Completed", "Ended abnormally", "Cancelled"];
      await expect(page.getByRole("option")).toHaveText(labels);
      for (const option of await page.getByRole("option").all()) {
        await expect(option).toBeInViewport();
      }
      await page.screenshot({ path: test.info().outputPath(`fixture-menu-${locale}-${width}.png`) });
      await page.keyboard.press("Escape");
      await expect(trigger).toBeFocused();
      await page.getByRole("button", { name: chinese ? "下一页" : "Next page" }).click();
      await expect.poll(() => requests.at(-1)?.searchParams.get("page")).toBe("1");
      await trigger.focus();
      await page.keyboard.press("Enter");
      await expect(page.getByRole("option", { name: labels[0], exact: true })).toBeFocused();
      await page.keyboard.press("End");
      await expect(page.getByRole("option", { name: labels[5], exact: true })).toBeFocused();
      await page.keyboard.press("ArrowUp");
      await expect(page.getByRole("option", { name: labels[4], exact: true })).toBeFocused();
      await page.keyboard.press("Enter");
      await expect(trigger).toHaveAttribute("data-value", "ABNORMAL");
      await expect(trigger).toBeFocused();
      await expect.poll(() => requests.at(-1)?.searchParams.get("page")).toBe("0");
      await expect.poll(() => requests.at(-1)?.searchParams.get("statusGroup")).toBe("ABNORMAL");
      await expect(page.locator("tbody .status-badge")).toHaveText(Array(10).fill(labels[4]));
      await expect(page.locator("tbody .run-result-danger").first()).toHaveText(chinese ? "失败2" : "Failed2");
      await chooseOption(page, trigger, "IN_PROGRESS");
      await expect(page.locator("tbody .status-badge")).toHaveText(Array(20).fill(labels[2]));
      for (const phase of chinese ? ["准备测试环境", "探测连接", "正在执行", "生成报告"]
        : ["Preparing test environment", "Probing connection", "Executing", "Generating report"]) {
        await expect(page.getByText(phase, { exact: true }).first()).toBeVisible();
      }
      await chooseOption(page, trigger, "COMPLETED");
      await expect(page.locator("tbody .status-badge")).toHaveText(Array(5).fill(labels[3]));
      await expect(page.locator("tbody .run-result-danger").first()).toHaveText(chinese ? "失败2" : "Failed2");
      await page.screenshot({ path: test.info().outputPath(`fixture-completed-${locale}-${width}.png`), fullPage: true });
      await chooseOption(page, trigger, "CANCELLED");
      await expect(page.getByRole("heading", { name: chinese ? "没有符合条件的运行" : "No matching runs" })).toBeVisible();
      await page.getByRole("button", { name: chinese ? "清除状态筛选" : "Clear status filter" }).click();
      await expect(trigger).toHaveText(labels[0]);
      await expect(page.getByRole("row")).toHaveCount(21);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      await page.goto("/runs/fixture-5");
      await expect(page.locator(".status-badge").filter({ hasText: new RegExp(`^${labels[3]}$`) })).toBeVisible();
      await expect(page.getByRole("button", { name: chinese ? "取消运行" : "Cancel run" })).toHaveCount(0);
      await page.getByRole("tab", { name: chinese ? "报告" : "Report", exact: true }).click();
      await expect(page.locator(".status-badge").filter({ hasText: new RegExp(`^${labels[3]}$`) })).toHaveCount(2);
      await expect(page.getByText(result.displayName, { exact: true })).toBeVisible();
    });
  }
}
