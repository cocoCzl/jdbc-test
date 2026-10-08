import i18n from "@/i18n";
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
        tooltip: expect.objectContaining({ trigger: "item", renderMode: "richText" }),
        series: [
          expect.objectContaining({
            data: [
              expect.objectContaining({ name: "Passed", value: 3 }),
              expect.objectContaining({ name: "Failed", value: 2 }),
              expect.objectContaining({ name: "Skipped", value: 1 }),
              expect.objectContaining({ name: "Aborted", value: 4 }),
            ],
          }),
        ],
      }),
    );
  });
  it("keeps explicit skip color when Passed is zero, and displays unknown values honestly", () => {
    document.documentElement.style.setProperty("--neutral", "#5b6c83");
    render(<OutcomeChart passed={0} failed={null} skipped={4} aborted={null} />);
    expect(screen.getByRole("img")).toHaveAccessibleName("Outcomes: 0 passed, unknown failed, 4 skipped, unknown aborted");
    expect(screen.getByText("Known outcomes")).toBeInTheDocument();
    expect(screen.getAllByText("—")).toHaveLength(2);
    expect(chart.setOption).toHaveBeenLastCalledWith(expect.objectContaining({series: [expect.objectContaining({data: [expect.objectContaining({name: "Skipped", value: 4, itemStyle: {color: "#5b6c83"}})]})]}));
  });
  it("does not replace an entirely unknown distribution with zero", () => {
    render(<OutcomeChart passed={null} failed={null} skipped={null} aborted={null} />);
    expect(screen.getAllByText("—")).toHaveLength(5);
    expect(screen.queryByText("0")).not.toBeInTheDocument();
  });
  it("updates localized legends, tooltip and center text without recreating the chart", async () => {
    render(<OutcomeChart passed={1234} failed={2} skipped={1} aborted={4} />);
    const creations = init.mock.calls.length;
    await act(async () => { await i18n.changeLanguage("zh-CN"); });
    expect(init.mock.calls).toHaveLength(creations);
    expect(screen.getByText("通过")).toBeVisible();
    expect(screen.getByText("已记录结果")).toBeVisible();
    const option = chart.setOption.mock.calls.at(-1)![0];
    expect(option.animationDurationUpdate).toBe(0);
    expect(option.series[0].data.map((item: {id: string}) => item.id)).toEqual(["passed", "failed", "skipped", "aborted"]);
    expect(option.series[0].data.map((item: {name: string}) => item.name)).toEqual(["通过", "失败", "已跳过", "已中止"]);
    expect(option.tooltip.formatter({name: "通过", value: 1234})).toBe("通过: 1,234");
    expect(option.series[0].data.map((item: {value: number}) => item.value)).toEqual([1234, 2, 1, 4]);
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
