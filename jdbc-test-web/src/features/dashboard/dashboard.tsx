import { lazy, Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ArrowRight, FlaskConical, Layers, Terminal } from "lucide-react";
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
import { formatNumber } from "@/lib/format";
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
  const outcomes = recent.reduce(
    (total, run) => ({
      passed: total.passed + run.passed,
      failed: total.failed + run.failed,
      skipped: total.skipped + run.skipped,
      aborted:
        total.aborted +
        Math.max(0, run.completedCases - run.passed - run.failed - run.skipped),
    }),
    { passed: 0, failed: 0, skipped: 0, aborted: 0 },
  );
  return (
    <>
      <PageHeader
        title="Compatibility, at a glance"
        description="A clear view of your driver tests. Real runs. Reproducible evidence."
      />
      <section className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Metric
          label="Total runs"
          value={runs.data ? formatNumber(runs.data.totalElements) : "—"}
          note="All history · reported by the server"
          icon={<Terminal className="size-4" />}
        />
        <Metric
          label="Catalog scenarios"
          value={
            catalog.data ? formatNumber(catalog.data.totalScenarioCount) : "—"
          }
          note="Versioned test inventory"
          icon={<FlaskConical className="size-4" />}
        />
        <Metric
          label="Test modules"
          value={catalog.data?.modules.length ?? "—"}
          note="Targeted JDBC contract areas"
          icon={<Layers className="size-4" />}
        />
        <Metric
          label="Recent cases completed"
          value={
            runs.data
              ? formatNumber(
                  recent.reduce((total, run) => total + run.completedCases, 0),
                )
              : "—"
          }
          note={`Within the latest ${recent.length} loaded runs`}
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
      <div className="mb-6 grid gap-6 xl:grid-cols-[1fr_360px]">
        <section className="panel flex flex-col justify-between">
          <div>
            <p className="eyebrow">Your next verification</p>
            <h2 className="mt-3 text-2xl font-semibold">
              Small selections.
              <br />
              Strong evidence.
            </h2>
            <p className="mt-4 max-w-lg text-sm leading-relaxed text-muted-foreground">
              Start with a focused module, or run the complete catalog. Every
              execution captures the exact connection, driver and selection
              used.
            </p>
          </div>
          <div className="mt-8 flex flex-wrap items-center gap-5">
            <Button asChild>
              <Link to="/runs/new">
                Create test run
                <ArrowRight />
              </Link>
            </Button>
            <Link to="/drivers" className="subtle-link text-sm">
              Manage driver packages
            </Link>
          </div>
        </section>
        <section className="panel">
          <h2 className="font-semibold">Recent test outcomes</h2>
          <p className="mt-1 text-xs text-muted-foreground">
            Latest {recent.length} runs only · not global statistics
          </p>
          {outcomes.passed +
          outcomes.failed +
          outcomes.skipped +
          outcomes.aborted ? (
            <Suspense fallback={<Loading label="Loading chart…" />}>
              <OutcomeChart {...outcomes} />
            </Suspense>
          ) : (
            <EmptyState
              title="No outcomes yet"
              description="Completed cases from recent runs will appear here."
            />
          )}
        </section>
      </div>
      <section className="panel overflow-hidden p-0">
        <div className="flex items-center justify-between px-6 py-5">
          <div>
            <h2 className="text-lg font-semibold">Recent runs</h2>
            <p className="mt-1 text-xs text-muted-foreground">
              The latest executions in your workspace
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
            columns={runColumns}
            empty={
              <EmptyState
                title="No test runs yet"
                description="Upload a driver, add a target connection, and start your first run."
                action={
                  <Button asChild>
                    <Link to="/drivers/new">Add a driver</Link>
                  </Button>
                }
              />
            }
          />
        )}
      </section>
    </>
  );
}
