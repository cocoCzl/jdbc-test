import i18n from "@/i18n";
import type {
  ConnectionTestStatus,
  DriverStatus,
  ResultStatus,
  RunStatus,
} from "@/types/api";
export type Tone = "active" | "success" | "danger" | "neutral" | "warning";
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
