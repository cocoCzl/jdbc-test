import i18n from "@/i18n";
import type {
  ConnectionTestStatus,
  DriverStatus,
  ResultStatus,
  RunStatus,
  RunStatusGroup,
} from "@/types/api";
export type Tone = "active" | "success" | "danger" | "neutral" | "warning";
// History/presentation groups only. Execution controls continue to use RunStatus.
export const runStatusGroups: Record<RunStatusGroup, { statuses: readonly RunStatus[]; label: string; tone: Tone }> = {
  WAITING: { statuses: ["QUEUED"], get label() { return i18n.t("run.group.waiting"); }, tone: "neutral" },
  IN_PROGRESS: { statuses: ["PREPARING", "PROBING", "RUNNING", "GENERATING_REPORT"], get label() { return i18n.t("run.group.inProgress"); }, tone: "active" },
  COMPLETED: { statuses: ["SUCCEEDED"], get label() { return i18n.t("run.succeeded"); }, tone: "success" },
  ABNORMAL: { statuses: ["FAILED", "INTERRUPTED"], get label() { return i18n.t("run.group.abnormal"); }, tone: "danger" },
  CANCELLED: { statuses: ["CANCELLED"], get label() { return i18n.t("ui.cancelled"); }, tone: "neutral" },
};
export const runStatusGroupOrder: readonly RunStatusGroup[] = ["WAITING", "IN_PROGRESS", "COMPLETED", "ABNORMAL", "CANCELLED"];

export function getRunPresentation(status: RunStatus) {
  return Object.values(runStatusGroups).find(group => group.statuses.includes(status))
    ?? { label: status, tone: "neutral" as const };
}

export function runStageLabel(status: RunStatus) {
  switch (status) {
    case "PREPARING": return i18n.t("run.stage.preparing");
    case "PROBING": return i18n.t("run.stage.probing");
    case "RUNNING": return i18n.t("run.stage.running");
    case "GENERATING_REPORT": return i18n.t("run.stage.report");
    default: return undefined;
  }
}

// Internal status labels/recognition retained for protocol compatibility.
export const runPresentation: Record<RunStatus, { label: string; tone: Tone }> =
  {
    QUEUED: { get label() { return i18n.t("ui.queued"); }, tone: "neutral" },
    PREPARING: { get label() { return i18n.t("ui.preparing"); }, tone: "active" },
    PROBING: { get label() { return i18n.t("ui.probing"); }, tone: "active" },
    RUNNING: { get label() { return i18n.t("ui.running"); }, tone: "active" },
    GENERATING_REPORT: { get label() { return i18n.t("ui.generatingReport"); }, tone: "active" },
    SUCCEEDED: { get label() { return i18n.t("run.succeeded"); }, tone: "success" },
    FAILED: { get label() { return i18n.t("run.failed"); }, tone: "danger" },
    CANCELLED: { get label() { return i18n.t("ui.cancelled"); }, tone: "neutral" },
    INTERRUPTED: { get label() { return i18n.t("ui.interrupted"); }, tone: "warning" },
  };
export const driverPresentation: Record<
  DriverStatus,
  { label: string; tone: Tone }
> = {
  PENDING_VALIDATION: { get label() { return i18n.t("ui.pendingValidation"); }, tone: "warning" },
  VALIDATED: { get label() { return i18n.t("ui.validated"); }, tone: "success" },
  INVALID: { get label() { return i18n.t("ui.invalid"); }, tone: "danger" },
};
export const resultPresentation: Record<
  ResultStatus,
  { label: string; tone: Tone }
> = {
  PASSED: { get label() { return i18n.t("ui.passed"); }, tone: "success" },
  FAILED: { get label() { return i18n.t("ui.failed"); }, tone: "danger" },
  SKIPPED: { get label() { return i18n.t("ui.skipped"); }, tone: "neutral" },
  ABORTED: { get label() { return i18n.t("ui.aborted"); }, tone: "warning" },
};
export const connectionPresentation: Record<
  ConnectionTestStatus,
  { label: string; tone: Tone }
> = {
  SUCCEEDED: { get label() { return i18n.t("ui.lastTestSucceeded"); }, tone: "success" },
  FAILED: { get label() { return i18n.t("ui.lastTestFailed"); }, tone: "danger" },
};
export function isTerminal(status: RunStatus) {
  return ["SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"].includes(status);
}
