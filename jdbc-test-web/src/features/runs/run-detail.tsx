import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
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
  const progress = run.totalCases
    ? Math.min(100, (run.completedCases / run.totalCases) * 100)
    : 0;
  return (
    <>
      <PageHeader
        title={`Run ${shortId(run.id)}`}
        description={`${run.connection.name} · ${run.driver.name} ${run.driver.version} · ${run.connection.adapterId} · ${run.profile}`}
        action={
          <div className="flex items-center gap-3">
            <StatusBadge {...runPresentation[run.status]} />
            {!isTerminal(run.status) ? (
              <Button variant="destructive" onClick={() => setCancelling(true)}>
                Cancel run
              </Button>
            ) : null}
          </div>
        }
      />
      <div className="mb-6 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <code>{run.id}</code>
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
      <section className="mb-6 grid gap-4 sm:grid-cols-3 xl:grid-cols-5">
        <Metric label="Total" value={run.totalCases} />
        <Metric label="Completed" value={run.completedCases} />
        <Metric label="Passed" value={run.passed} />
        <Metric label="Failed" value={run.failed} />
        <Metric label="Skipped" value={run.skipped} />
      </section>
      <section className="panel mb-7">
        <div className="mb-3 flex justify-between text-sm">
          <span className="font-medium">Execution progress</span>
          <span className="font-mono text-xs">
            {run.completedCases} / {run.totalCases}
          </span>
        </div>
        <progress
          aria-label="Execution progress"
          className="h-2 w-full overflow-hidden rounded-full accent-primary"
          max={run.totalCases || 1}
          value={run.completedCases}
        />
        <p className="mt-3 text-xs text-muted-foreground">
          {Math.round(progress)}% complete · Duration{" "}
          {formatDuration(run.durationMs)}
        </p>
        {!isTerminal(run.status) ? (
          <div className="mt-5 grid gap-3 border-t pt-4 text-sm sm:grid-cols-2">
            <p>
              <span className="text-muted-foreground">Current module </span>
              {run.currentModule ?? "Waiting for worker"}
            </p>
            <p className="break-all">
              <span className="text-muted-foreground">Current case </span>
              {run.currentTestCase ?? "—"}
            </p>
          </div>
        ) : null}
        {run.failureReason ? (
          <p
            role="alert"
            className="mt-4 rounded-xl bg-rose-50 p-3 text-sm text-destructive"
          >
            {run.failureReason}
          </p>
        ) : null}
      </section>
      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">Overview</TabsTrigger>
          <TabsTrigger value="cases">Test cases</TabsTrigger>
          <TabsTrigger value="report">Report</TabsTrigger>
        </TabsList>
        <TabsContent value="overview">
          <section className="panel">
            <h2 className="mb-5 text-lg font-semibold">
              Immutable run snapshot
            </h2>
            <dl className="grid gap-6 text-sm sm:grid-cols-2 xl:grid-cols-3">
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
                  <dd>{value}</dd>
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
