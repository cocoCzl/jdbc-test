import type { Run } from "@/types/api";

// Preserve the legacy REST count definition; missing evidence stays unknown.
export function abortedCount(run: Run): number | null {
  if (run.accounting?.aborted != null) return run.accounting.aborted;
  const values = [run.completedCases, run.passed, run.failed, run.skipped];
  return values.every((value) => typeof value === "number" && Number.isFinite(value))
    ? Math.max(0, run.completedCases - run.passed - run.failed - run.skipped)
    : null;
}
export function knownSum(values: (number | null | undefined)[]): number | null {
  return values.every((value) => typeof value === "number" && Number.isFinite(value))
    ? (values as number[]).reduce((sum, value) => sum + value, 0)
    : null;
}
