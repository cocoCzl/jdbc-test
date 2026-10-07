import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { runsApi, runsKeys } from "@/api/runs";
import type { RunStatus } from "@/types/api";
import { runPresentation } from "@/lib/status";
import { Button } from "@/components/ui/button";
import { DataTable, Pagination } from "@/components/data-table";
import {
  EmptyState,
  ErrorState,
  Loading,
  PageHeader,
} from "@/components/common";
import { runColumns } from "./run-columns";
export default function Runs() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<RunStatus | "">("");
  const [sort, setSort] = useState("createdAt,desc");
  const params = { page, size: 20, sort, status: status || undefined };
  const query = useQuery({
    queryKey: runsKeys.list(params),
    queryFn: ({ signal }) => runsApi.list(params, signal),
    placeholderData: keepPreviousData,
  });
  return (
    <>
      <PageHeader
        title="Run history"
        description="Every execution is a reproducible snapshot of the connection, driver and selection."
      />
      <div className="mb-5 flex flex-wrap gap-4">
        <label className="flex items-center gap-3 text-sm text-muted-foreground">
          Status
          <select
            aria-label="Filter run status"
            className="select w-48"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as RunStatus | "");
              setPage(0);
            }}
          >
            <option value="">All statuses</option>
            {Object.entries(runPresentation).map(([value, presentation]) => (
              <option key={value} value={value}>
                {presentation.label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex items-center gap-3 text-sm text-muted-foreground">
          Sort
          <select
            aria-label="Sort runs"
            className="select w-48"
            value={sort}
            onChange={(event) => {
              setSort(event.target.value);
              setPage(0);
            }}
          >
            <option value="createdAt,desc">Newest first</option>
            <option value="startedAt,desc">Started recently</option>
            <option value="finishedAt,desc">Finished recently</option>
          </select>
        </label>
      </div>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <div className="panel overflow-hidden p-0" aria-busy={query.isFetching}>
          <DataTable
            rows={query.data.items}
            columns={runColumns}
            empty={
              <EmptyState
                title="No test runs yet"
                description={
                  status
                    ? "No runs match this status."
                    : "Start your first compatibility run and build a history of evidence."
                }
                action={
                  <Button asChild>
                    <Link to="/runs/new">Create a test run</Link>
                  </Button>
                }
              />
            }
          />
          <Pagination {...query.data} onPage={setPage} />
        </div>
      )}
    </>
  );
}
