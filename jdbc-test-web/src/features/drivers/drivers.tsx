import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { useState } from "react";
import {
  keepPreviousData,
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
  PageHeader,
  StatusBadge,
} from "@/components/common";
import { driverPresentation } from "@/lib/status";
import { formatDateTime, plural } from "@/lib/format";
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
    placeholderData: keepPreviousData,
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
        <Link to={`/drivers/${row.original.id}`} className="subtle-link">
          {row.original.name}
        </Link>
      ),
    },
    {
      id: "ui.vendorVersion",
      get header() { return t("ui.vendorVersion"); },
      cell: ({ row }) => (
        <div>
          {row.original.vendor}
          <span className="mt-1 block text-xs text-muted-foreground">
            {row.original.version}
          </span>
        </div>
      ),
    },
    {
      get header() { return t("ui.driverClass"); },
      accessorKey: "driverClass",
      cell: ({ getValue }) => (
        <span className="font-mono text-xs">{getValue<string>()}</span>
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
        plural(row.original.artifacts.length, "JAR"),
    },
    {
      id: "ui.created",
      get header() { return t("ui.created"); },
      cell: ({ row }) => (
        <span className="whitespace-nowrap text-xs text-muted-foreground">
          {formatDateTime(row.original.createdAt)}
        </span>
      ),
    },
    {
      id: "ui.actions",
      get header() { return t("ui.actions"); },
      cell: ({ row }) => (
        <div className="flex gap-1">
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
      <PageHeader
        title={t("ui.driverPackages")}
        description={t("ui.bringYourOwnJDBCDriverEachPackage")}
        action={
          <Button asChild>
            <Link to="/drivers/new">
              <Plus />
              {t("ui.addDriver")}</Link>
          </Button>
        }
      />
      <div className="mb-3 flex justify-end">
        <label className="flex items-center gap-3 text-sm text-muted-foreground">
          {t("ui.sortBy")}<select
            aria-label={t("ui.sortDrivers")}
            className="select w-48"
            value={sort}
            onChange={(event) => {
              setSort(event.target.value);
              setPage(0);
            }}
          >
            <option value="createdAt,desc">{t("ui.newestFirst")}</option>
            <option value="name,asc">{t("ui.nameAZ")}</option>
            <option value="vendor,asc">{t("ui.vendorAZ")}</option>
            <option value="status,asc">{t("ui.status")}</option>
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
            columns={columns}
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
        </div>
      )}
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
