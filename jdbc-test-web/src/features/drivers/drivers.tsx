import { Card } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { useState } from "react";
import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { Plus, ShieldCheck, Trash2 } from "lucide-react";
import type { ColumnDef } from "@tanstack/react-table";
import { toast } from "sonner";
import { driversApi, driversKeys } from "@/api/drivers";
import { errorMessage } from "@/api/client";
import type { Driver } from "@/types/api";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/alert-dialog";
import { DataTable, Pagination } from "@/components/data-table";
import {
  EmptyState,
  ErrorState,
  Loading,
  StatusBadge,
} from "@/components/common";
import { driverPresentation } from "@/lib/status";
import { formatDateTime, plural, shortId } from "@/lib/format";
import "@/features/connections/resource-table.scss";
export default function Drivers() {
  useTranslation();
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState("createdAt,desc");
  const [deleting, setDeleting] = useState<Driver | null>(null);
  const client = useQueryClient();
  const params = { page, size: 20, sort };
  const query = useQuery({
    queryKey: driversKeys.list(params),
    queryFn: ({ signal }) => driversApi.list(params, signal),
  });
  const validate = useMutation({
    mutationFn: driversApi.validate,
    onSuccess: () => {
      toast.success(t("ui.driverValidated"));
      void client.invalidateQueries({ queryKey: driversKeys.all });
    },
    onError: (error) => toast.error(errorMessage(error)),
  });
  const remove = useMutation({
    mutationFn: () => driversApi.delete(deleting!.id),
    onSuccess: () => {
      setDeleting(null);
      toast.success(t("ui.driverDeleted"));
      void client.invalidateQueries({ queryKey: driversKeys.all });
    },
    onError: (error) => {
      setDeleting(null);
      toast.error(errorMessage(error));
    },
  });
  const columns: ColumnDef<Driver>[] = [
    {
      get header() { return t("ui.driverPackage"); },
      accessorKey: "name",
      cell: ({ row }) => (
        <div className="run-cell-stack">
          <Link to={`/drivers/${row.original.id}`} className="resource-primary-link rounded-sm">
            {row.original.name}
          </Link>
          <span className="run-cell-secondary" title={row.original.id}>{shortId(row.original.id)}</span>
        </div>
      ),
    },
    {
      id: "ui.vendorVersion",
      get header() { return t("ui.vendorVersion"); },
      cell: ({ row }) => (
        <div className="run-cell-stack">
          <span className="resource-cell-text">{row.original.vendor}</span>
          <span className="run-cell-secondary">
            {row.original.version}
          </span>
        </div>
      ),
    },
    {
      get header() { return t("ui.driverClass"); },
      accessorKey: "driverClass",
      cell: ({ getValue }) => (
        <span className="resource-code font-mono">{getValue<string>()}</span>
      ),
    },
    {
      id: "ui.status",
      get header() { return t("ui.status"); },
      cell: ({ row }) => (
        <StatusBadge {...driverPresentation[row.original.status]} />
      ),
    },
    {
      id: "ui.artifacts",
      get header() { return t("ui.artifacts"); },
      cell: ({ row }) =>
        <span className="run-cell-number">{plural(row.original.artifacts.length, "JAR")}</span>,
    },
    {
      id: "ui.created",
      get header() { return t("ui.created"); },
      cell: ({ row }) => (
        <span className="run-cell-secondary run-cell-date">
          {formatDateTime(row.original.createdAt)}
        </span>
      ),
    },
    {
      id: "ui.actions",
      get header() { return t("ui.actions"); },
      cell: ({ row }) => (
        <div className="resource-actions">
          <Button
            size="sm"
            variant="ghost"
            disabled={validate.isPending}
            onClick={() => validate.mutate(row.original.id)}
          >
            <ShieldCheck />
            {t("ui.validate")}</Button>
          <Button
            size="sm"
            variant="ghost"
            aria-label={t("common.deleteName", {name: row.original.name})}
            onClick={() => setDeleting(row.original)}
          >
            <Trash2 />
          </Button>
        </div>
      ),
    },
  ];
  return (
    <>
      <Card as="section" className="panel overflow-hidden p-0" aria-busy={query.isFetching}>
        <div className="run-history-panel-header">
          <div>
            <h1 className="page-title">{t("ui.driverPackages")}</h1>
            <p>{t("ui.bringYourOwnJDBCDriverEachPackage")}{query.data ? ` · ${plural(query.data.totalElements, "record")}` : ""}</p>
          </div>
          <Button asChild><Link to="/drivers/new"><Plus />{t("ui.addDriver")}</Link></Button>
        </div>
        <div className="run-history-toolbar">
          <label>
          {t("ui.sortBy")}<Select
            aria-label={t("ui.sortDrivers")}
            value={sort}
            onValueChange={(value) => {
              setSort(value);
              setPage(0);
            }}
          >
            <option value="createdAt,desc">{t("ui.newestFirst")}</option>
            <option value="name,asc">{t("ui.nameAZ")}</option>
            <option value="vendor,asc">{t("ui.vendorAZ")}</option>
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
          <>
          <DataTable
            rows={query.data.items}
            columns={columns}
            className="run-table resource-table resource-table--drivers"
            empty={
              <EmptyState
                title={t("ui.noJDBCDriversYet")}
                description={t("ui.uploadADriverPackageToGetStarted")}
                action={
                  <Button asChild variant="outline">
                    <Link to="/drivers/new">{t("ui.uploadYourFirstDriver")}</Link>
                  </Button>
                }
              />
            }
          />
          <Pagination {...query.data} onPage={setPage} />
          </>
        )}
      </Card>
      <ConfirmDialog
        open={!!deleting}
        onOpenChange={(open) => {
          if (!open) setDeleting(null);
        }}
        title={t("ui.deleteDriverPackageQuestion")}
        description={t("drivers.deleteDescription", {name: deleting?.name ?? t("ui.thisDriver")})}
        label={t("ui.deleteDriver")}
        pending={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
    </>
  );
}
