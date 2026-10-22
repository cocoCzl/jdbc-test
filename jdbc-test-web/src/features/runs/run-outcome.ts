import i18n from "@/i18n";
import type { Run } from "@/types/api";
import { getRunPresentation, isTerminal, runPresentation, runStatusGroups } from "@/lib/status";

// A full result count alone cannot prove that execution and report processing succeeded.
function validatedCaseFailureReport(run: Run) {
  return run.status === "FAILED"
    && run.workerReportValidated === true && run.workerTerminationReason === "TEST_FAILURE"
    && run.cleanupEvidence?.status === "SUCCESS" && Array.isArray(run.executionIssues) && run.executionIssues.length === 0
    && !!run.accounting && run.accounting.notRun === 0 && run.accounting.incomplete === 0
    && run.accounting.started > 0 && run.accounting.selected === run.totalCases
    && run.accounting.completed === run.accounting.selected
    && (run.workerExitCode == null || run.workerExitCode === 4);
}
export function completedWithFailures(run: Run) {
  const unsuccessful = run.failed > 0 || (run.workerTerminationReason === "TEST_FAILURE"
    && (run.accounting?.aborted ?? 0) > 0);
  if (run.executionOutcome) return run.executionOutcome === "COMPLETED" && unsuccessful;
  if (run.status === "SUCCEEDED") return unsuccessful;
  // Compatibility for protocol 2 servers: require validated accounting and independent evidence.
  return ["TEST_FAILURE", "WORKER_EXECUTION_FAILED"].includes(run.failureReason ?? "")
    && validatedCaseFailureReport(run);
}
export function legacyCaseFailureReport(run: Run) {
  // Old generic codes lost the actual exit code: report completeness cannot recover it.
  return run.failureReason === "WORKER_EXECUTION_FAILED" && validatedCaseFailureReport(run);
}
export function outcomePresentation(run: Run) {
  return completedWithFailures(run)
    ? { label: i18n.t("record.withFailures"), tone: "warning" as const }
    : run.executionOutcome ? runStatusGroups[run.executionOutcome] : getRunPresentation(run.status);
}
export function lifecycleLabel(run: Run) {
  return isTerminal(run.status) ? i18n.t("record.ended") : runPresentation[run.status].label;
}
export function failureExplanation(run: Run) {
  if (completedWithFailures(run)) return i18n.t("record.testFailureExplanation");
  if (!run.failureReason && !run.executionIssues?.length) return null;
  const reason = run.failureReason === "WORKER_EXECUTION_FAILED"
    ? run.workerTerminationReason ?? run.failureReason : run.failureReason ?? run.workerTerminationReason;
  const specific = `record.reason.${reason}`;
  return i18n.exists(specific) ? i18n.t(specific) : i18n.t("record.unknownFailure");
}

export function failurePhaseLabel(value: unknown) {
  if (typeof value !== "string" || !value) return i18n.t("record.notRecorded");
  const key = `record.phase.${value}`;
  return i18n.exists(key) ? i18n.t(key) : i18n.t("record.unknownPhase");
}
