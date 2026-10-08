import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { Plus } from "lucide-react";
import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { runsApi, runsKeys } from "@/api/runs";
import type { RunStatus } from "@/types/api";
import { runPresentation } from "@/lib/status";
import { Button } from "@/components/ui/button";
import { DataTable, Pagination } from "@/components/data-table";
import {
  EmptyState,
  ErrorState,
  Loading,
  PageHeader,
} from "@/components/common";
import { getRunColumns } from "./run-columns";
export default function Runs() {
  useTranslation();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<RunStatus | "">("");
  const [sort, setSort] = useState("createdAt,desc");
  const params = { page, size: 20, sort, status: status || undefined };
  const query = useQuery({
    queryKey: runsKeys.list(params),
    queryFn: ({ signal }) => runsApi.list(params, signal),
    placeholderData: keepPreviousData,
  });
  return (
    <>
      <PageHeader
        title={t("ui.runHistory")}
        action={<Button asChild><Link to="/runs/new"><Plus />{t("ui.newRun")}</Link></Button>}
        description={t("ui.openARunToViewItsTest")}
      />
      <div className="mb-5 flex flex-wrap gap-4">
        <label className="flex items-center gap-3 text-sm text-muted-foreground">
          {t("ui.status")}<select
            aria-label={t("ui.filterRunStatus")}
            className="select w-48"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as RunStatus | "");
              setPage(0);
            }}
          >
            <option value="">{t("ui.allStatuses")}</option>
            {Object.entries(runPresentation).map(([value, presentation]) => (
              <option key={value} value={value}>
                {presentation.label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex items-center gap-3 text-sm text-muted-foreground">
          {t("ui.sort")}<select
            aria-label={t("ui.sortRuns")}
            className="select w-48"
            value={sort}
            onChange={(event) => {
              setSort(event.target.value);
              setPage(0);
            }}
          >
            <option value="createdAt,desc">{t("ui.newestFirst")}</option>
            <option value="startedAt,desc">{t("ui.startedRecently")}</option>
            <option value="finishedAt,desc">{t("ui.finishedRecently")}</option>
          </select>
        </label>
      </div>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <div className="panel overflow-hidden p-0" aria-busy={query.isFetching}>
          <DataTable
            rows={query.data.items}
            columns={getRunColumns()}
            empty={
              <EmptyState
                title={t("ui.noTestRunsYet")}
                description={
                  status
                    ? t("ui.noRunsMatchThisStatus")
                    : t("ui.startYourFirstCompatibilityRunAndBuild")
                }
                action={
                  <Button asChild variant="outline">
                    <Link to="/runs/new">{t("ui.createATestRun")}</Link>
                  </Button>
                }
              />
            }
          />
          <Pagination {...query.data} onPage={setPage} />
        </div>
      )}
    </>
  );
}
