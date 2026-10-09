import { t } from "i18next";
import type { ColumnDef } from "@tanstack/react-table";
import type { Run } from "@/types/api";
import { formatDateTime, formatDuration, formatNumber } from "@/lib/format";
import { RunIdentityCell, RunResultSummary } from "./run-cells";
import "./run-table.scss";

const identity: ColumnDef<Run> = {
  id: "ui.runStatus",
  get header() { return t("ui.runStatus"); },
  cell: ({ row }) => <RunIdentityCell run={row.original} />,
};
const connection: ColumnDef<Run> = {
  id: "ui.connection",
  get header() { return t("ui.connection"); },
  cell: ({ row }) => <div className="run-cell-stack">
    <span className="run-cell-primary">{row.original.connection.name}</span>
    <span className="run-cell-secondary">{row.original.connection.databaseType}</span>
  </div>,
};
const driver: ColumnDef<Run> = {
  id: "ui.driver",
  get header() { return t("ui.driver"); },
  cell: ({ row }) => <div className="run-cell-stack">
    <span className="run-cell-primary">{row.original.driver.name}</span>
    <span className="run-cell-secondary">{row.original.driver.version}</span>
  </div>,
};
const progress: ColumnDef<Run> = {
  id: "ui.progress",
  get header() { return t("ui.progress"); },
  cell: ({ row }) => <span className="run-cell-number">
    {formatNumber(row.original.completedCases)} / {formatNumber(row.original.totalCases)}
  </span>,
};
const results: ColumnDef<Run> = {
  id: "ui.results",
  get header() { return t("ui.results"); },
  cell: ({ row }) => <RunResultSummary run={row.original} />,
};
const duration: ColumnDef<Run> = {
  id: "ui.duration",
  get header() { return t("ui.duration"); },
  cell: ({ row }) => <span className="run-cell-number">{formatDuration(row.original.durationMs)}</span>,
};
const started: ColumnDef<Run> = {
  id: "ui.startedCreated",
  get header() { return t("ui.startedCreated"); },
  cell: ({ row }) => <span className="run-cell-secondary run-cell-date">
    {formatDateTime(row.original.startedAt ?? row.original.createdAt)}
  </span>,
};

export function getRunColumns({ compact = false }: { compact?: boolean } = {}): ColumnDef<Run>[] {
  return compact
    ? [identity, connection, progress, results]
    : [identity, connection, driver, progress, results, duration, started];
}
