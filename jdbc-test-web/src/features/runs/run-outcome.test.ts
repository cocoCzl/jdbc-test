import { describe, expect, it } from "vitest";
import { finishedRun } from "@/test/fixtures";
import type { Run } from "@/types/api";
import { completedWithFailures, failureExplanation, lifecycleLabel, outcomePresentation, legacyCaseFailureReport, failurePhaseLabel } from "./run-outcome";

export const verifiedFailure: Run = {
  ...finishedRun, status: "FAILED", failureReason: "TEST_FAILURE",
  workerTerminationReason: "TEST_FAILURE", workerReportValidated: true,
  totalCases: 4, completedCases: 4, passed: 2, failed: 1, skipped: 1,
  accounting: { selected: 4, started: 3, completed: 4, passed: 2, failed: 1, skipped: 1,
    aborted: 0, notRun: 0, incomplete: 0, pending: 0, running: 0, scenarios: [] },
  cleanupEvidence: { status: "SUCCESS", attempted: 2, confirmedDeleted: 2, retainedObjects: [] },
  executionIssues: [],
};
describe("Evidence-based run outcome", () => {
  it("uses the Server conclusion without overriding it from the raw historical status", () => {
    expect(outcomePresentation({ ...finishedRun, status: "FAILED", executionOutcome: "COMPLETED" }).label).toBe("Completed");
    expect(outcomePresentation({ ...finishedRun, executionOutcome: "ABNORMAL" }).label).toBe("Ended abnormally");
  });
  it("distinguishes verified case failures from an execution exception, without rewriting historical generic errors", () => {
    expect(completedWithFailures(verifiedFailure)).toBe(true);
    const legacy = { ...verifiedFailure, failureReason: "WORKER_EXECUTION_FAILED" };
    expect(completedWithFailures(legacy)).toBe(true);
    expect(legacyCaseFailureReport(legacy)).toBe(true);
    expect(outcomePresentation(legacy).label).toBe("Completed · unsuccessful cases");
    expect(failureExplanation(legacy)).toBe("Execution completed; some cases did not pass. Review the unsuccessful cases for details.");
    expect(outcomePresentation(verifiedFailure)).toEqual({ label: "Completed · unsuccessful cases", tone: "warning" });
  });
  it.each<Partial<Run>>([
    { workerExitCode: 7 }, { workerReportValidated: false }, { executionIssues: undefined }, { workerReportValidated: undefined },
    { workerTerminationReason: "CONTAINER_FAILURE" }, { failureReason: "REPORT_INCONSISTENT" },
    { cleanupEvidence: { status: "FAILED" } }, { executionIssues: [{ phase: "CLEANUP", message: "Failed" }] },
    { accounting: { ...verifiedFailure.accounting!, incomplete: 1 } },
    { accounting: { ...verifiedFailure.accounting!, notRun: 1 } },
  ])("does not infer successful execution from complete counts when proof is missing: %j", change => {
    expect(completedWithFailures({ ...verifiedFailure, ...change })).toBe(false);
  });
  it("keeps non-allowed aborted cases visible after normal task completion", () => {
    expect(completedWithFailures({ ...verifiedFailure, status: "SUCCEEDED", failed: 0,
      executionOutcome: "COMPLETED", accounting: { ...verifiedFailure.accounting!, aborted: 1 } })).toBe(true);
    expect(completedWithFailures({ ...verifiedFailure, status: "SUCCEEDED", failed: 0,
      executionOutcome: "COMPLETED", workerTerminationReason: "COMPLETED",
      accounting: { ...verifiedFailure.accounting!, aborted: 1 } })).toBe(false);
  });
  it("keeps an unknown cause unknown", () => {
    expect(failureExplanation({ ...verifiedFailure, failureReason: "UNRECOGNIZED_REASON" })).toContain("not yet confirmed");
  });
  it("translates recorded phases without guessing missing or unknown phases", () => {
    expect(failurePhaseLabel("PREFLIGHT")).toBe("Preflight checks");
    expect(failurePhaseLabel(undefined)).toBe("Not recorded");
    expect(failurePhaseLabel("OTHER_PHASE")).toBe("Phase unknown");
  });
  it("100 percent processed does not finish a running or reporting task", () => {
    expect(lifecycleLabel({ ...finishedRun, status: "RUNNING" })).toBe("Running");
    expect(lifecycleLabel({ ...finishedRun, status: "GENERATING_REPORT" })).toBe("Generating report");
    expect(lifecycleLabel({ ...finishedRun, status: "CANCELLED" })).toBe("Ended");
    expect(outcomePresentation(finishedRun).tone).toBe("success");
  });
});
