import { act, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

const chart = vi.hoisted(() => ({
  setOption: vi.fn(),
  resize: vi.fn(),
  dispose: vi.fn(),
}));
const init = vi.hoisted(() => vi.fn(() => chart));
vi.mock("echarts/core", () => ({ use: vi.fn(), init }));
vi.unmock("@/components/outcome-chart");
import OutcomeChart from "./outcome-chart";

describe("ECharts lifecycle", () => {
  it("renders genuine PASSED/FAILED/SKIPPED/ABORTED outcomes with accessible text", () => {
    render(<OutcomeChart passed={3} failed={2} skipped={1} aborted={4} />);
    expect(screen.getByRole("img")).toHaveAccessibleName(
      "Outcomes: 3 passed, 2 failed, 1 skipped, 4 aborted",
    );
    expect(chart.setOption).toHaveBeenCalledWith(
      expect.objectContaining({
        tooltip: { trigger: "item", renderMode: "richText" },
        series: [
          expect.objectContaining({
            data: [
              { name: "Passed", value: 3 },
              { name: "Failed", value: 2 },
              { name: "Skipped", value: 1 },
              { name: "Aborted", value: 4 },
            ],
          }),
        ],
      }),
    );
  });
  it("resizes and disposes the chart and disconnects its observer on unmount", () => {
    let resize: (() => void) | undefined;
    const disconnect = vi.fn();
    vi.stubGlobal(
      "ResizeObserver",
      class {
        constructor(callback: () => void) {
          resize = callback;
        }
        observe() {}
        disconnect = disconnect;
      },
    );
    const view = render(
      <OutcomeChart passed={3} failed={0} skipped={0} aborted={0} />,
    );
    act(() => resize?.());
    expect(chart.resize).toHaveBeenCalledOnce();
    view.unmount();
    expect(chart.dispose).toHaveBeenCalledOnce();
    expect(disconnect).toHaveBeenCalledOnce();
  });
});
