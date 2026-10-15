import { CleanupEvidence } from "./cleanup-evidence";
import { JdbcUrl } from "@/components/jdbc-url";
import { Card } from "@/components/ui/card";
import { moduleName, profileName } from "@/lib/presentation";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { lazy, Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { runsApi, runsKeys } from "@/api/runs";
import type { CaseResult, Run } from "@/types/api";
import { abortedCount } from "@/lib/outcomes";
import { isTerminal, resultPresentation } from "@/lib/status";
import { formatDateTime, formatDuration, formatNumber } from "@/lib/format";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState, ErrorState, Loading } from "@/components/common";
import { FailureDetail } from "./results";
const OutcomeChart = lazy(() => import("@/components/outcome-chart"));
export default function Report({ run }: { run: Run }) {
  useTranslation();
  const [rawOpen, setRawOpen] = useState(false);
  const [failure, setFailure] = useState<CaseResult | null>(null);
  const query = useQuery({
    queryKey: runsKeys.report(run.id),
    queryFn: ({ signal }) => runsApi.report(run.id, signal),
    enabled: isTerminal(run.status),
  });
  const raw = useQuery({
    queryKey: ["raw-report", run.id],
    queryFn: ({ signal }) => runsApi.rawReport(run.id, signal),
    enabled: rawOpen,
  });
  const databaseEvidence = typeof raw.data === "object" && raw.data !== null &&
    "source" in raw.data && raw.data.source === "PERSISTED_WORKER_EVENTS";
  const preservedHistoryCopy = document.querySelector('meta[name="jdbc-environment"]')?.getAttribute("content") === "history-copy";
  if (!isTerminal(run.status))
    return (
      <EmptyState
        title={t("ui.yourReportIsInProgress")}
        description={t("ui.theReportBecomesAvailableAfterTheRun")}
      />
    );
  if (query.isPending) return <Loading />;
  if (!query.data)
    return (
      <ErrorState error={query.error} retry={() => void query.refetch()} />
    );
  const summary = query.data.summary;
  const aborted = abortedCount(summary);
  return (
    <>
      {query.isError ? <ErrorState error={query.error} retry={() => void query.refetch()} /> : null}
      {summary.accounting ? <p className="mb-4 text-sm">
        {t("report.accounting", {notRun: summary.accounting.notRun, incomplete: summary.accounting.incomplete})}{summary.accounting.started === 0 ? t("ui.noScenarioExecutionWasVerifiedSkippedCases") : ""}
      </p> : null}
      <CleanupEvidence evidence={summary.cleanupEvidence} />
      {summary.executionIssues?.length ? <Card className="panel mb-4"><h2>{t("ui.executionIssues")}</h2>
        {summary.executionIssues.map((issue, index) => <p key={index} className="break-words">{String(issue.level)}: {String(issue.message ?? issue.status)}</p>)}
      </Card> : null}
      <div className="mb-6 grid gap-6 xl:grid-cols-[340px_1fr]">
        <Card as="section" className="panel">
          <h2 className="text-lg font-semibold">{t("ui.testOutcomes")}</h2>
          {summary.completedCases ? (
            <Suspense fallback={<Loading />}>
              <OutcomeChart
                passed={summary.passed}
                failed={summary.failed}
                skipped={summary.skipped}
                aborted={aborted}
              />
            </Suspense>
          ) : (
            <EmptyState
              title={t("ui.noCompletedCases")}
              description={t("ui.thisRunEndedBeforeCaseResultsWere")}
            />
          )}
          <p className="mt-3 text-center text-sm text-muted-foreground">
            {t("report.outcomes", {passed: formatNumber(summary.passed), failed: formatNumber(summary.failed), skipped: formatNumber(summary.skipped), aborted: formatNumber(aborted)})}</p>
        </Card>
        <Card as="section" className="panel">
          <h2 className="mb-5 text-lg font-semibold">{t("ui.capturedEnvironment")}</h2>
          <dl className="grid gap-5 text-sm sm:grid-cols-2">
            {Object.entries({
              [t("ui.connection")]: summary.connection.name,
              [t("ui.databaseType")]: summary.connection.databaseType,
              [t("ui.adapter")]: summary.connection.adapterId,
              [t("ui.driver")]: `${summary.driver.name} · ${summary.driver.version}`,
              [t("ui.driverClass")]: summary.driver.driverClass,
              [t("ui.profile")]: profileName(summary.profile),
              [t("ui.started")]: formatDateTime(summary.startedAt),
              [t("ui.finished")]: formatDateTime(summary.finishedAt),
              [t("ui.duration")]: formatDuration(summary.durationMs),
            }).map(([label, value]) => (
              <div key={label}>
                <dt className="mb-1 text-muted-foreground">{label}</dt>
                <dd className="break-all">{value}</dd>
              </div>
            ))}
          </dl>
          <div className="mt-5"><JdbcUrl value={summary.connection.jdbcUrl} /></div>
          <div className="mt-5 border-t pt-4">
            <h3 className="text-sm font-medium">{t("ui.selectedModules")}</h3>
            <p className="mt-2 text-sm text-muted-foreground">
              {summary.selectedModules.map(id => moduleName(id)).join(", ") || "—"}
            </p>
            <p className="mt-3 text-xs text-muted-foreground">
                  {t("ui.selectionCapturedWhenThisRunWasCreated")}</p>
          </div>
        </Card>
      </div>
      <Card as="section" className="panel">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-semibold">{t("ui.failuresAbortedCases")}</h2>
          <Button variant="outline" onClick={() => setRawOpen(true)}>
            {t("report.viewJSON")}</Button>
        </div>
        {query.data.failures.length ? (
          <ul className="mt-5 divide-y">
            {query.data.failures.map((item) => (
              <li
                key={item.id}
                className="flex items-center justify-between gap-3 py-4"
              >
                <div className="min-w-0">
                  <p className="break-words text-sm font-medium">{item.displayName}</p>
                  <p className="mt-1 font-mono text-xs text-muted-foreground">
                    {item.scenarioId} · {resultPresentation[item.status].label}
                  </p>
                </div>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setFailure(item)}
                >
                  {t("ui.inspectFailure")}</Button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-5 text-sm text-muted-foreground">
            {t("ui.noFailedOrAbortedCasesWereRecorded")}</p>
        )}
      </Card>
      <FailureDetail result={failure} close={() => setFailure(null)} />
      <Dialog
        open={rawOpen}
        onOpenChange={setRawOpen}
        title={t(databaseEvidence ? "report.databaseEvidenceJSON" : "report.reportJSON")}
        description={databaseEvidence
          ? `${t("report.databaseEvidenceDescription")}${preservedHistoryCopy ? ` ${t("history.report")}` : ""}`
          : t("report.jsonDescription")}
      >
        {raw.isPending ? (
          <Loading />
        ) : raw.isError ? (
          <ErrorState error={raw.error} retry={() => void raw.refetch()} />
        ) : (
          <>
            <Button
              variant="outline"
              size="sm"
              className="mb-4"
              onClick={() => {
                const url = URL.createObjectURL(
                  new Blob([JSON.stringify(raw.data, null, 2)], {
                    type: "application/json",
                  }),
                );
                const anchor = document.createElement("a");
                anchor.href = url;
                anchor.download = `jdbc-test-${run.id}${databaseEvidence ? "-database-evidence" : ""}.json`;
                anchor.click();
                URL.revokeObjectURL(url);
              }}
            >
              {t(databaseEvidence ? "report.downloadDatabaseEvidence" : "ui.downloadJSON")}</Button>
            <pre className="max-h-[55vh] overflow-auto whitespace-pre-wrap break-words rounded-xl bg-background p-4 font-mono text-xs">
              {JSON.stringify(raw.data, null, 2)}
            </pre>
          </>
        )}
      </Dialog>
    </>
  );
}
