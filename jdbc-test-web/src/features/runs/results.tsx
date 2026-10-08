import { moduleName } from "@/lib/presentation";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { runsApi, runsKeys } from "@/api/runs";
import type { CaseResult, ResultStatus } from "@/types/api";
import { resultPresentation } from "@/lib/status";
import { formatDuration } from "@/lib/format";
import { DataTable, Pagination } from "@/components/data-table";
import { Dialog } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import {
  EmptyState,
  ErrorState,
  Loading,
  StatusBadge,
} from "@/components/common";
export function FailureDetail({
  result,
  close,
}: {
  result: CaseResult | null;
  close: () => void;
}) {
  useTranslation();
  return (
    <Dialog
      open={!!result}
      onOpenChange={(open) => {
        if (!open) close();
      }}
      title={result?.displayName ?? t("ui.caseDetail")}
      description={`${result?.scenarioId ?? ""} · ${result?.moduleId ?? ""}`}
    >
      <div className="space-y-4">
        {result ? (
          <>
            <div className="flex items-center gap-4">
              <StatusBadge {...resultPresentation[result.status]} />
              <span className="text-sm">
                {formatDuration(result.durationMs)}
              </span>
            </div>
            <section>
              <h3 className="mb-2 text-sm font-medium">{result.status === "SKIPPED" ? t("skip.title") : t("ui.message")}</h3>
              {result.status === "SKIPPED" ? <p className="mb-2 text-sm text-muted-foreground">{t("skip.note")}</p> : null}
              <pre className="whitespace-pre-wrap break-words rounded-xl bg-background p-4 font-mono text-xs">
                {result.message ?? t("ui.noMessageProvided")}
              </pre>
            </section>
            <section>
              <h3 className="mb-2 text-sm font-medium">{t("ui.stackTrace")}</h3>
              <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-xl bg-background p-4 font-mono text-xs">
                {result.stackTrace ?? t("ui.noStackTraceProvided")}
              </pre>
            </section>
          </>
        ) : null}
      </div>
    </Dialog>
  );
}
export default function Results({
  id,
  modules,
}: {
  id: string;
  modules: string[];
}) {
  useTranslation();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ResultStatus | "">("");
  const [moduleId, setModule] = useState("");
  const [sort, setSort] = useState("scenarioId,asc");
  const [selected, setSelected] = useState<CaseResult | null>(null);
  const params = {
    page,
    size: 20,
    sort,
    status: status || undefined,
    moduleId: moduleId || undefined,
  };
  const query = useQuery({
    queryKey: [...runsKeys.results(id), params],
    queryFn: ({ signal }) => runsApi.results(id, params, signal),
    placeholderData: keepPreviousData,
  });
  const columns: ColumnDef<CaseResult>[] = [
    {
      id: "ui.status",
      get header() { return t("ui.status"); },
      cell: ({ row }) => (
        <StatusBadge {...resultPresentation[row.original.status]} />
      ),
    },
    {
      id: "ui.module", get header() { return t("ui.module"); }, cell: ({ row }) => moduleName(row.original.moduleId) },
    {
      id: "ui.scenario",
      get header() { return t("ui.scenario"); },
      cell: ({ row }) => (
        <code className="text-xs">{row.original.scenarioId}</code>
      ),
    },
    { get header() { return t("ui.displayName"); }, accessorKey: "displayName" },
    {
      id: "ui.duration",
      get header() { return t("ui.duration"); },
      cell: ({ row }) => formatDuration(row.original.durationMs),
    },
    {
      id: "ui.detail",
      get header() { return t("ui.detail"); },
      cell: ({ row }) =>
        ["FAILED", "ABORTED", "SKIPPED"].includes(row.original.status) ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setSelected(row.original)}
          >
            {row.original.status === "SKIPPED" ? t("skip.title") : t("ui.inspectFailure")}</Button>
        ) : (
          "—"
        ),
    },
  ];
  return (
    <>
      <div className="mb-5 flex flex-wrap gap-3">
        <label className="text-xs text-muted-foreground">
          {t("ui.status")}<select
            aria-label={t("ui.filterResultStatus")}
            className="select mt-1 w-40"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as ResultStatus | "");
              setPage(0);
              setModule("");
            }}
          >
            <option value="">{t("ui.allResults")}</option>
            {Object.entries(resultPresentation).map(([value, item]) => (
              <option value={value} key={value}>
                {item.label}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs text-muted-foreground">
          {t("ui.module")}<select
            aria-label={t("ui.filterResultModule")}
            className="select mt-1 w-48"
            value={moduleId}
            disabled={!!status}
            onChange={(event) => {
              setModule(event.target.value);
              setPage(0);
            }}
          >
            <option value="">{t("ui.allModules")}</option>
            {modules.map((module) => (
              <option key={module} value={module}>{moduleName(module)}</option>
            ))}
          </select>
        </label>
        <label className="text-xs text-muted-foreground">
          {t("ui.sort")}<select
            aria-label={t("ui.sortResults")}
            className="select mt-1 w-48"
            value={sort}
            onChange={(event) => {
              setSort(event.target.value);
              setPage(0);
            }}
          >
            <option value="scenarioId,asc">{t("ui.scenarioAZ")}</option>
            <option value="durationMs,desc">{t("ui.longestFirst")}</option>
            <option value="status,asc">{t("ui.status")}</option>
          </select>
        </label>
      </div>
      {status ? (
        <p className="mb-4 text-xs text-muted-foreground">
          {t("ui.clearTheStatusFilterToFilterResults")}</p>
      ) : null}
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <div className="panel overflow-hidden p-0" aria-busy={query.isFetching}>
          <DataTable
            rows={query.data.items}
            columns={columns}
            empty={
              <EmptyState
                title={t("ui.noCaseResultsYet")}
                description={t("ui.casesAppearAsTheWorkerCompletesThem")}
              />
            }
          />
          <Pagination {...query.data} onPage={setPage} />
        </div>
      )}
      <FailureDetail result={selected} close={() => setSelected(null)} />
    </>
  );
}
