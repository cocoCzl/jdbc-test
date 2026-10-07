import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { runsApi, runsKeys } from "@/api/runs";
import type { CaseResult, ResultStatus } from "@/types/api";
import { resultPresentation } from "@/lib/status";
import { formatDuration } from "@/lib/format";
import { DataTable, Pagination } from "@/components/data-table";
import { Dialog } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import {
  EmptyState,
  ErrorState,
  Loading,
  StatusBadge,
} from "@/components/common";
export function FailureDetail({
  result,
  close,
}: {
  result: CaseResult | null;
  close: () => void;
}) {
  return (
    <Dialog
      open={!!result}
      onOpenChange={(open) => {
        if (!open) close();
      }}
      title={result?.displayName ?? "Case detail"}
      description={`${result?.scenarioId ?? ""} · ${result?.moduleId ?? ""}`}
    >
      <div className="space-y-4">
        {result ? (
          <>
            <div className="flex items-center gap-4">
              <StatusBadge {...resultPresentation[result.status]} />
              <span className="text-sm">
                {formatDuration(result.durationMs)}
              </span>
            </div>
            <section>
              <h3 className="mb-2 text-sm font-medium">Message</h3>
              <pre className="whitespace-pre-wrap break-words rounded-xl bg-background p-4 font-mono text-xs">
                {result.message ?? "No message provided."}
              </pre>
            </section>
            <section>
              <h3 className="mb-2 text-sm font-medium">Stack trace</h3>
              <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-xl bg-background p-4 font-mono text-xs">
                {result.stackTrace ?? "No stack trace provided."}
              </pre>
            </section>
          </>
        ) : null}
      </div>
    </Dialog>
  );
}
export default function Results({
  id,
  modules,
}: {
  id: string;
  modules: string[];
}) {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ResultStatus | "">("");
  const [moduleId, setModule] = useState("");
  const [sort, setSort] = useState("scenarioId,asc");
  const [selected, setSelected] = useState<CaseResult | null>(null);
  const params = {
    page,
    size: 20,
    sort,
    status: status || undefined,
    moduleId: moduleId || undefined,
  };
  const query = useQuery({
    queryKey: [...runsKeys.results(id), params],
    queryFn: ({ signal }) => runsApi.results(id, params, signal),
    placeholderData: keepPreviousData,
  });
  const columns: ColumnDef<CaseResult>[] = [
    {
      header: "Status",
      cell: ({ row }) => (
        <StatusBadge {...resultPresentation[row.original.status]} />
      ),
    },
    { header: "Module", accessorKey: "moduleId" },
    {
      header: "Scenario",
      cell: ({ row }) => (
        <code className="text-xs">{row.original.scenarioId}</code>
      ),
    },
    { header: "Display name", accessorKey: "displayName" },
    {
      header: "Duration",
      cell: ({ row }) => formatDuration(row.original.durationMs),
    },
    {
      header: "Detail",
      cell: ({ row }) =>
        ["FAILED", "ABORTED"].includes(row.original.status) ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setSelected(row.original)}
          >
            Inspect failure
          </Button>
        ) : (
          "—"
        ),
    },
  ];
  return (
    <>
      <div className="mb-5 flex flex-wrap gap-3">
        <label className="text-xs text-muted-foreground">
          Status
          <select
            aria-label="Filter result status"
            className="select mt-1 w-40"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as ResultStatus | "");
              setPage(0);
              setModule("");
            }}
          >
            <option value="">All results</option>
            {Object.entries(resultPresentation).map(([value, item]) => (
              <option value={value} key={value}>
                {item.label}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs text-muted-foreground">
          Module
          <select
            aria-label="Filter result module"
            className="select mt-1 w-48"
            value={moduleId}
            disabled={!!status}
            onChange={(event) => {
              setModule(event.target.value);
              setPage(0);
            }}
          >
            <option value="">All modules</option>
            {modules.map((module) => (
              <option key={module}>{module}</option>
            ))}
          </select>
        </label>
        <label className="text-xs text-muted-foreground">
          Sort
          <select
            aria-label="Sort results"
            className="select mt-1 w-48"
            value={sort}
            onChange={(event) => {
              setSort(event.target.value);
              setPage(0);
            }}
          >
            <option value="scenarioId,asc">Scenario A–Z</option>
            <option value="durationMs,desc">Longest first</option>
            <option value="status,asc">Status</option>
          </select>
        </label>
      </div>
      {status ? (
        <p className="mb-4 text-xs text-muted-foreground">
          Clear the status filter to filter results by module.
        </p>
      ) : null}
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <div className="panel overflow-hidden p-0" aria-busy={query.isFetching}>
          <DataTable
            rows={query.data.items}
            columns={columns}
            empty={
              <EmptyState
                title="No case results yet"
                description="Cases appear as the worker completes them. Try another filter if this run has finished."
              />
            }
          />
          <Pagination {...query.data} onPage={setPage} />
        </div>
      )}
      <FailureDetail result={selected} close={() => setSelected(null)} />
    </>
  );
}
