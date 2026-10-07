import { Plus } from "lucide-react";
import { lazy, Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { CheckCheck, FlaskConical, Layers, Terminal } from "lucide-react";
import { runsApi, runsKeys } from "@/api/runs";
import { catalogApi, catalogKeys } from "@/api/catalog";
import { Button } from "@/components/ui/button";
import { DataTable } from "@/components/data-table";
import {
  EmptyState,
  ErrorState,
  Loading,
  Metric,
  PageHeader,
} from "@/components/common";
import { abortedCount, knownSum } from "@/lib/outcomes";
import { formatNumber, plural } from "@/lib/format";
import { runColumns } from "@/features/runs/run-columns";
const OutcomeChart = lazy(() => import("@/components/outcome-chart"));
export default function Dashboard() {
  const params = { page: 0, size: 8, sort: "createdAt,desc" };
  const runs = useQuery({
    queryKey: runsKeys.list(params),
    queryFn: ({ signal }) => runsApi.list(params, signal),
  });
  const catalog = useQuery({
    queryKey: catalogKeys.all,
    queryFn: ({ signal }) => catalogApi.get(signal),
    staleTime: 300_000,
  });
  const recent = runs.data?.items ?? [];
  const outcomes = {
    passed: knownSum(recent.map((run) => run.passed)),
    failed: knownSum(recent.map((run) => run.failed)),
    skipped: knownSum(recent.map((run) => run.skipped)),
    aborted: knownSum(recent.map(abortedCount)),
  };
  return (
    <>
      <PageHeader
        title="Workspace overview"
        action={<Button asChild><Link to="/runs/new"><Plus />New run</Link></Button>}
        description="Recent executions and their recorded JDBC results."
      />
      <section className="mb-5 grid grid-cols-2 gap-3 xl:grid-cols-4">
        <Metric
          label="Total runs"
          value={runs.data ? formatNumber(runs.data.totalElements) : "—"}
          note="All history · reported by the server"
          icon={<Terminal className="size-4" />}
        />
        <Metric
          tone="highlight"
          label="Available test cases"
          value={
            catalog.data ? formatNumber(catalog.data.totalScenarioCount) : "—"
          }
          note="Current test catalog"
          icon={<FlaskConical className="size-4" />}
        />
        <Metric
          tone="neutral"
          label="Test modules"
          value={catalog.data?.modules.length ?? "—"}
          note="Available JDBC test groups"
          icon={<Layers className="size-4" />}
        />
        <Metric
          tone="success"
          icon={<CheckCheck className="size-4" />}
          label="Recent cases completed"
          value={
            runs.data
              ? formatNumber(
                  knownSum(recent.map((run) => run.completedCases)),
                )
              : "—"
          }
          note={`Latest ${plural(recent.length, "loaded run")}`}
        />
      </section>
      {catalog.isError ? (
        <div className="mb-6">
          <ErrorState
            error={catalog.error}
            retry={() => void catalog.refetch()}
          />
        </div>
      ) : null}
      <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1fr)_300px]">
      <section className="panel min-w-0 overflow-hidden p-0">
        <div className="flex items-center justify-between px-4 py-4">
          <div>
            <h2 className="text-lg font-semibold">Recent runs</h2>
            <p className="mt-1 text-xs text-muted-foreground">
              Latest {plural(recent.length, "loaded run")}
            </p>
          </div>
          <Link className="subtle-link text-sm" to="/runs">
            View history →
          </Link>
        </div>
        {runs.isPending ? (
          <Loading />
        ) : runs.isError ? (
          <ErrorState error={runs.error} retry={() => void runs.refetch()} />
        ) : (
          <DataTable
            rows={recent}
            columns={[runColumns[0], runColumns[1], runColumns[4], runColumns[5]]}
            empty={
              <EmptyState
                title="No test runs yet"
                description="Upload a driver, add a target connection, and start your first run."
                action={
                  <Button asChild variant="outline">
                    <Link to="/drivers/new">Add a driver</Link>
                  </Button>
                }
              />
            }
          />
        )}
      </section>
      {recent.length > 0 ? <section className="panel min-w-0">
        <h2 className="font-semibold">Recent test outcomes</h2>
        <p className="mt-1 text-xs text-muted-foreground">{plural(recent.length, "loaded run")} · recorded results</p>
        <Suspense fallback={<Loading label="Loading chart…" />}><OutcomeChart {...outcomes} /></Suspense>
        <details className="mt-3 border-t pt-3 text-sm text-muted-foreground">
          <summary className="cursor-pointer rounded-sm font-medium">About these statistics</summary>
          <p className="mt-2 leading-relaxed">The distribution and recent completed count cover only the latest {plural(recent.length, "loaded run")}, not all history. Total runs is the server-reported history count. Started, pending and incomplete cases are not outcome categories. Missing historical counts remain unknown.</p>
        </details>
      </section> : null}
      </div>
    </>
  );
}
