import { Card } from "@/components/ui/card";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
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
import { getRunColumns } from "@/features/runs/run-columns";
const OutcomeChart = lazy(() => import("@/components/outcome-chart"));
export default function Dashboard() {
  useTranslation();
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
  const runColumns = getRunColumns({ compact: true });
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
        title={t("ui.workspaceOverview")}
        action={<Button asChild><Link to="/runs/new"><Plus />{t("ui.newRun")}</Link></Button>}
        description={t("ui.recentExecutionsAndTheirRecordedJDBCResults")}
      />
      <section className="mb-5 grid grid-cols-2 gap-3 xl:grid-cols-4">
        <Metric
          label={t("ui.totalRuns")}
          value={runs.data ? formatNumber(runs.data.totalElements) : "—"}
          note={t("dashboard.allHistory")}
          icon={<Terminal className="size-4" />}
        />
        <Metric
          tone="highlight"
          label={t("ui.availableTestCases")}
          value={
            catalog.data ? formatNumber(catalog.data.totalScenarioCount) : "—"
          }
          note={t("dashboard.catalog")}
          icon={<FlaskConical className="size-4" />}
        />
        <Metric
          tone="cyan"
          label={t("ui.testModules")}
          value={catalog.data?.modules.length ?? "—"}
          note={t("dashboard.groups")}
          icon={<Layers className="size-4" />}
        />
        <Metric
          tone="success"
          icon={<CheckCheck className="size-4" />}
          label={t("ui.recentCasesCompleted")}
          value={
            runs.data
              ? formatNumber(
                  knownSum(recent.map((run) => run.completedCases)),
                )
              : "—"
          }
          note={t("dashboard.latest", {countText: plural(recent.length, "loaded run")}) + " · " + t("run.completedNote")}
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
      <Card as="section" className="panel min-w-0 overflow-hidden p-0">
        <div className="flex items-center justify-between px-4 py-4">
          <div>
            <h2 className="text-lg font-semibold">{t("ui.recentRuns")}</h2>
            <p className="mt-1 text-xs text-muted-foreground">
              {t("dashboard.latest", {countText: plural(recent.length, "loaded run")})}
            </p>
          </div>
          <Link className="subtle-link text-sm" to="/runs">
            {t("ui.viewHistory")}</Link>
        </div>
        {runs.isError && runs.data ? <ErrorState error={runs.error} retry={() => void runs.refetch()} /> : null}
        {runs.isPending ? (
          <Loading />
        ) : !runs.data ? (
          <ErrorState error={runs.error} retry={() => void runs.refetch()} />
        ) : (
          <DataTable
            rows={recent}
            columns={runColumns}
            className="run-table run-table--recent"
            empty={
              <EmptyState
                title={t("ui.noTestRunsYet")}
                description={t("ui.uploadADriverAddATargetConnection")}
                action={
                  <Button asChild variant="outline">
                    <Link to="/drivers/new">{t("ui.addADriver")}</Link>
                  </Button>
                }
              />
            }
          />
        )}
      </Card>
      {recent.length > 0 ? <Card as="section" className="panel min-w-0">
        <h2 className="font-semibold">{t("ui.recentTestOutcomes")}</h2>
        <p className="mt-1 text-xs text-muted-foreground">{plural(recent.length, "loaded run")}  {t("ui.recordedResults")}</p>
        <Suspense fallback={<Loading label={t("ui.loadingChart")} />}><OutcomeChart {...outcomes} /></Suspense>
        <details className="mt-3 border-t pt-3 text-sm text-muted-foreground">
          <summary className="cursor-pointer rounded-sm font-medium">{t("ui.aboutTheseStatistics")}</summary>
          <p className="mt-2 leading-relaxed">{t("dashboard.scope", {countText: plural(recent.length, "loaded run")})}</p>
        </details>
      </Card> : null}
      </div>
    </>
  );
}
