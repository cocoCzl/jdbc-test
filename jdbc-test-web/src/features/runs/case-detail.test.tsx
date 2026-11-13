import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { page, result } from "@/test/fixtures";
import { FailureDetail } from "./results";
import type { ResultStatus } from "@/types/api";
import { json, mockApi, renderPage } from "@/test/harness";
import i18n from "@/i18n";

describe("Case details for every result", () => {
  it("translates result headers while retaining the detail action and its focus target", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname.endsWith("/results") ? json(page([result])) : undefined);
    renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", { name: "Test cases" }));
    const trigger = await screen.findByRole("button", { name: "View details" });
    expect(screen.getByRole("columnheader", { name: "Display name" })).toBeVisible();
    await act(async () => { await i18n.changeLanguage("zh-CN"); });
    expect(screen.getByRole("columnheader", { name: "显示名称" })).toBeVisible();
    expect(trigger).toBeInTheDocument();
    await user.click(trigger);
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();
  });
  it.each(["pointer", "keyboard"])("returns focus to the result action after %s opening and Escape", async method => {
    const user = userEvent.setup();
    const api = mockApi(url => url.pathname.endsWith("/results") ? json(page([result])) : undefined);
    renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", { name: "Test cases" }));
    const trigger = await screen.findByRole("button", { name: "View details" });
    if (method === "keyboard") {
      trigger.focus();
      await user.keyboard("{Enter}");
    } else await user.click(trigger);
    expect(screen.getByRole("dialog")).toBeVisible();
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();
    expect(api.mock.calls.some(([, options]) => options?.method === "POST")).toBe(false);
  });
  it.each(["PASSED", "FAILED", "SKIPPED", "ABORTED"] as ResultStatus[])("shows an honest %s conclusion without invented evidence", async status => {
    render(<FailureDetail result={{ ...result, status, message: null, stackTrace: null }} close={() => {}} />);
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Case conclusion")).toBeVisible();
    expect(within(dialog).queryByText("Stack trace")).not.toBeInTheDocument();
    expect(within(dialog).queryByText("Raw evidence")).not.toBeInTheDocument();
    expect(within(dialog).queryByText("Expected")).not.toBeInTheDocument();
  });
  it("folds stack traces and preserves collected null values", async () => {
    const user = userEvent.setup();
    render(<FailureDetail result={{ ...result, executionEvidence: { testDomain: "SQL_FEATURE", steps: [
      { id: "one", phase: "EXECUTION", status: "FAILED", actual: null },
    ] } }} close={() => {}} />);
    const trace = screen.getByText("sanitized trace");
    expect(trace).not.toBeVisible();
    await user.click(screen.getByText("Stack trace"));
    expect(trace).toBeVisible();
    const step = screen.getByText("one · EXECUTION · FAILED");
    await user.click(step);
    expect(screen.getByText("null", { selector: "pre" })).toBeVisible();
    expect(screen.queryByText("Expected")).not.toBeInTheDocument();
  });
  it("shows the collected failure location and truncation marker without changing its conclusion", async () => {
    const user = userEvent.setup();
    const trace = "ROOT: java.lang.IllegalStateException: wrapper\n"
      + "CAUSE: java.lang.NullPointerException: controlled failure\n"
      + "\tat fixture.Driver.addBatch(Driver.java:42)\n... [TRUNCATED]";
    const failure = { ...result, status: "FAILED" as const, stackTrace: trace,
      message: "controlled failure", executionEvidence: { conclusion: "NOT_EVALUATED",
        phase: "EXECUTION", errorType: "java.lang.NullPointerException", stackTrace: trace } };
    mockApi(url => url.pathname.endsWith("/results") ? json(page([failure])) : undefined);
    renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", { name: "Test cases" }));
    await user.click(await screen.findByRole("button", { name: "View details" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Failed", { exact: true })).toBeVisible();
    expect(within(dialog).getByText("Contract not evaluated")).toBeVisible();
    const stack = within(dialog).getByText(trace, { selector: "pre", exact: true,
      normalizer: value => value });
    expect(stack).not.toBeVisible();
    await user.click(within(dialog).getByText("Stack trace"));
    expect(stack).toBeVisible();
    expect(stack).toHaveTextContent("fixture.Driver.addBatch(Driver.java:42)");
    expect(stack).toHaveTextContent("[TRUNCATED]");
  });
});
