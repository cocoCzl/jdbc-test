import "./run-detail.scss";
import { Card } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { moduleName } from "@/lib/presentation";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
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
            <section className="space-y-2 text-sm">
              <h3 className="font-semibold">{t("record.caseConclusion")}</h3>
              <p>{result.executionEvidence?.conclusion === "UNSUPPORTED_ALLOWED" ? t("selection.unsupportedNote") : t(`record.case.${result.status}`)}</p>
              {result.executionEvidence?.conclusion ? <p>{t(`selection.${result.executionEvidence.conclusion}`, { defaultValue: result.executionEvidence.conclusion })}</p> : null}
              {result.executionEvidence?.requirementScope === "SUITE" ? <p>{t("selection.suiteRequirementNote")}</p> : null}
              {result.status === "SKIPPED" ? <p className="text-muted-foreground">{t("skip.note")}</p> : null}
              {result.message ? <p className="whitespace-pre-wrap break-words">{result.message}</p> : null}
            </section>
            {result.executionEvidence?.testDomain === "SQL_FEATURE" ? <section className="space-y-3 text-sm"><h3 className="font-semibold">{t("sql.stepEvidence")}</h3>
              {result.executionEvidence.steps?.map((step, index) => <details key={index} className="rounded-lg border p-3"><summary className="cursor-pointer">{step.id} · {step.phase} · {step.status}</summary>
                {step.sql ? <pre className="run-evidence mt-2">{step.sql}</pre> : null}
                {step.expected !== undefined ? <><p>{t("sql.expected")}</p><pre className="run-evidence">{JSON.stringify(step.expected, null, 2)}</pre></> : null}
                {step.actual !== undefined || step.failure !== undefined ? <><p>{t("sql.actual")}</p><pre className="run-evidence">{JSON.stringify(step.actual !== undefined ? step.actual : step.failure, null, 2)}</pre></> : null}
              </details>)}</section> : null}
            {result.stackTrace ? <details><summary className="cursor-pointer text-sm font-medium">{t("ui.stackTrace")}</summary><pre className="run-evidence mt-2">{result.stackTrace}</pre></details> : null}
            {result.executionEvidence ? <details><summary className="cursor-pointer text-sm font-medium">{t("record.rawEvidence")}</summary><pre className="run-evidence mt-2">{JSON.stringify(result.executionEvidence, null, 2)}</pre></details> : null}
          </>
        ) : null}
      </div>
    </Dialog>
  );
}
export default function Results({
  id,
  modules,
  initialStatus = "",
}: {
  id: string;
  modules: string[];
  initialStatus?: ResultStatus | "";
}) {
  useTranslation();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ResultStatus | "">(initialStatus);
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
      cell: ({ row }) => <Button variant="ghost" size="sm" className="text-primary" onClick={() => setSelected(row.original)}>{t("record.viewDetails")}</Button>,
    },
  ];
  return (
    <>
      <div className="run-result-toolbar">
        <label className="text-xs text-muted-foreground">
          {t("ui.status")}<Select
            aria-label={t("ui.filterResultStatus")}

            value={status}
            onValueChange={(value) => {
              setStatus(value as ResultStatus | "");
              setPage(0);
            }}
          >
            <option value="">{t("ui.allResults")}</option>
            {Object.entries(resultPresentation).map(([value, item]) => (
              <option value={value} key={value}>
                {item.label}
              </option>
            ))}
          </Select>
        </label>
        <label className="text-xs text-muted-foreground">
          {t("ui.module")}<Select
            aria-label={t("ui.filterResultModule")}

            value={moduleId}
            onValueChange={(value) => {
              setModule(value);
              setPage(0);
            }}
          >
            <option value="">{t("ui.allModules")}</option>
            {modules.map((module) => (
              <option key={module} value={module}>{moduleName(module)}</option>
            ))}
          </Select>
        </label>
        <label className="text-xs text-muted-foreground">
          {t("ui.sort")}<Select
            aria-label={t("ui.sortResults")}

            value={sort}
            onValueChange={(value) => {
              setSort(value);
              setPage(0);
            }}
          >
            <option value="scenarioId,asc">{t("ui.scenarioAZ")}</option>
            <option value="durationMs,desc">{t("ui.longestFirst")}</option>
            <option value="status,asc">{t("ui.status")}</option>
          </Select>
        </label>
      </div>
      {query.isError && query.data ? <ErrorState error={query.error} retry={() => void query.refetch()} /> : null}
      {query.isPending ? (
        <Loading />
      ) : !query.data ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <Card className="panel overflow-hidden p-0" aria-busy={query.isFetching}>
          <DataTable
            rows={query.data.items}
            columns={columns}
            className="run-case-table"
            empty={
              <EmptyState
                title={t("ui.noCaseResultsYet")}
                description={t("ui.casesAppearAsTheWorkerCompletesThem")}
              />
            }
          />
          {query.data.totalElements > 0 ? <Pagination {...query.data} onPage={setPage} /> : null}
        </Card>
      )}
      <FailureDetail result={selected} close={() => setSelected(null)} />
    </>
  );
}
