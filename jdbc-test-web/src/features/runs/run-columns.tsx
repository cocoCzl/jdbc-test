import { Link } from "react-router-dom";
import type { ColumnDef } from "@tanstack/react-table";
import type { Run } from "@/types/api";
import { StatusBadge } from "@/components/common";
import { abortedCount } from "@/lib/outcomes";
import { isTerminal, runPresentation } from "@/lib/status";
import { formatDateTime, formatDuration, shortId } from "@/lib/format";
export const runColumns: ColumnDef<Run>[] = [
  {
    header: "Run / status",
    cell: ({ row }) => (
      <div className="min-w-36 max-w-56 space-y-2">
        <Link to={`/runs/${row.original.id}`} className="subtle-link block rounded-sm font-semibold" title={`Open run ${row.original.id}`}>
          {row.original.profile === "CUSTOM" ? row.original.selectedModules.join(" + ") || "Custom run" : row.original.profile}
          <span className="mt-1 block font-mono text-xs">Run {shortId(row.original.id)}</span>
        </Link>
        <StatusBadge {...runPresentation[row.original.status]} />
      </div>
    ),
  },
  {
    header: "Connection",
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
    header: "Driver",
    cell: ({ row }) => (
      <div>
        {row.original.driver.name}
        <p className="mt-1 text-xs text-muted-foreground">
          {row.original.driver.version}
        </p>
      </div>
    ),
  },
  { header: "Profile", accessorKey: "profile" },
  {
    header: "Progress",
    cell: ({ row }) => (
      <span className="whitespace-nowrap font-mono text-xs">
        {row.original.completedCases} / {row.original.totalCases}
      </span>
    ),
  },
  {
    header: "Results",
    cell: ({ row }) => (
      <div className="space-y-1 whitespace-nowrap text-xs tabular-nums">
        {[
          {label: "Passed", value: row.original.passed, color: "text-success"},
          {label: "Failed", value: row.original.failed, color: "text-destructive"},
          {label: "Skipped", value: row.original.skipped, color: "text-neutral"},
          {label: "Aborted", value: abortedCount(row.original), color: "text-warning"},
        ].map(({label, value, color}) => <p key={label} className={value != null && value > 0 ? color : "text-muted-foreground"}>{label} {value ?? "—"}</p>)}
        {isTerminal(row.original.status) && row.original.accounting ? <>
          {row.original.accounting.notRun > 0 ? <p className="font-semibold text-warning">Not run {row.original.accounting.notRun}</p> : null}
          {row.original.accounting.incomplete > 0 ? <p className="font-semibold text-warning">Incomplete {row.original.accounting.incomplete}</p> : null}
        </> : null}
      </div>
    ),
  },
  {
    header: "Duration",
    cell: ({ row }) => formatDuration(row.original.durationMs),
  },
  {
    header: "Started / created",
    cell: ({ row }) => (
      <span className="whitespace-nowrap text-xs text-muted-foreground">
        {formatDateTime(row.original.startedAt ?? row.original.createdAt)}
      </span>
    ),
  },
];
