import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";
import { TestEventSource } from "./test-event-source";
import { createElement } from "react";

vi.stubGlobal("EventSource", TestEventSource);
vi.stubGlobal(
  "ResizeObserver",
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
);
vi.mock("@/components/outcome-chart", () => ({
  default: ({
    passed,
    failed,
    skipped,
    aborted = 0,
  }: {
    passed: number;
    failed: number;
    skipped: number;
    aborted?: number;
  }) =>
    createElement(
      "div",
      { "aria-label": "Outcome chart" },
      `${passed} / ${failed} / ${skipped} / ${aborted}`,
    ),
}));

afterEach(() => {
  cleanup();
  TestEventSource.instances = [];
  vi.restoreAllMocks();
  localStorage.clear();
  sessionStorage.clear();
});
