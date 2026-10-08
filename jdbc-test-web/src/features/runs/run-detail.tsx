import { moduleName, profileName } from "@/lib/presentation";
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
  Loading,
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
  if (query.isPending) return <Loading />;
  if (query.isError)
    return (
      <ErrorState error={query.error} retry={() => void query.refetch()} />
    );
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
      <PageHeader
        title={`${run.selectedModules.map(id => moduleName(id)).join(" + ") || profileName(run.profile)} · ${run.connection.name}`}
        description={`${run.connection.name} · ${run.driver.name} ${run.driver.version} · ${run.connection.adapterId} · ${profileName(run.profile)}`}
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
        <span>{t("ui.created")} {formatDateTime(run.createdAt)}</span>
        <span>{t("ui.started")} {formatDateTime(run.startedAt)}</span>
        <span>{t("ui.duration")} {formatDuration(run.durationMs)}</span>
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
      <section className="panel mb-4" aria-label={t("ui.executionAccounting")}>
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
        <p className="mt-2 text-xs text-muted-foreground">{t("run.completedNote")}</p>
        <dl className="mt-3 flex flex-wrap gap-x-6 gap-y-2 border-t pt-3 text-sm" aria-label={t("ui.executionCounters")}>
          {secondary.map(({id,icon: Icon,label,value}, index) => <div key={id} data-stat={label}
            className={`flex items-center gap-2 ${index > 0 && value != null && value > 0 ? terminal ? "font-semibold text-warning" : "font-semibold text-primary" : "text-muted-foreground"}`}>
            <dt className="flex items-center gap-1.5"><Icon className="size-3.5" aria-hidden="true" />{label}</dt><dd className="font-semibold tabular-nums">{formatNumber(value)}</dd>
          </div>)}
        </dl>
        {!run.accounting ? <p className="mt-2 text-xs text-muted-foreground">{t("ui.legacyRunExecutionStartAndInterruptionEvidence")}</p> : null}
        {run.accounting?.started === 0 && terminal ? <p className="mt-2 text-sm text-warning">{t("ui.noScenarioExecutionWasVerifiedSkippedCases")}</p> : null}
        {run.executionScope ? <p className="mt-2 text-xs text-muted-foreground">{t("ui.testScopeSavedForThisRun")} {run.executionScope.database || "—"} / {run.executionScope.schema || "—"}</p> : null}
        {!terminal ? <div className="mt-3 grid gap-2 border-t pt-3 text-sm sm:grid-cols-2">
          <p><span className="text-muted-foreground">{t("ui.currentModule")} </span>{run.currentModule ? moduleName(run.currentModule) : t("ui.waitingForWorker")}</p>
          <p className="break-all"><span className="text-muted-foreground">{t("ui.currentCase")} </span>{run.currentTestCase ?? "—"}</p>
        </div> : null}
        {run.failureReason ? <p role="alert" className="mt-3 break-words rounded-lg bg-destructive/5 p-3 text-sm text-destructive">{run.failureReason}</p> : null}
      </section>
      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">{t("ui.overview")}</TabsTrigger>
          <TabsTrigger value="cases">{t("ui.testCases")}</TabsTrigger>
          <TabsTrigger value="report">{t("ui.report")}</TabsTrigger>
        </TabsList>
        <TabsContent value="overview">
          <section className="panel">
            <h2 className="mb-3 text-lg font-semibold">
              {t("ui.runConfiguration")}</h2>
            <p className="mb-4 text-sm text-muted-foreground">{t("ui.savedWhenTheRunWasCreatedLater")}</p>
            <dl className="grid gap-3 text-sm sm:grid-cols-2 xl:grid-cols-3">
              {Object.entries({
                [t("ui.connection")]: run.connection.name,
                [t("ui.databaseType")]: run.connection.databaseType,
                [t("ui.adapter")]: run.connection.adapterId,
                [t("ui.driver")]: `${run.driver.name} · ${run.driver.version}`,
                [t("ui.vendor")]: run.driver.vendor,
                [t("ui.profile")]: profileName(run.profile),
                [t("ui.started")]: formatDateTime(run.startedAt),
                [t("ui.finished")]: formatDateTime(run.finishedAt),
                [t("ui.duration")]: formatDuration(run.durationMs),
              }).map(([label, value]) => (
                <div key={label}>
                  <dt className="mb-2 text-muted-foreground">{label}</dt>
                  <dd className="break-words">{value}</dd>
                </div>
              ))}
            </dl>
            <div className="mt-6 space-y-3 border-t pt-5 text-sm">
              <p>
                {t("ui.selectedModules")}{" "}
                <span className="text-muted-foreground">
                  {run.selectedModules.map(id => moduleName(id)).join(", ")}
                </span>
              </p>
              <p className="break-all font-mono text-xs">
                {run.connection.jdbcUrl}
              </p>
              <div className="flex items-center gap-2">
                <code className="break-all text-xs">
                  {run.driver.packageFingerprint ?? t("ui.noFingerprint")}
                </code>
                {run.driver.packageFingerprint ? (
                  <CopyButton
                    value={run.driver.packageFingerprint}
                    label={t("ui.driverFingerprint")}
                  />
                ) : null}
              </div>
            </div>
          </section>
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
