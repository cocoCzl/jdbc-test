import { Link } from "react-router-dom";
import type { ColumnDef } from "@tanstack/react-table";
import type { Run } from "@/types/api";
import { StatusBadge } from "@/components/common";
import { runPresentation } from "@/lib/status";
import { formatDateTime, formatDuration, shortId } from "@/lib/format";
export const runColumns: ColumnDef<Run>[] = [
  {
    header: "Run / status",
    cell: ({ row }) => (
      <Link to={`/runs/${row.original.id}`} className="space-y-2">
        <StatusBadge {...runPresentation[row.original.status]} />
        <code className="block text-xs text-muted-foreground">
          {shortId(row.original.id)}
        </code>
      </Link>
    ),
  },
  {
    header: "Connection",
    cell: ({ row }) => (
      <div className="min-w-32">
        <Link className="subtle-link" to={`/runs/${row.original.id}`}>
          {row.original.connection.name}
        </Link>
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
    header: "P / F / S",
    cell: ({ row }) => (
      <span className="whitespace-nowrap text-xs">
        {row.original.passed} / {row.original.failed} / {row.original.skipped}
      </span>
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
