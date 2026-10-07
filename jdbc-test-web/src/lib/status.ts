import type {
  ConnectionTestStatus,
  DriverStatus,
  ResultStatus,
  RunStatus,
} from "@/types/api";
export type Tone = "active" | "success" | "danger" | "neutral" | "warning";
export const runPresentation: Record<RunStatus, { label: string; tone: Tone }> =
  {
    QUEUED: { label: "Queued", tone: "neutral" },
    PREPARING: { label: "Preparing", tone: "active" },
    PROBING: { label: "Probing", tone: "active" },
    RUNNING: { label: "Running", tone: "active" },
    GENERATING_REPORT: { label: "Generating report", tone: "active" },
    SUCCEEDED: { label: "Succeeded", tone: "success" },
    FAILED: { label: "Failed", tone: "danger" },
    CANCELLED: { label: "Cancelled", tone: "neutral" },
    INTERRUPTED: { label: "Interrupted", tone: "warning" },
  };
export const driverPresentation: Record<
  DriverStatus,
  { label: string; tone: Tone }
> = {
  PENDING_VALIDATION: { label: "Pending validation", tone: "warning" },
  VALIDATED: { label: "Validated", tone: "success" },
  INVALID: { label: "Invalid", tone: "danger" },
};
export const resultPresentation: Record<
  ResultStatus,
  { label: string; tone: Tone }
> = {
  PASSED: { label: "Passed", tone: "success" },
  FAILED: { label: "Failed", tone: "danger" },
  SKIPPED: { label: "Skipped", tone: "neutral" },
  ABORTED: { label: "Aborted", tone: "warning" },
};
export const connectionPresentation: Record<
  ConnectionTestStatus,
  { label: string; tone: Tone }
> = {
  SUCCEEDED: { label: "Last test succeeded", tone: "success" },
  FAILED: { label: "Last test failed", tone: "danger" },
};
export function isTerminal(status: RunStatus) {
  return ["SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"].includes(status);
}
