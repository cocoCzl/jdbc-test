import { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { runsKeys } from "@/api/runs";
import { isTerminal, runPresentation } from "@/lib/status";
import type { Run, RunStatus } from "@/types/api";
const eventNames = [
  "run.snapshot",
  "run.status",
  "module.started",
  "case.started",
  "case.finished",
  "run.progress",
  "run.issue",
  "run.cleanup",
  "run.finished",
  "run.failed",
  "run.cancelled",
];
function record(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}
function status(value: unknown): value is RunStatus {
  return typeof value === "string" && Object.hasOwn(runPresentation, value);
}
export function useRunEvents(id: string, run: Run | undefined) {
  const client = useQueryClient();
  const enabled = !!run && !isTerminal(run.status);
  const [connection, setConnection] = useState<
    "connecting" | "live" | "reconnecting"
  >("connecting");
  useEffect(() => {
    if (!enabled) return;
    const source = new EventSource(
      `${import.meta.env.VITE_API_BASE_URL ?? "/api"}/runs/${id}/events`,
    );
    source.onopen = () => setConnection("live");
    source.onerror = () => setConnection("reconnecting");
    const receive = (event: MessageEvent<string>) => {
      let data: unknown;
      try {
        data = JSON.parse(event.data);
      } catch {
        void client.invalidateQueries({ queryKey: runsKeys.detail(id) });
        return;
      }
      if (
        !record(data) ||
        (data.runId !== undefined && data.runId !== id) ||
        (data.id !== undefined && data.id !== id)
      )
        return;
      const body = data;
      const runStatus = event.type === "run.status" && status(body.status)
        ? body.status : undefined;
      const terminalStatus =
        event.type === "run.finished"
          ? "SUCCEEDED"
          : event.type === "run.failed"
            ? "FAILED"
            : event.type === "run.cancelled"
              ? "CANCELLED"
              : undefined;
      client.setQueryData<Run>(runsKeys.detail(id), (previous) => {
        if (!previous || isTerminal(previous.status)) return previous;
        if (event.type === "run.snapshot") {
          if (body.id !== id || !status(body.status)) return previous;
          return {
            ...previous,
            ...Object.fromEntries(
              [...Object.keys(previous), "accounting", "executionScope", "executionIssues", "cleanupEvidence"]
                .filter((key) => key in body)
                .map((key) => [key, body[key]]),
            ),
          } as Run;
        }
        const next = { ...previous };
        if (body.accounting && typeof body.accounting === "object")
          next.accounting = body.accounting as Run["accounting"];
        if (event.type === "run.cleanup" && record(body.cleanupEvidence))
          next.cleanupEvidence = body.cleanupEvidence as unknown as Run["cleanupEvidence"];
        if (event.type === "run.issue") next.executionIssues = [...(previous.executionIssues ?? []), body];
        if (runStatus) next.status = runStatus;
        if (terminalStatus) next.status = terminalStatus;
        for (const key of [
          "completedCases",
          "totalCases",
          "passed",
          "failed",
          "skipped",
        ] as const)
          if (typeof body[key] === "number" && Number.isFinite(body[key]))
            next[key] = body[key];
        if (
          event.type === "module.started" &&
          typeof body.moduleId === "string"
        )
          next.currentModule = body.moduleId;
        if (
          event.type === "case.started" &&
          typeof body.scenarioId === "string"
        )
          next.currentTestCase = body.scenarioId;
        if (typeof body.reason === "string") next.failureReason = body.reason;
        if (isTerminal(next.status)) {
          next.currentModule = null;
          next.currentTestCase = null;
        }
        return next;
      });
      if (event.type === "case.finished")
        void client.invalidateQueries({ queryKey: runsKeys.results(id) });
      if (terminalStatus || (runStatus && isTerminal(runStatus))) {
        source.close();
        void client.invalidateQueries({ queryKey: runsKeys.all });
      }
    };
    for (const name of eventNames)
      source.addEventListener(name, receive as EventListener);
    return () => {
      for (const name of eventNames)
        source.removeEventListener(name, receive as EventListener);
      source.close();
    };
  }, [client, enabled, id]);
  return enabled ? connection : "closed";
}
