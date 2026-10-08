import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { Plus } from "lucide-react";
import { JdbcUrl } from "@/components/jdbc-url";
import { useState } from "react";
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { Link } from "react-router-dom";
import type { ColumnDef } from "@tanstack/react-table";
import { toast } from "sonner";
import { connectionsApi, connectionsKeys } from "@/api/connections";
import { errorMessage } from "@/api/client";
import type { Connection, Probe } from "@/types/api";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/alert-dialog";
import { Dialog } from "@/components/ui/dialog";
import { DataTable, Pagination } from "@/components/data-table";
import {
  EmptyState,
  ErrorState,
  Field,
  FormError,
  Loading,
  PageHeader,
  ProbeResult,
  StatusBadge,
} from "@/components/common";
import { connectionPresentation } from "@/lib/status";
import { formatDateTime } from "@/lib/format";
export default function Connections() {
  useTranslation();
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState("createdAt,desc");
  const [deleting, setDeleting] = useState<Connection | null>(null);
  const [testing, setTesting] = useState<Connection | null>(null);
  const [password, setPassword] = useState("");
  const [probe, setProbe] = useState<Probe | null>(null);
  const [error, setError] = useState<string | Error | null>(null);
  const client = useQueryClient();
  const params = { page, size: 20, sort };
  const query = useQuery({
    queryKey: connectionsKeys.list(params),
    queryFn: ({ signal }) => connectionsApi.list(params, signal),
    placeholderData: keepPreviousData,
  });
  const test = useMutation({
    mutationFn: () =>
      connectionsApi.testSaved(testing!.id, password || undefined),
    onSuccess: (result) => {
      setProbe(result);
      toast.success(t("ui.connectionEstablished"));
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
    },
    onError: (failure) => setError(failure instanceof Error ? failure : new Error()),
    onSettled: () => setPassword(""),
  });
  const remove = useMutation({
    mutationFn: () => connectionsApi.delete(deleting!.id),
    onSuccess: () => {
      setDeleting(null);
      toast.success(t("ui.connectionDeleted"));
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
    },
    onError: (failure) => {
      setDeleting(null);
      toast.error(errorMessage(failure));
    },
  });
  const columns: ColumnDef<Connection>[] = [
    {
      id: "ui.connection",
      get header() { return t("ui.connection"); },
      cell: ({ row }) => (
        <Link className="subtle-link" to={`/connections/${row.original.id}`}>
          {row.original.name}
        </Link>
      ),
    },
    {
      id: "ui.databaseAdapter",
      get header() { return t("ui.databaseAdapter"); },
      cell: ({ row }) => (
        <div>
          {t("ui.databaseLabel")}{row.original.databaseType}
          <span className="mt-1 block text-xs text-muted-foreground">
            {t("ui.adapterLabel")}{row.original.adapterId}
          </span>
        </div>
      ),
    },
    {
      id: "ui.jdbcURL",
      get header() { return t("ui.jdbcURL"); },
      cell: ({ row }) => (
        <div className="min-w-48 max-w-72">
          <JdbcUrl value={row.original.jdbcUrl} />
          <p className="mt-1 text-xs text-muted-foreground">{t("ui.user")} {row.original.username}</p>
        </div>
      ),
    },
    {
      id: "ui.defaultDriver",
      get header() { return t("ui.defaultDriver"); },
      cell: ({ row }) => row.original.defaultDriver?.name ?? "—",
    },
    {
      id: "ui.lastTest",
      get header() { return t("ui.lastTest"); },
      cell: ({ row }) => (
        <div>
          {row.original.lastConnectionTestStatus ? (
            <StatusBadge
              {...connectionPresentation[row.original.lastConnectionTestStatus]}
            />
          ) : (
            <span className="text-muted-foreground">{t("ui.notTested")}</span>
          )}
          <p className="mt-1 whitespace-nowrap text-xs text-muted-foreground">
            {formatDateTime(row.original.lastConnectionTestAt)}
          </p>
        </div>
      ),
    },
    {
      id: "ui.updated",
      get header() { return t("ui.updated"); },
      cell: ({ row }) => (
        <span className="whitespace-nowrap text-xs">
          {formatDateTime(row.original.updatedAt)}
        </span>
      ),
    },
    {
      id: "ui.actions",
      get header() { return t("ui.actions"); },
      cell: ({ row }) => (
        <div className="flex flex-wrap gap-1">
          <Button
            size="sm"
            variant="ghost"
            onClick={() => {
              setTesting(row.original);
              setProbe(null);
              setError(null);
              setPassword("");
            }}
          >
            {t("ui.test")}</Button>
          <Button size="sm" variant="ghost" asChild>
            <Link to={`/runs/new?connectionId=${row.original.id}`}>
              {t("ui.startRun")}</Link>
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => setDeleting(row.original)}
          >
            {t("ui.delete")}</Button>
        </div>
      ),
    },
  ];
  return (
    <>
      <PageHeader
        title={t("ui.connections")}
        action={<Button asChild><Link to="/connections/new"><Plus />{t("ui.addConnection")}</Link></Button>}
        description={t("ui.yourTargetDatabasesSeparateFromThePlatform")}
      />
      <div className="mb-3 flex justify-end">
        <label className="flex items-center gap-3 text-sm text-muted-foreground">
          {t("ui.sortBy")}<select
            aria-label={t("ui.sortConnections")}
            className="select w-48"
            value={sort}
            onChange={(event) => {
              setSort(event.target.value);
              setPage(0);
            }}
          >
            <option value="createdAt,desc">{t("ui.newestFirst")}</option>
            <option value="name,asc">{t("ui.nameAZ")}</option>
            <option value="databaseType,asc">{t("ui.databaseType")}</option>
            <option value="lastConnectionTestAt,desc">{t("ui.lastTest")}</option>
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
                title={t("ui.noDatabaseConnectionsYet")}
                description={t("ui.addATargetConnectionToStartTesting")}
                action={
                  <Button asChild variant="outline">
                    <Link to="/connections/new">{t("ui.addConnection")}</Link>
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
        title={t("ui.deleteConnectionQuestion")}
        description={t("ui.connectionsReferencedByHistoricalTestRunsCannot")}
        label={t("ui.deleteConnection")}
        pending={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
      <Dialog
        open={!!testing}
        onOpenChange={(open) => {
          if (!open) {
            setTesting(null);
            setPassword("");
          }
        }}
        title={t("common.testName", {name: testing?.name ?? t("ui.connection")})}
        description={t("ui.usesTheSavedConnectionAndItsDefault")}
      >
        <form
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            setError(null);
            setProbe(null);
            test.mutate();
          }}
        >
          <Field
            id="probe-password"
            label={t("ui.passwordForThisTest")}
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required={!testing?.hasSavedPassword}
            hint={
              testing?.hasSavedPassword
                ? t("ui.leaveBlankToUseTheSavedPassword")
                : t("ui.usedOnlyForThisTestNotSaved")
            }
          />
          <FormError message={error} />
          {probe ? <ProbeResult result={probe} /> : null}
          <Button disabled={test.isPending}>
            {test.isPending ? t("ui.testingConnection") : t("ui.testConnection")}
          </Button>
        </form>
      </Dialog>
    </>
  );
}
