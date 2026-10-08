import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import * as echarts from "echarts/core";
import { PieChart } from "echarts/charts";
import { AriaComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import { formatNumber } from "@/lib/format";
echarts.use([PieChart, AriaComponent, TooltipComponent, CanvasRenderer]);

export default function OutcomeChart({ passed, failed, skipped, aborted }: {
  passed: number | null; failed: number | null; skipped: number | null; aborted: number | null;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.resolvedLanguage;
  const container = useRef<HTMLDivElement>(null);
  const instance = useRef<ReturnType<typeof echarts.init> | null>(null);
  const values = { passed, failed, skipped, aborted };
  const description = t("chart.description", Object.fromEntries(Object.entries(values).map(([key, value]) => [key, value == null ? t("common.unknown") : formatNumber(value)])));
  useEffect(() => {
    if (!container.current) return;
    const chart = echarts.init(container.current);
    instance.current = chart;
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(container.current);
    return () => { observer.disconnect(); chart.dispose(); instance.current = null; };
  }, []);
  useEffect(() => {
    if (!instance.current) return;
    const style = getComputedStyle(document.documentElement);
    const colors = ["--success", "--danger", "--neutral", "--warning"].map(token => style.getPropertyValue(token).trim());
    instance.current.setOption({
      aria: { enabled: true, label: { description } },
      color: colors,
      // Text changes must not replay the outcome distribution's animation.
      animationDurationUpdate: 0,
      tooltip: { trigger: "item", renderMode: "richText", formatter: (item: {name: string; value: number}) => `${item.name}: ${formatNumber(item.value)}` },
      series: [{ type: "pie", radius: ["70%", "88%"], center: ["50%", "50%"], padAngle: 3,
        itemStyle: { borderRadius: 6 }, label: { show: false },
        data: [
          { id: "passed", name: t("ui.passed"), value: passed }, { id: "failed", name: t("ui.failed"), value: failed },
          { id: "skipped", name: t("ui.skipped"), value: skipped }, { id: "aborted", name: t("ui.aborted"), value: aborted },
        ].map((item, index) => ({...item, itemStyle: {color: colors[index]}})).filter(item => item.value != null && item.value > 0),
      }],
    });
  }, [passed, failed, skipped, aborted, locale, t, description]);
  const entries = [
    {id: "passed", label: t("ui.passed"), value: passed, color: "text-success"},
    {id: "failed", label: t("ui.failed"), value: failed, color: "text-destructive"},
    {id: "skipped", label: t("ui.skipped"), value: skipped, color: "text-neutral"},
    {id: "aborted", label: t("ui.aborted"), value: aborted, color: "text-warning"},
  ];
  const known = entries.filter(entry => entry.value != null);
  const total = known.reduce((sum, entry) => sum + entry.value!, 0);
  return <div>
    <div className="relative mx-auto max-w-64">
      <div ref={container} className="h-44 w-full" role="img" aria-label={description} />
      <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center" aria-hidden="true">
        <strong className="text-3xl tabular-nums">{known.length ? formatNumber(total) : "—"}</strong>
        <span className="text-xs text-muted-foreground">{known.length === 4 ? t("ui.recordedOutcomes") : t("ui.knownOutcomes")}</span>
      </div>
    </div>
    <dl className="grid grid-cols-2 gap-x-5 gap-y-2 text-sm" aria-label={t("ui.outcomeCounts")}>
      {entries.map(({id, label, value, color}) => <div key={id} className="flex items-center justify-between gap-2">
        <dt className={`flex items-center gap-2 ${value != null && value > 0 ? color : "text-muted-foreground"}`}><span className="size-2 rounded-full bg-current" />{label}</dt>
        <dd className={value != null && value > 0 ? "font-semibold tabular-nums" : "text-muted-foreground tabular-nums"}>{formatNumber(value)}</dd>
      </div>)}
    </dl>
  </div>;
}
