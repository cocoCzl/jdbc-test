import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { result } from "@/test/fixtures";
import { FailureDetail } from "./results";
import type { ResultStatus } from "@/types/api";

describe("Case details for every result", () => {
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
});
