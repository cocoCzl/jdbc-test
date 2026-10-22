import { Card } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { Cable, Play, Plus, Trash2 } from "lucide-react";
import { JdbcUrl } from "@/components/jdbc-url";
import { useState } from "react";
import {
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
import { ResourceAction } from "@/components/resource-action";
import { Dialog } from "@/components/ui/dialog";
import { DataTable, Pagination } from "@/components/data-table";
import {
  EmptyState,
  ErrorState,
  Field,
  FormError,
  Loading,
  ProbeResult,
  StatusBadge,
} from "@/components/common";
import { connectionPresentation } from "@/lib/status";
import { formatDateTime, plural } from "@/lib/format";
import "./resource-table.scss";
export default function Connections() {
  useTranslation();
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState("createdAt,desc");
  const [deleting, setDeleting] = useState<Connection | null>(null);
  const [testing, setTesting] = useState<Connection | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [password, setPassword] = useState("");
  const [emptyPassword, setEmptyPassword] = useState(false);
  const [probe, setProbe] = useState<Probe | null>(null);
  const [error, setError] = useState<string | Error | null>(null);
  const client = useQueryClient();
  const params = { page, size: 20, sort };
  const query = useQuery({
    queryKey: connectionsKeys.list(params),
    queryFn: ({ signal }) => connectionsApi.list(params, signal),
  });
  const test = useMutation({
    mutationFn: () =>
      connectionsApi.testSaved(testing!.id, emptyPassword ? "" : password || undefined),
    onSuccess: (result) => {
      setProbe(result);
      toast.success(t("ui.connectionEstablished"));
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
    },
    onError: (failure) => setError(failure instanceof Error ? failure : new Error()),
    onSettled: () => { setPassword(""); setTestingId(null); },
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
          <Link className="resource-primary-link rounded-sm" to={`/connections/${row.original.id}`}>
            {row.original.name}
          </Link>
      ),
    },
    {
      id: "ui.databaseAdapter",
      get header() { return t("ui.databaseAdapter"); },
      cell: ({ row }) => (
        <div className="run-cell-stack">
          <span className="resource-cell-text">{t("ui.databaseLabel")} {row.original.databaseType}</span>
          <span className="run-cell-secondary">
            {t("ui.adapterLabel")} {row.original.adapterId}
          </span>
        </div>
      ),
    },
    {
      id: "ui.jdbcURL",
      get header() { return t("ui.jdbcURL"); },
      cell: ({ row }) => (
        <div className="resource-url-line">
          <JdbcUrl value={row.original.jdbcUrl} />
          <p className="run-cell-secondary resource-user">{t("ui.user")} {row.original.username}</p>
        </div>
      ),
    },
    {
      id: "ui.defaultDriver",
      get header() { return t("ui.defaultDriver"); },
      cell: ({ row }) => <span className="resource-cell-text">{row.original.defaultDriver?.name ?? "—"}</span>,
    },
    {
      id: "ui.lastTest",
      get header() { return t("ui.lastTest"); },
      cell: ({ row }) => (
        <div className="run-cell-stack">
          {row.original.lastConnectionTestStatus ? (
            <StatusBadge
              {...connectionPresentation[row.original.lastConnectionTestStatus]}
            />
          ) : (
            <span className="run-cell-secondary">{t("ui.notTested")}</span>
          )}
          <span className="run-cell-secondary run-cell-date">
            {formatDateTime(row.original.lastConnectionTestAt)}
          </span>
        </div>
      ),
    },
    {
      id: "ui.updated",
      get header() { return t("ui.updated"); },
      cell: ({ row }) => (
        <span className="run-cell-secondary run-cell-date">
          {formatDateTime(row.original.updatedAt)}
        </span>
      ),
    },
    {
      id: "ui.actions",
      get header() { return t("ui.actions"); },
      cell: ({ row }) => (
        <div className="resource-actions">
          <ResourceAction icon={Cable} label={t("resource.testConnection", { name: row.original.name })}
            pending={test.isPending && testingId === row.original.id}
            disabled={test.isPending}
            reason={t("ui.testingConnection")}
            onClick={() => {
              setTesting(row.original);
              setProbe(null);
              setError(null);
              setPassword(""); setEmptyPassword(false);
            }} />
          <ResourceAction icon={Play} label={t("resource.startRun", { name: row.original.name })}
            href={`/runs/new?connectionId=${row.original.id}`} />
          <ResourceAction icon={Trash2} danger label={t("common.deleteName", { name: row.original.name })}
            disabled={remove.isPending || (test.isPending && testingId === row.original.id)}
            reason={t(remove.isPending ? "ui.pleaseWait" : "ui.testingConnection")}
            onClick={() => setDeleting(row.original)} />
        </div>
      ),
    },
  ];
  return (
    <>
      <Card as="section" className="panel overflow-hidden p-0" aria-busy={query.isFetching}>
        <div className="run-history-panel-header">
          <div>
            <h1 className="page-title">{t("ui.connections")}</h1>
            <p>{t("ui.yourTargetDatabasesSeparateFromThePlatform")}{query.data ? ` · ${plural(query.data.totalElements, "record")}` : ""}</p>
          </div>
          <Button asChild><Link to="/connections/new"><Plus />{t("ui.addConnection")}</Link></Button>
        </div>
        <div className="run-history-toolbar">
          <label>
          {t("ui.sortBy")}<Select
            aria-label={t("ui.sortConnections")}
            value={sort}
            onValueChange={(value) => {
              setSort(value);
              setPage(0);
            }}
          >
            <option value="createdAt,desc">{t("ui.newestFirst")}</option>
            <option value="name,asc">{t("ui.nameAZ")}</option>
            <option value="databaseType,asc">{t("ui.databaseType")}</option>
            <option value="lastConnectionTestAt,desc">{t("ui.lastTest")}</option>
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
            className="run-table resource-table resource-table--connections"
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
          </>
        )}
      </Card>
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
            setPassword(""); setEmptyPassword(false);
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
            if (!test.isPending) { setTestingId(testing!.id); test.mutate(); }
          }}
        >
          <Field
            id="probe-password"
            label={t("ui.passwordForThisTest")}
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required={!testing?.hasSavedPassword && !emptyPassword}
            disabled={emptyPassword}
            hint={
              testing?.hasSavedPassword
                ? t("ui.leaveBlankToUseTheSavedPassword")
                : t("ui.usedOnlyForThisTestNotSaved")
            }
          />
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={emptyPassword}
              onChange={(event) => { setEmptyPassword(event.target.checked); setPassword(""); }} />
            {t("credentials.emptyPassword")}
          </label>
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
