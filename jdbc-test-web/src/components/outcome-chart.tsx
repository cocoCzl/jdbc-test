import { useEffect, useRef } from "react";
import * as echarts from "echarts/core";
import { PieChart } from "echarts/charts";
import {
  AriaComponent,
  TooltipComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
echarts.use([
  PieChart,
  AriaComponent,
  TooltipComponent,
  CanvasRenderer,
]);
export default function OutcomeChart({
  passed,
  failed,
  skipped,
  aborted,
}: {
  passed: number | null;
  failed: number | null;
  skipped: number | null;
  aborted: number | null;
}) {
  const container = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!container.current) return;
    const chart = echarts.init(container.current);
    const style = getComputedStyle(document.documentElement);
    chart.setOption({
      aria: { enabled: true },
      color: ["--success", "--danger", "--neutral", "--warning"].map((token) => style.getPropertyValue(token).trim()),
      tooltip: { trigger: "item", renderMode: "richText" },
      series: [
        {
          type: "pie",
          radius: ["70%", "88%"],
          center: ["50%", "50%"],
          padAngle: 3,
          itemStyle: { borderRadius: 6 },
          label: { show: false },
          data: [
            { name: "Passed", value: passed },
            { name: "Failed", value: failed },
            { name: "Skipped", value: skipped },
            { name: "Aborted", value: aborted },
          ].map((item, index) => ({...item, itemStyle: {color: style.getPropertyValue(["--success", "--danger", "--neutral", "--warning"][index]).trim()}})).filter((item) => item.value != null && item.value > 0),
        },
      ],
    });
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(container.current);
    return () => { observer.disconnect(); chart.dispose(); };
  }, [passed, failed, skipped, aborted]);
  const entries = [
    { label: "Passed", value: passed, color: "text-success" },
    { label: "Failed", value: failed, color: "text-destructive" },
    { label: "Skipped", value: skipped, color: "text-neutral" },
    { label: "Aborted", value: aborted, color: "text-warning" },
  ];
  const known = entries.filter((entry) => entry.value != null);
  const total = known.reduce((sum, entry) => sum + entry.value!, 0);
  return (
    <div>
      <div className="relative mx-auto max-w-64">
        <div ref={container} className="h-44 w-full" role="img"
          aria-label={`Outcomes: ${passed ?? "unknown"} passed, ${failed ?? "unknown"} failed, ${skipped ?? "unknown"} skipped, ${aborted ?? "unknown"} aborted`} />
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center" aria-hidden="true">
          <strong className="text-3xl tabular-nums">{known.length ? total : "—"}</strong>
          <span className="text-xs text-muted-foreground">{known.length === 4 ? "Recorded outcomes" : "Known outcomes"}</span>
        </div>
      </div>
      <dl className="grid grid-cols-2 gap-x-5 gap-y-2 text-sm" aria-label="Outcome counts">
        {entries.map(({ label, value, color }) => <div key={label} className="flex items-center justify-between gap-2">
          <dt className={`flex items-center gap-2 ${value != null && value > 0 ? color : "text-muted-foreground"}`}><span className="size-2 rounded-full bg-current" />{label}</dt>
          <dd className={value != null && value > 0 ? "font-semibold tabular-nums" : "text-muted-foreground tabular-nums"}>{value ?? "—"}</dd>
        </div>)}
      </dl>
    </div>
  );
}
