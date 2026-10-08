import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import i18n from "@/i18n";
import { json, mockApi, problem, renderPage } from "@/test/harness";

describe("Report JSON provenance", () => {
  it("labels persisted events as database evidence and keeps the JSON when switching language", async () => {
    const evidence = { source: "PERSISTED_WORKER_EVENTS", runId: "run-1", summary: { passed: 23 } };
    const api = mockApi(url => url.pathname.endsWith("/report.json") ? json(evidence) : undefined);
    const user = userEvent.setup();
    const { router } = renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", { name: "Report" }));
    await user.click(await screen.findByRole("button", { name: "View report JSON" }));
    expect(await screen.findByRole("heading", { name: "Database evidence JSON" })).toBeVisible();
    expect(screen.getByText(/This is not the original Worker report file/)).toBeVisible();
    expect(screen.getByRole("button", { name: "Download database evidence JSON" })).toBeVisible();
    const original = document.querySelector("pre")!.textContent;
    await act(async () => { await i18n.changeLanguage("zh-CN"); });
    expect(screen.getByRole("heading", { name: "数据库证据 JSON" })).toBeVisible();
    expect(screen.getByText(/这不是原始 Worker 报告文件/)).toBeVisible();
    expect(screen.getByRole("button", { name: "下载数据库证据 JSON" })).toBeVisible();
    expect(document.querySelector("pre")!.textContent).toBe(original);
    expect(router.state.location.pathname).toBe("/runs/run-1");
    expect(api.mock.calls.filter(([url]) => String(url).endsWith("/report.json"))).toHaveLength(1);
  });
  it.each([
    ["en", "Report", "View report JSON", "The original report file is unavailable for this run."],
    ["zh-CN", "报告", "查看报告 JSON", "本次运行的原始报告文件不可用。"],
  ])("handles a missing original file in %s without offering a download", async (locale, tab, button, message) => {
    await i18n.changeLanguage(locale);
    mockApi(url => url.pathname.endsWith("/report.json") ? problem("REPORT_NOT_FOUND", 404) : undefined);
    const user = userEvent.setup();
    renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", { name: tab }));
    await user.click(await screen.findByRole("button", { name: button }));
    expect(await screen.findByText(`${message} (REPORT_NOT_FOUND)`)).toBeVisible();
    expect(document.querySelector("pre")).toBeNull();
    expect(screen.queryByRole("button", { name: /Download|下载/ })).not.toBeInTheDocument();
  });
});
