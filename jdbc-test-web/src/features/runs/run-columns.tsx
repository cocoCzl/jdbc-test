import { moduleName, profileName } from "@/lib/presentation";
import { t } from "i18next";
import { Link } from "react-router-dom";
import type { ColumnDef } from "@tanstack/react-table";
import type { Run } from "@/types/api";
import { StatusBadge } from "@/components/common";
import { abortedCount } from "@/lib/outcomes";
import { isTerminal, runPresentation } from "@/lib/status";
import { formatDateTime, formatDuration, formatNumber, shortId } from "@/lib/format";
export function getRunColumns(): ColumnDef<Run>[] {
  return [
  {
      id: "ui.runStatus",
    get header() { return t("ui.runStatus"); },
    cell: ({ row }) => (
      <div className="min-w-36 max-w-56 space-y-2">
        <Link to={`/runs/${row.original.id}`} className="subtle-link block rounded-sm font-semibold" title={t("common.openRun", {id: row.original.id})}>
          {row.original.profile === "CUSTOM" ? row.original.selectedModules.map(id => moduleName(id)).join(" + ") || t("ui.customRun") : profileName(row.original.profile)}
          <span className="mt-1 block font-mono text-xs">{t("ui.run")} {shortId(row.original.id)}</span>
        </Link>
        <StatusBadge {...runPresentation[row.original.status]} />
      </div>
    ),
  },
  {
      id: "ui.connection",
    get header() { return t("ui.connection"); },
    cell: ({ row }) => (
      <div className="min-w-32">
        <p>{row.original.connection.name}</p>
        <p className="mt-1 text-xs text-muted-foreground">
          {row.original.connection.databaseType}
        </p>
      </div>
    ),
  },
  {
      id: "ui.driver",
    get header() { return t("ui.driver"); },
    cell: ({ row }) => (
      <div>
        {row.original.driver.name}
        <p className="mt-1 text-xs text-muted-foreground">
          {row.original.driver.version}
        </p>
      </div>
    ),
  },
  {
      id: "ui.profile", get header() { return t("ui.profile"); }, cell: ({ row }) => profileName(row.original.profile) },
  {
      id: "ui.progress",
    get header() { return t("ui.progress"); },
    cell: ({ row }) => (
      <span className="whitespace-nowrap font-mono text-xs">
        {formatNumber(row.original.completedCases)} / {formatNumber(row.original.totalCases)}
      </span>
    ),
  },
  {
      id: "ui.results",
    get header() { return t("ui.results"); },
    cell: ({ row }) => (
      <div className="space-y-1 whitespace-nowrap text-xs tabular-nums">
        {[
          {get label() { return t("ui.passed"); }, value: row.original.passed, color: "text-success"},
          {get label() { return t("ui.failed"); }, value: row.original.failed, color: "text-destructive"},
          {get label() { return t("ui.skipped"); }, value: row.original.skipped, color: "text-neutral"},
          {get label() { return t("ui.aborted"); }, value: abortedCount(row.original), color: "text-warning"},
        ].map(({label, value, color}) => <p key={label} className={value != null && value > 0 ? color : "text-muted-foreground"}>{label} {formatNumber(value)}</p>)}
        {isTerminal(row.original.status) && row.original.accounting ? <>
          {row.original.accounting.notRun > 0 ? <p className="font-semibold text-warning">{t("ui.notRun")} {formatNumber(row.original.accounting.notRun)}</p> : null}
          {row.original.accounting.incomplete > 0 ? <p className="font-semibold text-warning">{t("ui.incomplete")} {formatNumber(row.original.accounting.incomplete)}</p> : null}
        </> : null}
      </div>
    ),
  },
  {
      id: "ui.duration",
    get header() { return t("ui.duration"); },
    cell: ({ row }) => formatDuration(row.original.durationMs),
  },
  {
      id: "ui.startedCreated",
    get header() { return t("ui.startedCreated"); },
    cell: ({ row }) => (
      <span className="whitespace-nowrap text-xs text-muted-foreground">
        {formatDateTime(row.original.startedAt ?? row.original.createdAt)}
      </span>
    ),
  },
];
}
