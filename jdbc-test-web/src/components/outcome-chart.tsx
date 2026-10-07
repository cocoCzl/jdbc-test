import { useEffect, useRef } from "react";
import * as echarts from "echarts/core";
import { PieChart } from "echarts/charts";
import {
  AriaComponent,
  LegendComponent,
  TooltipComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
echarts.use([
  PieChart,
  AriaComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
]);
export default function OutcomeChart({
  passed,
  failed,
  skipped,
  aborted,
}: {
  passed: number;
  failed: number;
  skipped: number;
  aborted: number;
}) {
  const container = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!container.current) return;
    const chart = echarts.init(container.current);
    chart.setOption({
      aria: { enabled: true },
      color: ["#14836b", "#be3847", "#9da8b8", "#c18a30"],
      tooltip: { trigger: "item", renderMode: "richText" },
      legend: {
        bottom: 0,
        icon: "circle",
        textStyle: { color: "#69778a", fontFamily: "DM Sans" },
      },
      series: [
        {
          type: "pie",
          radius: ["58%", "78%"],
          center: ["50%", "44%"],
          padAngle: 3,
          itemStyle: { borderRadius: 6 },
          label: { show: false },
          data: [
            { name: "Passed", value: passed },
            { name: "Failed", value: failed },
            { name: "Skipped", value: skipped },
            { name: "Aborted", value: aborted },
          ].filter((item) => item.value > 0),
        },
      ],
    });
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(container.current);
    return () => {
      observer.disconnect();
      chart.dispose();
    };
  }, [passed, failed, skipped, aborted]);
  return (
    <div
      ref={container}
      className="h-64 w-full"
      role="img"
      aria-label={`Outcomes: ${passed} passed, ${failed} failed, ${skipped} skipped, ${aborted} aborted`}
    />
  );
}
