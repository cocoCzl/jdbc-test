import { describe, expect, it } from "vitest";
import { getRunPresentation, isTerminal, runPresentation, runStageLabel, runStatusGroupOrder, runStatusGroups } from "./status";
import type { RunStatus } from "@/types/api";

describe("Run lifecycle presentation is independent of execution controls", () => {
  it.each([
    ["QUEUED", "Waiting", false],
    ["PREPARING", "Running", false],
    ["PROBING", "Running", false],
    ["RUNNING", "Running", false],
    ["GENERATING_REPORT", "Running", false],
    ["SUCCEEDED", "Completed", true],
    ["FAILED", "Ended abnormally", true],
    ["INTERRUPTED", "Ended abnormally", true],
    ["CANCELLED", "Cancelled", true],
  ] as const)("%s has its presentation label without changing terminal semantics", (status, label, terminal) => {
    expect(getRunPresentation(status).label).toBe(label);
    expect(isTerminal(status)).toBe(terminal);
  });
  it("covers every internal status once and retains explicit stages", () => {
    const states = runStatusGroupOrder.flatMap(key => runStatusGroups[key].statuses);
    expect([...states].sort()).toEqual(Object.keys(runPresentation).sort());
    expect(runStageLabel("PROBING")).toBe("Probing connection");
    expect(runStageLabel("SUCCEEDED")).toBeUndefined();
    expect(getRunPresentation("FUTURE_STATE" as RunStatus)).toEqual({ label: "FUTURE_STATE", tone: "neutral" });
  });
  it("keeps legacy aggregates separate from display groups and aligned with terminal semantics", () => {
    expect(runStatusGroupOrder).not.toContain("ACTIVE");
    expect(runStatusGroupOrder).not.toContain("ENDED");
    const active = runStatusGroups.ACTIVE.statuses;
    const ended = runStatusGroups.ENDED.statuses;
    expect([...active, ...ended].sort()).toEqual(Object.keys(runPresentation).sort());
    for (const status of active) expect(isTerminal(status)).toBe(false);
    for (const status of ended) expect(isTerminal(status)).toBe(true);
  });
});
