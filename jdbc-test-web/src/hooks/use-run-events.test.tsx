import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { runsKeys } from "@/api/runs";
import { finishedRun, run } from "@/test/fixtures";
import { TestEventSource } from "@/test/test-event-source";
import { useRunEvents } from "./use-run-events";

function setup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  client.setQueryData(runsKeys.detail(run.id), run);
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const hook = renderHook(() => useRunEvents(run.id, run), { wrapper });
  return { ...hook, client, source: TestEventSource.instances[0] };
}
describe("EventSource lifecycle", () => {
  it("uses the events endpoint and restores a run.snapshot", () => {
    const { source, client } = setup();
    expect(source.url).toBe("/api/runs/run-1/events");
    act(() => source.emit("run.snapshot", { ...run, completedCases: 2 }));
    expect(client.getQueryData(runsKeys.detail(run.id))).toMatchObject({
      completedCases: 2,
    });
  });
  it("handles status, module, case and progress events in the single Query cache", () => {
    const { source, client } = setup();
    act(() => {
      source.emit("run.status", { runId: run.id, status: "GENERATING_REPORT" });
      source.emit("module.started", { runId: run.id, moduleId: "statement" });
      source.emit("case.started", {
        runId: run.id,
        scenarioId: "statement.execute",
      });
      source.emit("run.progress", {
        runId: run.id,
        completedCases: 3,
        passed: 1,
        failed: 1,
        skipped: 1,
      });
    });
    expect(client.getQueryData(runsKeys.detail(run.id))).toMatchObject({
      status: "GENERATING_REPORT",
      currentModule: "statement",
      currentTestCase: "statement.execute",
      completedCases: 3,
    });
  });
  it("invalidates paginated results when a case finishes", () => {
    const { source, client } = setup();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    act(() =>
      source.emit("case.finished", {
        runId: run.id,
        scenarioId: "statement.execute",
      }),
    );
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: runsKeys.results(run.id),
    });
  });
  it.each([
    ["run.finished", "SUCCEEDED"],
    ["run.failed", "FAILED"],
    ["run.cancelled", "CANCELLED"],
  ])("closes on %s and never reverses terminal state", (event, status) => {
    const { source, client } = setup();
    act(() => source.emit(event, { runId: run.id, reason: "sanitized" }));
    expect(source.closed).toBe(true);
    act(() => source.emit("run.status", { runId: run.id, status: "RUNNING" }));
    expect(client.getQueryData(runsKeys.detail(run.id))).toMatchObject({
      status,
      currentModule: null,
      currentTestCase: null,
    });
  });
  it("reports reconnecting without making Run failed", () => {
    const { source, client, result } = setup();
    act(() => source.onopen?.());
    expect(result.current).toBe("live");
    act(() => source.onerror?.());
    expect(result.current).toBe("reconnecting");
    expect(source.closed).toBe(false);
    expect(client.getQueryData(runsKeys.detail(run.id))).toMatchObject({
      status: "RUNNING",
    });
  });
  it("ignores wrong Run IDs and unrecognized sensitive fields", () => {
    const { source, client } = setup();
    act(() => {
      source.emit("run.progress", {
        runId: "another-run",
        completedCases: 100,
      });
      source.emit("run.snapshot", {
        ...run,
        password: "private",
        workspacePath: "/private/run",
      });
    });
    const cached = client.getQueryData(runsKeys.detail(run.id));
    expect(cached).toMatchObject({ completedCases: 0 });
    expect(cached).not.toHaveProperty("password");
    expect(cached).not.toHaveProperty("workspacePath");
  });
  it("removes listeners and closes on unmount", () => {
    const { source, client, unmount } = setup();
    unmount();
    expect(source.closed).toBe(true);
    source.emit("run.progress", { runId: run.id, completedCases: 9 });
    expect(client.getQueryData(runsKeys.detail(run.id))).toMatchObject({
      completedCases: 0,
    });
  });
  it("does not subscribe to terminal runs", () => {
    const client = new QueryClient();
    renderHook(() => useRunEvents(run.id, finishedRun), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    });
    expect(TestEventSource.instances).toHaveLength(0);
  });
});
