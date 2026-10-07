import { lazy, Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { runsApi, runsKeys } from "@/api/runs";
import type { CaseResult, Run } from "@/types/api";
import { abortedCount } from "@/lib/outcomes";
import { isTerminal } from "@/lib/status";
import { formatDateTime, formatDuration } from "@/lib/format";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState, ErrorState, Loading } from "@/components/common";
import { FailureDetail } from "./results";
const OutcomeChart = lazy(() => import("@/components/outcome-chart"));
export default function Report({ run }: { run: Run }) {
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
  if (!isTerminal(run.status))
    return (
      <EmptyState
        title="Your report is in progress"
        description="The report becomes available after the run reaches a terminal state."
      />
    );
  if (query.isPending) return <Loading />;
  if (query.isError)
    return (
      <ErrorState error={query.error} retry={() => void query.refetch()} />
    );
  const summary = query.data.summary;
  const aborted = abortedCount(summary);
  return (
    <>
      {summary.accounting ? <p className="mb-4 text-sm">
        {summary.accounting.notRun} not run · {summary.accounting.incomplete} started but incomplete.
        {summary.accounting.started === 0 ? " No scenario execution was verified; skipped cases do not establish compatibility." : ""}
      </p> : null}
      {summary.executionIssues?.length ? <div className="panel mb-4"><h2>Execution issues</h2>
        {summary.executionIssues.map((issue, index) => <p key={index} className="break-words">{String(issue.level)}: {String(issue.message ?? issue.status)}</p>)}
      </div> : null}
      <div className="mb-6 grid gap-6 xl:grid-cols-[340px_1fr]">
        <section className="panel">
          <h2 className="text-lg font-semibold">Test outcomes</h2>
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
              title="No completed cases"
              description="This run ended before case results were recorded."
            />
          )}
          <p className="mt-3 text-center text-sm text-muted-foreground">
            {summary.passed ?? "—"} passed · {summary.failed ?? "—"} failed ·{" "}
            {summary.skipped ?? "—"} skipped · {aborted ?? "—"} aborted
          </p>
        </section>
        <section className="panel">
          <h2 className="mb-5 text-lg font-semibold">Captured environment</h2>
          <dl className="grid gap-5 text-sm sm:grid-cols-2">
            {Object.entries({
              Connection: summary.connection.name,
              "Database type": summary.connection.databaseType,
              Adapter: summary.connection.adapterId,
              Driver: `${summary.driver.name} · ${summary.driver.version}`,
              "Driver class": summary.driver.driverClass,
              Profile: summary.profile,
              Started: formatDateTime(summary.startedAt),
              Finished: formatDateTime(summary.finishedAt),
              Duration: formatDuration(summary.durationMs),
            }).map(([label, value]) => (
              <div key={label}>
                <dt className="mb-1 text-muted-foreground">{label}</dt>
                <dd className="break-all">{value}</dd>
              </div>
            ))}
          </dl>
          <p className="mt-5 break-all font-mono text-xs text-muted-foreground">
            {summary.connection.jdbcUrl}
          </p>
          <div className="mt-5 border-t pt-4">
            <h3 className="text-sm font-medium">Selected modules</h3>
            <p className="mt-2 text-sm text-muted-foreground">
              {summary.selectedModules.join(", ") || "—"}
            </p>
            <p className="mt-3 text-xs text-muted-foreground">
                  Selection captured when this run was created.
            </p>
          </div>
        </section>
      </div>
      <section className="panel">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-semibold">Failures & aborted cases</h2>
          <Button variant="outline" onClick={() => setRawOpen(true)}>
            View raw JSON
          </Button>
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
                    {item.scenarioId} · {item.status}
                  </p>
                </div>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setFailure(item)}
                >
                  Inspect failure
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-5 text-sm text-muted-foreground">
            No failed or aborted cases were recorded.
          </p>
        )}
      </section>
      <FailureDetail result={failure} close={() => setFailure(null)} />
      <Dialog
        open={rawOpen}
        onOpenChange={setRawOpen}
        title="Raw report JSON"
        description="The sanitized report artifact returned by the server."
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
                anchor.download = `jdbc-test-${run.id}.json`;
                anchor.click();
                URL.revokeObjectURL(url);
              }}
            >
              Download JSON
            </Button>
            <pre className="max-h-[55vh] overflow-auto whitespace-pre-wrap break-words rounded-xl bg-background p-4 font-mono text-xs">
              {JSON.stringify(raw.data, null, 2)}
            </pre>
          </>
        )}
      </Dialog>
    </>
  );
}
