import { Card } from "@/components/ui/card";
import { JdbcUrl } from "@/components/jdbc-url";
import { moduleName, profileName, runTitle } from "@/lib/presentation";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { Ban, Check, CircleDashed, Clock, ListChecks, LoaderCircle, Play, Plus, TriangleAlert, X } from "lucide-react";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { toast } from "sonner";
import { ApiError, errorMessage } from "@/api/client";
import { runsApi, runsKeys } from "@/api/runs";
import { useRunEvents } from "@/hooks/use-run-events";
import { isTerminal, runPresentation } from "@/lib/status";
import { formatDateTime, formatDuration, formatNumber, shortId } from "@/lib/format";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/alert-dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  CopyButton,
  ErrorState,
  PendingPage,
  Metric,
  PageHeader,
  StatusBadge,
} from "@/components/common";
import Results from "./results";
import Report from "./report";
export default function RunDetail() {
  useTranslation();
  const id = useParams().id!;
  const client = useQueryClient();
  const [cancelling, setCancelling] = useState(false);
  const query = useQuery({
    queryKey: runsKeys.detail(id),
    queryFn: ({ signal }) => runsApi.get(id, signal),
    refetchInterval: (query) =>
      query.state.data && !isTerminal(query.state.data.status) ? 10_000 : false,
  });
  const live = useRunEvents(id, query.data);
  const cancel = useMutation({
    mutationFn: () => runsApi.cancel(id),
    onSuccess: () => {
      setCancelling(false);
      toast.success(t("ui.runCancelled"));
      void client.invalidateQueries({ queryKey: runsKeys.all });
    },
    onError: (error) => {
      setCancelling(false);
      if (error instanceof ApiError && error.code === "RUN_ALREADY_FINISHED") {
        void query.refetch();
        toast.info(errorMessage(error));
      } else toast.error(errorMessage(error));
    },
  });
  if (query.isPending) return <PendingPage title={t("ui.runDetail")} />;
  if (!query.data) return <PendingPage title={t("ui.runDetail")} error={query.error} retry={() => void query.refetch()} />;
  const run = query.data;
  const progress = run.totalCases > 0 && run.completedCases != null
    ? Math.min(100, (run.completedCases / run.totalCases) * 100) : null;
  const terminal = isTerminal(run.status);
  const secondary = [
    { id: "started", icon: Play, get label() { return t("accounting.started"); }, value: run.accounting?.started },
    { id: "aborted", icon: Ban, get label() { return t("ui.aborted"); }, value: run.accounting?.aborted },
    { id: "notRun", icon: terminal ? CircleDashed : Clock, label: terminal ? t("ui.notRun") : t("ui.pending"), value: terminal ? run.accounting?.notRun : run.accounting?.pending },
    { id: "incomplete", icon: terminal ? TriangleAlert : LoaderCircle, label: terminal ? t("ui.incomplete") : t("accounting.running"), value: terminal ? run.accounting?.incomplete : run.accounting?.running },
  ];
  return (
    <>
      {query.isError ? <ErrorState error={query.error} retry={() => void query.refetch()} /> : null}
      <PageHeader
        title={runTitle(run)}
        description=""
        action={
          <div className="flex items-center gap-3">
            <StatusBadge {...runPresentation[run.status]} />
            {!isTerminal(run.status) ? (
              <Button variant="destructive" onClick={() => setCancelling(true)}>
                {t("ui.cancelRun")}</Button>
            ) : <Button asChild><Link to="/runs/new"><Plus />{t("ui.newRun")}</Link></Button>}
          </div>
        }
      />
      <div className="mb-4 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <code title={run.id}>{shortId(run.id)}</code>
        <CopyButton value={run.id} label={t("ui.runID")} />
        <span className="ml-auto" role="status">
          {live === "live"
            ? t("ui.liveUpdatesConnected")
            : live === "reconnecting"
              ? t("ui.liveUpdatesReconnecting")
              : live === "connecting"
                ? t("ui.connectingLiveUpdates")
                : t("ui.runComplete")}
        </span>
      </div>
      <section aria-label={t("ui.runOutcomeSummary")} className="mb-3 grid grid-cols-2 gap-3 md:grid-cols-4">
        <Metric label={t("ui.total")} value={run.totalCases ?? "—"} icon={<ListChecks className="size-4" />} />
        <Metric label={t("ui.passed")} tone="success" subdued={run.passed == null || run.passed === 0} value={run.passed ?? "—"} icon={<Check className="size-4" />} />
        <Metric label={t("ui.failed")} tone="danger" subdued={run.failed == null || run.failed === 0} value={run.failed ?? "—"} icon={<X className="size-4" />} />
        <Metric label={t("ui.skipped")} tone="neutral" subdued={run.skipped == null || run.skipped === 0} value={run.skipped ?? "—"} icon={<CircleDashed className="size-4" />} />
      </section>
      <Card as="section" className="panel mb-4" aria-label={t("ui.executionAccounting")}>
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-sm">
          <p className="font-medium">{t("ui.executionProgress")} <span className="ml-2 font-normal text-muted-foreground">{t("ui.completed")}</span></p>
          <span className="font-mono text-sm">{formatNumber(run.completedCases)} / {formatNumber(run.totalCases)}</span>
        </div>
        <progress aria-label={t("ui.executionProgress")}
          className={`run-progress h-2 w-full overflow-hidden rounded-full tone-${run.status === "SUCCEEDED" && run.passed > 0 ? "success" : runPresentation[run.status].tone}`}
          max={run.totalCases || 1} value={run.completedCases ?? undefined} />
        <p className="mt-2 text-xs text-muted-foreground">
          {progress == null ? t("ui.progressUnavailable") : t("run.complete", {percent: Math.round(progress)})}  {t("ui.durationInline")} {formatDuration(run.durationMs)}
        </p>
        <details className="mt-2 text-xs text-muted-foreground"><summary className="cursor-pointer">{t("ui.aboutTheseStatistics")}</summary><p className="mt-2">{t("run.completedNote")}</p></details>
        <dl className="mt-3 flex flex-wrap gap-x-6 gap-y-2 border-t pt-3 text-sm" aria-label={t("ui.executionCounters")}>
          {secondary.map(({id,icon: Icon,label,value}, index) => <div key={id} data-stat={label}
            className={`flex items-center gap-2 ${index > 0 && value != null && value > 0 ? terminal ? "font-semibold text-warning" : "font-semibold text-primary" : "text-muted-foreground"}`}>
            <dt className="flex items-center gap-1.5"><Icon className="size-3.5" aria-hidden="true" />{label}</dt><dd className="font-semibold tabular-nums">{formatNumber(value)}</dd>
          </div>)}
        </dl>
        {!run.accounting ? <p className="mt-2 text-xs text-muted-foreground">{t("ui.legacyRunExecutionStartAndInterruptionEvidence")}</p> : null}
        {run.accounting?.started === 0 && terminal ? <p className="mt-2 text-sm text-warning">{t("ui.noScenarioExecutionWasVerifiedSkippedCases")}</p> : null}
        {!terminal ? <div className="mt-3 grid gap-2 border-t pt-3 text-sm sm:grid-cols-2">
          <p><span className="text-muted-foreground">{t("ui.currentModule")} </span>{run.currentModule ? moduleName(run.currentModule) : t("ui.waitingForWorker")}</p>
          <p className="break-all"><span className="text-muted-foreground">{t("ui.currentCase")} </span>{run.currentTestCase ?? "—"}</p>
        </div> : null}
        {run.failureReason ? <p role="alert" className="mt-3 break-words rounded-lg bg-destructive/5 p-3 text-sm text-destructive">{run.failureReason}</p> : null}
      </Card>
      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">{t("ui.overview")}</TabsTrigger>
          <TabsTrigger value="cases">{t("ui.testCases")}</TabsTrigger>
          <TabsTrigger value="report">{t("ui.report")}</TabsTrigger>
        </TabsList>
        <TabsContent value="overview">
          <Card as="section" className="panel mb-5" aria-label={t("run.selection")}>
            <div className="mb-3 flex flex-wrap items-center gap-3"><h2 className="text-lg font-semibold">{t("run.selection")}</h2><StatusBadge label={profileName(run.profile)} tone="neutral" /><span className="text-sm text-muted-foreground">{t("run.moduleCount", {count:run.selectedModules.length})}</span></div>
            <p className="text-sm text-muted-foreground">{run.selectedModules.slice(0,3).map(id=>moduleName(id)).join(" · ")}</p>
            <details className="mt-3">
              <summary className="cursor-pointer text-sm font-semibold"><span className="disclosure-closed">{t("run.expandModules")}</span><span className="disclosure-open">{t("run.collapseModules")}</span></summary>
              <div className="mt-3 flex flex-wrap gap-2">{run.selectedModules.map(id=><span key={id} title={id} className="rounded-full border bg-muted px-3 py-1.5 text-xs">{moduleName(id)}</span>)}</div>
            </details>
            <p className="mt-3 text-xs text-muted-foreground">{t("ui.savedWhenTheRunWasCreatedLater")}</p>
          </Card>
          <div className="grid items-start gap-5 lg:grid-cols-2">
            <Card as="section" className="panel min-w-0">
              <h2 className="mb-5 text-lg font-semibold">{t("run.identity")}</h2>
              <dl className="grid gap-5 text-sm sm:grid-cols-2">
                {Object.entries({[t("ui.runID")]:run.id, [t("ui.created")]:formatDateTime(run.createdAt), [t("ui.started")]:formatDateTime(run.startedAt), [t("ui.finished")]:formatDateTime(run.finishedAt), [t("ui.duration")]:formatDuration(run.durationMs)}).map(([label,value]) => <div key={label}><dt className="mb-2 text-muted-foreground">{label}</dt><dd className="break-all">{value}</dd></div>)}
              </dl>
            </Card>
            <Card as="section" className="panel min-w-0">
              <h2 className="mb-5 text-lg font-semibold">{t("run.environment")}</h2>
              <dl className="grid gap-5 text-sm sm:grid-cols-2">
                {Object.entries({[t("ui.databaseType")]:run.connection.databaseType, [t("ui.adapter")]:run.connection.adapterId, [t("ui.driver")]:`${run.driver.name} · ${run.driver.version}`, [t("ui.vendor")]:run.driver.vendor, [t("ui.driverClass")]:run.driver.driverClass}).map(([label,value]) => <div key={label}><dt className="mb-2 text-muted-foreground">{label}</dt><dd className="break-words">{value}</dd></div>)}
              </dl>
              <div className="mt-5 border-t pt-4 text-sm"><h3 className="mb-2 font-medium">{t("ui.testScopeSavedForThisRun")}</h3>
                {run.executionScope ? <dl className="grid gap-3 sm:grid-cols-2">{[[t("scope.database"),run.executionScope.database],[t("scope.schema"),run.executionScope.schema]].map(([label,value]) => <div key={label}><dt className="text-muted-foreground">{label}</dt><dd className="mt-1 break-all">{value || t("scope.unspecified")}</dd></div>)}</dl> : <p className="text-muted-foreground">{t("scope.missing")}</p>}
              </div>
              <div className="mt-5 border-t pt-4"><JdbcUrl value={run.connection.jdbcUrl} /></div>
              <div className="mt-5 border-t pt-4"><p className="mb-2 text-xs text-muted-foreground">{t("ui.driverFingerprint")}</p><div className="flex items-center gap-2"><code className="break-all text-xs">{run.driver.packageFingerprint ?? t("ui.noFingerprint")}</code>{run.driver.packageFingerprint ? <CopyButton value={run.driver.packageFingerprint} label={t("ui.driverFingerprint")} /> : null}</div></div>
            </Card>
          </div>
        </TabsContent>
        <TabsContent value="cases">
          <Results id={id} modules={run.selectedModules} />
        </TabsContent>
        <TabsContent value="report">
          <Report run={run} />
        </TabsContent>
      </Tabs>
      <ConfirmDialog
        open={cancelling}
        onOpenChange={setCancelling}
        title={t("ui.cancelThisRun")}
        description={t("ui.theWorkerWillBeTerminatedCompletedResults")}
        label={t("ui.cancelRun")}
        pending={cancel.isPending}
        onConfirm={() => cancel.mutate()}
      />
    </>
  );
}
