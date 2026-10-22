import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { finishedRun, page } from "@/test/fixtures";
import { json, mockApi, renderPage } from "@/test/harness";
import type { Run } from "@/types/api";

function setup(record: Run) {
  return mockApi(url => url.pathname === "/api/runs" ? json(page([record]))
    : url.pathname === `/api/runs/${record.id}` ? json(record)
    : url.pathname.endsWith("/report.json") ? json({ terminationReason: "TEST_FAILURE", workerReportValidated: true }) : undefined);
}

describe("One execution conclusion across records and detail", () => {
  it.each([
    ["COMPLETED", "Completed · unsuccessful cases"], ["ABNORMAL", "Ended abnormally"],
  ] as const)("uses the Server %s conclusion and keeps raw historical evidence", async (executionOutcome, label) => {
    const record: Run = { ...finishedRun, name: "Saved task", status: "FAILED", failed: 1,
      failureReason: "WORKER_EXECUTION_FAILED", executionOutcome, historicalOutcome: executionOutcome === "COMPLETED" };
    const api = setup(record);
    const user = userEvent.setup();
    renderPage("/runs");
    const table = await screen.findByRole("table");
    expect(within(table).getByText(label, {selector: ".status-badge"})).toBeVisible();
    await user.click(within(table).getByRole("link", {name: "View details"}));
    expect(await screen.findByRole("heading", {name: "Saved task"})).toBeVisible();
    expect(document.querySelector(".jdbc-page-header .status-badge")).toHaveTextContent(label);
    expect(screen.getByText("About these statistics").closest("details")).not.toHaveAttribute("open");
    expect(screen.getByRole("region", {name: "Execution accounting"})).not.toHaveTextContent("older version");
    if (executionOutcome === "COMPLETED") {
      await user.click(screen.getByText("Technical details"));
      expect(screen.getByText(/The older version recorded case failures/)).toBeVisible();
      expect(screen.getByText("FAILED", {selector: "dd"})).toBeVisible();
    }
    expect(api.mock.calls.some(([url]) => String(url).endsWith("/report.json"))).toBe(false);
  });
  it("keeps a real cleanup problem outside the tab panels", async () => {
    setup({...finishedRun, status: "FAILED", failureReason: "WORKER_EXECUTION_FAILED",
      cleanupEvidence: {status: "FAILED", attempted: 2, confirmedDeleted: 1}});
    renderPage("/runs/run-1");
    await screen.findByRole("region", {name: "Execution accounting"});
    const evidence = document.querySelector(".cleanup-FAILED");
    expect(evidence).toBeVisible();
    expect(evidence?.closest('[role="tabpanel"]')).toBeNull();
    await userEvent.setup().click(screen.getByRole("tab", {name: "Test cases"}));
    expect(evidence).toBeVisible();
  });
  it("does not silently reclassify only the detail when an older Server omits outcome evidence", async () => {
    const api = setup({...finishedRun, status: "FAILED", failureReason: "WORKER_EXECUTION_FAILED"});
    const user = userEvent.setup();
    renderPage("/runs");
    await screen.findByRole("table");
    await user.click(screen.getByRole("link", {name: "View details"}));
    expect(await screen.findByText("Ended abnormally", {selector: ".jdbc-page-header .status-badge"})).toBeVisible();
    expect(api.mock.calls.some(([url]) => String(url).endsWith("/report.json"))).toBe(false);
  });
});
