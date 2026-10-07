import { Check, CircleDashed, ListChecks, Plus, X } from "lucide-react";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { toast } from "sonner";
import { ApiError, errorMessage } from "@/api/client";
import { runsApi, runsKeys } from "@/api/runs";
import { useRunEvents } from "@/hooks/use-run-events";
import { isTerminal, runPresentation } from "@/lib/status";
import { formatDateTime, formatDuration, shortId } from "@/lib/format";
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
      toast.success("Run cancelled");
      void client.invalidateQueries({ queryKey: runsKeys.all });
    },
    onError: (error) => {
      setCancelling(false);
      if (error instanceof ApiError && error.code === "RUN_ALREADY_FINISHED") {
        void query.refetch();
        toast.info(error.message);
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
    { label: "Started", value: run.accounting?.started },
    { label: "Aborted", value: run.accounting?.aborted },
    { label: terminal ? "Not run" : "Pending", value: terminal ? run.accounting?.notRun : run.accounting?.pending },
    { label: terminal ? "Incomplete" : "Running", value: terminal ? run.accounting?.incomplete : run.accounting?.running },
  ];
  return (
    <>
      <PageHeader
        title={`${run.selectedModules.join(" + ") || run.profile} · ${run.connection.name}`}
        description={`${run.connection.name} · ${run.driver.name} ${run.driver.version} · ${run.connection.adapterId} · ${run.profile}`}
        action={
          <div className="flex items-center gap-3">
            <StatusBadge {...runPresentation[run.status]} />
            {!isTerminal(run.status) ? (
              <Button variant="destructive" onClick={() => setCancelling(true)}>
                Cancel run
              </Button>
            ) : <Button asChild><Link to="/runs/new"><Plus />New run</Link></Button>}
          </div>
        }
      />
      <div className="mb-4 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <code title={run.id}>{shortId(run.id)}</code>
        <CopyButton value={run.id} label="Run ID" />
        <span>Created {formatDateTime(run.createdAt)}</span>
        <span>Started {formatDateTime(run.startedAt)}</span>
        <span>Duration {formatDuration(run.durationMs)}</span>
        <span className="ml-auto" role="status">
          {live === "live"
            ? "Live updates connected"
            : live === "reconnecting"
              ? "Live updates reconnecting…"
              : live === "connecting"
                ? "Connecting live updates…"
                : "Run complete"}
        </span>
      </div>
      <section aria-label="Run outcome summary" className="mb-3 grid grid-cols-2 gap-3 md:grid-cols-4">
        <Metric label="Total" value={run.totalCases ?? "—"} icon={<ListChecks className="size-4" />} />
        <Metric label="Passed" tone="success" subdued={run.passed == null || run.passed === 0} value={run.passed ?? "—"} icon={<Check className="size-4" />} />
        <Metric label="Failed" tone="danger" subdued={run.failed == null || run.failed === 0} value={run.failed ?? "—"} icon={<X className="size-4" />} />
        <Metric label="Skipped" tone="neutral" subdued={run.skipped == null || run.skipped === 0} value={run.skipped ?? "—"} icon={<CircleDashed className="size-4" />} />
      </section>
      <section className="panel mb-4" aria-label="Execution accounting">
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-sm">
          <p className="font-medium">Execution progress <span className="ml-2 font-normal text-muted-foreground">Completed</span></p>
          <span className="font-mono text-sm">{run.completedCases ?? "—"} / {run.totalCases ?? "—"}</span>
        </div>
        <progress aria-label="Execution progress"
          className={`run-progress h-2 w-full overflow-hidden rounded-full tone-${run.status === "SUCCEEDED" && run.passed > 0 ? "success" : runPresentation[run.status].tone}`}
          max={run.totalCases || 1} value={run.completedCases ?? undefined} />
        <p className="mt-2 text-xs text-muted-foreground">
          {progress == null ? "Progress unavailable" : `${Math.round(progress)}% complete`} · Duration {formatDuration(run.durationMs)}
        </p>
        <dl className="mt-3 flex flex-wrap gap-x-6 gap-y-2 border-t pt-3 text-sm" aria-label="Execution counters">
          {secondary.map(({label,value}, index) => <div key={label} data-stat={label}
            className={`flex items-center gap-2 ${index > 0 && value != null && value > 0 ? terminal ? "font-semibold text-warning" : "font-semibold text-primary" : "text-muted-foreground"}`}>
            <dt>{label}</dt><dd className="font-semibold tabular-nums">{value ?? "—"}</dd>
          </div>)}
        </dl>
        {!run.accounting ? <p className="mt-2 text-xs text-muted-foreground">Legacy run: execution start and interruption evidence is unavailable.</p> : null}
        {run.accounting?.started === 0 && terminal ? <p className="mt-2 text-sm text-warning">No scenario execution was verified; skipped cases do not establish compatibility.</p> : null}
        {run.executionScope ? <p className="mt-2 text-xs text-muted-foreground">Test scope saved for this run: {run.executionScope.database || "—"} / {run.executionScope.schema || "—"}</p> : null}
        {!terminal ? <div className="mt-3 grid gap-2 border-t pt-3 text-sm sm:grid-cols-2">
          <p><span className="text-muted-foreground">Current module </span>{run.currentModule ?? "Waiting for worker"}</p>
          <p className="break-all"><span className="text-muted-foreground">Current case </span>{run.currentTestCase ?? "—"}</p>
        </div> : null}
        {run.failureReason ? <p role="alert" className="mt-3 break-words rounded-lg bg-destructive/5 p-3 text-sm text-destructive">{run.failureReason}</p> : null}
      </section>
      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">Overview</TabsTrigger>
          <TabsTrigger value="cases">Test cases</TabsTrigger>
          <TabsTrigger value="report">Report</TabsTrigger>
        </TabsList>
        <TabsContent value="overview">
          <section className="panel">
            <h2 className="mb-3 text-lg font-semibold">
              Run configuration
            </h2>
            <p className="mb-4 text-sm text-muted-foreground">Saved when the run was created; later connection edits do not change this run. Dates use your browser’s local time.</p>
            <dl className="grid gap-3 text-sm sm:grid-cols-2 xl:grid-cols-3">
              {Object.entries({
                Connection: run.connection.name,
                "Database type": run.connection.databaseType,
                Adapter: run.connection.adapterId,
                Driver: `${run.driver.name} · ${run.driver.version}`,
                Vendor: run.driver.vendor,
                Profile: run.profile,
                Started: formatDateTime(run.startedAt),
                Finished: formatDateTime(run.finishedAt),
                Duration: formatDuration(run.durationMs),
              }).map(([label, value]) => (
                <div key={label}>
                  <dt className="mb-2 text-muted-foreground">{label}</dt>
                  <dd className="break-words">{value}</dd>
                </div>
              ))}
            </dl>
            <div className="mt-6 space-y-3 border-t pt-5 text-sm">
              <p>
                Selected modules{" "}
                <span className="text-muted-foreground">
                  {run.selectedModules.join(", ")}
                </span>
              </p>
              <p className="break-all font-mono text-xs">
                {run.connection.jdbcUrl}
              </p>
              <div className="flex items-center gap-2">
                <code className="break-all text-xs">
                  {run.driver.packageFingerprint ?? "No fingerprint"}
                </code>
                {run.driver.packageFingerprint ? (
                  <CopyButton
                    value={run.driver.packageFingerprint}
                    label="driver fingerprint"
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
        title="Cancel this run?"
        description="The worker will be terminated. Completed results are retained, but remaining scenarios will not execute."
        label="Cancel run"
        pending={cancel.isPending}
        onConfirm={() => cancel.mutate()}
      />
    </>
  );
}
