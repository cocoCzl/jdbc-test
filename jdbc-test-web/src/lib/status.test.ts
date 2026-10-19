import { describe, expect, it } from "vitest";
import { getRunPresentation, isTerminal, runStageLabel, runStatusGroups } from "./status";
import type { RunStatus } from "@/types/api";

describe("Run lifecycle presentation is independent of execution controls", () => {
  it.each([
    ["QUEUED", "Waiting", false],
    ["PREPARING", "In progress", false],
    ["PROBING", "In progress", false],
    ["RUNNING", "In progress", false],
    ["GENERATING_REPORT", "In progress", false],
    ["SUCCEEDED", "Completed", true],
    ["FAILED", "Ended abnormally", true],
    ["INTERRUPTED", "Ended abnormally", true],
    ["CANCELLED", "Cancelled", true],
  ] as const)("%s has its presentation label without changing terminal semantics", (status, label, terminal) => {
    expect(getRunPresentation(status).label).toBe(label);
    expect(isTerminal(status)).toBe(terminal);
  });
  it("covers every internal status once and retains explicit stages", () => {
    const states = Object.values(runStatusGroups).flatMap(group => group.statuses);
    expect(states).toHaveLength(9);
    expect(new Set(states).size).toBe(9);
    expect(runStageLabel("PROBING")).toBe("Probing connection");
    expect(runStageLabel("SUCCEEDED")).toBeUndefined();
    expect(getRunPresentation("FUTURE_STATE" as RunStatus)).toEqual({ label: "FUTURE_STATE", tone: "neutral" });
  });
});
