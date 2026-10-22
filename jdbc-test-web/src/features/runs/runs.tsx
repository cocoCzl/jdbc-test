import "./run-detail.scss";
import { Card } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { Plus } from "lucide-react";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { runsApi, runsKeys } from "@/api/runs";
import type { RunStatusGroup } from "@/types/api";
import { runStatusGroupOrder, runStatusGroups } from "@/lib/status";
import { plural } from "@/lib/format";
import { Button } from "@/components/ui/button";
import { DataTable, Pagination } from "@/components/data-table";
import {
  EmptyState,
  ErrorState,
  Loading,
} from "@/components/common";
import { getRunColumns } from "./run-columns";
export default function Runs() {
  useTranslation();
  const [page, setPage] = useState(0);
  const [statusGroup, setStatusGroup] = useState<RunStatusGroup | "">("");
  const [sort, setSort] = useState("createdAt,desc");
  const params = { page, size: 20, sort, statusGroup: statusGroup || undefined };
  const query = useQuery({
    queryKey: runsKeys.list(params),
    queryFn: ({ signal }) => runsApi.list(params, signal),
    refetchInterval: 10_000,
  });
  return (
    <Card as="section" className="panel run-history-panel overflow-hidden p-0" aria-busy={query.isFetching}>
      <div className="run-history-panel-header">
        <div>
          <h1 className="page-title">{t("ui.runHistory")}</h1>
          <p>{t("ui.openARunToViewItsTest")}{query.data ? ` · ${plural(query.data.totalElements, "record")}` : ""}</p>
        </div>
        <Button asChild><Link to="/runs/new"><Plus />{t("ui.newRun")}</Link></Button>
      </div>
      <div className="run-history-toolbar">
        <label>
          {t("ui.status")}<Select
            aria-label={t("ui.filterRunStatus")}
            value={statusGroup}
            onValueChange={(value) => {
              setStatusGroup(value as RunStatusGroup | "");
              setPage(0);
            }}
          >
            <option value="">{t("ui.allStatuses")}</option>
            {runStatusGroupOrder.map((value) => (
              <option key={value} value={value}>
                {runStatusGroups[value].label}
              </option>
            ))}
          </Select>
        </label>
        <label>
          {t("ui.sort")}<Select
            aria-label={t("ui.sortRuns")}
            value={sort}
            onValueChange={(value) => {
              setSort(value);
              setPage(0);
            }}
          >
            <option value="createdAt,desc">{t("ui.newestFirst")}</option>
            <option value="startedAt,desc">{t("ui.startedRecently")}</option>
            <option value="finishedAt,desc">{t("ui.finishedRecently")}</option>
          </Select>
        </label>
      </div>
      {query.isError && query.data ? <ErrorState error={query.error} retry={() => void query.refetch()} /> : null}
      {query.isPending ? (
        <Loading />
      ) : !query.data ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <>
          <DataTable
            rows={query.data.items}
            columns={getRunColumns()}
            className="run-table run-table--history"
            empty={
              <EmptyState
                title={statusGroup ? t("run.noMatchingRuns") : t("ui.noTestRunsYet")}
                description={
                  statusGroup
                    ? t("ui.noRunsMatchThisStatus")
                    : t("ui.startYourFirstCompatibilityRunAndBuild")
                }
                action={
                  statusGroup ? <Button variant="outline" onClick={() => {
                    setStatusGroup("");
                    setPage(0);
                  }}>{t("run.clearFilter")}</Button> : <Button asChild variant="outline">
                    <Link to="/runs/new">{t("ui.createATestRun")}</Link>
                  </Button>
                }
              />
            }
          />
          {query.data.totalElements > 0 ? <Pagination {...query.data} onPage={setPage} /> : null}
        </>
      )}
    </Card>
  );
}
