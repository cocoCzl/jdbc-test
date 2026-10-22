import { RunTechnical } from "./run-technical";
import { completedWithFailures, failureExplanation, failurePhaseLabel, lifecycleLabel, outcomePresentation } from "./run-outcome";
import "./run-detail.scss";
import { CleanupEvidence } from "./cleanup-evidence";
import { Card } from "@/components/ui/card";
import { JdbcUrl } from "@/components/jdbc-url";
import { moduleName, profileName, runTitle } from "@/lib/presentation";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { ArrowLeft, Ban, CircleDashed, Clock, LoaderCircle, Play, Plus, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { toast } from "sonner";
import { ApiError, errorMessage } from "@/api/client";
import { runsApi, runsKeys } from "@/api/runs";
import { useRunEvents } from "@/hooks/use-run-events";
import { isTerminal } from "@/lib/status";
import { formatDateTime, formatDuration, formatNumber } from "@/lib/format";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/alert-dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  ErrorState,
  PendingPage,
  PageHeader,
  StatusBadge,
} from "@/components/common";
import Results from "./results";
import Report from "./report";
export default function RunDetail() {
  useTranslation();
  const id = useParams().id!;
  const client = useQueryClient();
  const [tab, setTab] = useState("overview");
  const [unsuccessfulVisit, setUnsuccessfulVisit] = useState(0);
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
    <div className="workspace-page run-detail">
      <nav className="run-breadcrumb" aria-label={t("record.breadcrumb")}>
        <Link to="/runs"><ArrowLeft className="size-4" />{t("record.back")}</Link><span aria-hidden="true">/</span><span>{t("ui.runDetail")}</span>
      </nav>
      {query.isError ? <ErrorState error={query.error} retry={() => void query.refetch()} /> : null}
      <PageHeader
        title={runTitle(run)}
        description=""
        action={
          <div className="run-header-actions flex flex-wrap items-center gap-3">
            <StatusBadge {...outcomePresentation(run)} />
            {!isTerminal(run.status) ? (
              <Button variant="destructive" onClick={() => setCancelling(true)}>
                {t("ui.cancelRun")}</Button>
            ) : <Button asChild><Link to="/runs/new"><Plus />{t("ui.newRun")}</Link></Button>}
          </div>
        }
      />
      {!terminal ? <div className="mb-4 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <span className="ml-auto" role="status">
          {live === "live"
            ? t("ui.liveUpdatesConnected")
            : live === "reconnecting"
              ? t("ui.liveUpdatesReconnecting")
              : live === "connecting"
                ? t("ui.connectingLiveUpdates")
                : t("record.ended")}
        </span>
      </div> : null}
      <section aria-label={t("ui.runOutcomeSummary")} className="run-statistics">
        {([
          ["ui.total", run.totalCases, "total"], ["ui.passed", run.passed, "success"],
          ["ui.failed", run.failed, "danger"], ["ui.skipped", run.skipped, "neutral"],
        ] as const).map(([label, value, tone]) => <div key={label} className={`run-statistic run-statistic--${tone}`} data-metric={t(label)}>
          <p className="run-statistic-label">{t(label)}</p>
          <p className="run-statistic-value">{formatNumber(value)}</p>
        </div>)}
      </section>
      <Card as="section" className="panel mb-4" aria-label={t("ui.executionAccounting")}>
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-sm">
          <p className="font-medium">{t("ui.executionProgress")} <span className="ml-2 font-normal text-muted-foreground">{!terminal ? lifecycleLabel(run) : ""}</span></p>
          <span className="font-mono text-sm">{t("record.processed", { completed: formatNumber(run.completedCases), total: formatNumber(run.totalCases) })}</span>
        </div>
        <progress aria-label={t("ui.executionProgress")}
          className={`run-progress h-2 w-full overflow-hidden rounded-full tone-${outcomePresentation(run).tone}`}
          max={run.totalCases || 1} value={run.completedCases ?? undefined} />
        <p className="mt-2 text-xs text-muted-foreground">
          {progress == null ? t("ui.progressUnavailable") : t("record.processedPercent", {percent: Math.round(progress)})}  {t("ui.durationInline")} {formatDuration(run.durationMs)}
        </p>
        <dl className="mt-3 flex flex-wrap gap-x-6 gap-y-2 border-t pt-3 text-sm" aria-label={t("ui.executionCounters")}>
          {secondary.map(({id,icon: Icon,label,value}, index) => <div key={id} data-stat={label}
            className={`flex items-center gap-2 ${index > 0 && value != null && value > 0 ? terminal ? "font-semibold text-warning" : "font-semibold text-primary" : "text-muted-foreground"}`}>
            <dt className="flex items-center gap-1.5"><Icon className="size-3.5" aria-hidden="true" />{label}</dt><dd className="font-semibold tabular-nums">{formatNumber(value)}</dd>
          </div>)}
        </dl>
        {!run.accounting ? <p className="mt-2 text-xs text-muted-foreground">{t("ui.legacyRunExecutionStartAndInterruptionEvidence")}</p> : null}
        {run.accounting?.started === 0 && terminal ? <p className="mt-2 text-sm text-warning">{t("ui.noScenarioExecutionWasVerifiedSkippedCases")}</p> : null}
        {run.status === "RUNNING" ? <div className="mt-3 grid gap-2 border-t pt-3 text-sm sm:grid-cols-2">
          <p><span className="text-muted-foreground">{t("ui.currentModule")} </span>{run.accounting?.running === 0 ? "—" : run.currentModule ? moduleName(run.currentModule) : t("ui.waitingForWorker")}</p>
          <p className="break-all"><span className="text-muted-foreground">{t("ui.currentCase")} </span>{run.accounting?.running === 0 ? t("record.noActiveCase") : run.currentTestCase ?? "—"}</p>
        </div> : null}
        {failureExplanation(run) ? <div role="alert" className="mt-3 rounded-lg border p-3 text-sm">
          <p>{failureExplanation(run)}</p>
          {completedWithFailures(run) ? <Button variant="ghost" size="sm" className="mt-2 text-primary"
            onClick={() => { setUnsuccessfulVisit(previous => previous + 1); setTab("cases"); }}>{t("record.viewUnsuccessful")}</Button> : null}
          {run.executionIssues?.map((issue, index) => <p key={index} className="mt-2 break-words">{t("record.failureStage")}: {failurePhaseLabel(issue.phase)} · {String(issue.message ?? t("record.unknownCause"))}</p>)}
        </div> : null}
      </Card>
      {run.cleanupEvidence && (run.cleanupEvidence.status === "FAILED" || !!run.cleanupEvidence.retainedObjects?.length) ? <CleanupEvidence evidence={run.cleanupEvidence} /> : null}
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="overview">{t("ui.overview")}</TabsTrigger>
          <TabsTrigger value="cases">{t("ui.testCases")}</TabsTrigger>
          <TabsTrigger value="report">{t("ui.report")}</TabsTrigger>
        </TabsList>
        <TabsContent value="overview">
          {!run.cleanupEvidence || (run.cleanupEvidence.status !== "FAILED" && !run.cleanupEvidence.retainedObjects?.length) ? <CleanupEvidence evidence={run.cleanupEvidence} /> : null}
          <div className="run-summary-grid">
            <Card as="section" className="panel min-w-0">
              <h2>{t("run.environment")}</h2>
              <dl>{[[t("ui.connection"), run.connection.name], [t("ui.databaseType"), run.connection.databaseType],
                [t("ui.driver"), `${run.driver.name} · ${run.driver.version}`],
                [t("connection.sqlDialect"), run.sqlDialect ? t(`driverPreset.${run.sqlDialect}`, { defaultValue: run.sqlDialect }) : t("record.notRecorded")],
                [t("scope.database"), run.executionScope?.database || t("scope.unspecified")],
                [t("scope.schema"), run.executionScope?.schema || t("scope.unspecified")]].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
              <div className="mt-4 border-t pt-4"><JdbcUrl value={run.connection.jdbcUrl} compact /></div>
            </Card>
            <Card as="section" className="panel min-w-0">
              <h2>{t("run.identity")}</h2>
              <dl>{[[t("ui.created"), formatDateTime(run.createdAt)], [t("ui.started"), formatDateTime(run.startedAt)],
                [t("ui.finished"), formatDateTime(run.finishedAt)], [t("ui.duration"), formatDuration(run.durationMs)]].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
            </Card>
          </div>
          <Card as="section" className="panel mt-4" aria-label={t("run.selection")}>
            <div className="mb-3 flex flex-wrap items-center gap-3"><h2 className="text-lg font-semibold">{t("run.selection")}</h2><StatusBadge label={profileName(run.profile)} tone="neutral" /><span className="text-sm text-muted-foreground">{t("run.moduleCount", {count:run.selectedModules.length})}</span></div>
            <p className="text-sm text-muted-foreground">{run.selectedModules.slice(0,3).map(id=>moduleName(id)).join(" · ")}</p>
            <details className="mt-3">
              <summary className="cursor-pointer text-sm font-semibold"><span className="disclosure-closed">{t("run.expandModules")}</span><span className="disclosure-open">{t("run.collapseModules")}</span></summary>
              <div className="mt-3 flex flex-wrap gap-2">{run.selectedModules.map(id=><span key={id} title={id} className="rounded-full border bg-muted px-3 py-1.5 text-xs">{moduleName(id)}</span>)}</div>
            </details>
            <p className="mt-3 text-xs text-muted-foreground">{t("ui.savedWhenTheRunWasCreatedLater")}</p>
          </Card>
          <details className="run-statistics-note"><summary>{t("ui.aboutTheseStatistics")}</summary><p>{t("run.completedNote")}</p></details>
          <RunTechnical run={run} />
        </TabsContent>
        <TabsContent value="cases">
          <Results key={unsuccessfulVisit} id={id} modules={run.selectedModules} initialStatus={unsuccessfulVisit ? run.failed > 0 ? "FAILED" : "ABORTED" : ""} />
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
    </div>
  );
}
