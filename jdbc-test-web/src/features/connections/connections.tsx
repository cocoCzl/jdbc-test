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
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState("createdAt,desc");
  const [deleting, setDeleting] = useState<Connection | null>(null);
  const [testing, setTesting] = useState<Connection | null>(null);
  const [password, setPassword] = useState("");
  const [probe, setProbe] = useState<Probe | null>(null);
  const [error, setError] = useState<string | null>(null);
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
      toast.success("Connection established");
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
    },
    onError: (failure) => setError(errorMessage(failure)),
    onSettled: () => setPassword(""),
  });
  const remove = useMutation({
    mutationFn: () => connectionsApi.delete(deleting!.id),
    onSuccess: () => {
      setDeleting(null);
      toast.success("Connection deleted");
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
    },
    onError: (failure) => {
      setDeleting(null);
      toast.error(errorMessage(failure));
    },
  });
  const columns: ColumnDef<Connection>[] = [
    {
      header: "Connection",
      cell: ({ row }) => (
        <Link className="subtle-link" to={`/connections/${row.original.id}`}>
          {row.original.name}
        </Link>
      ),
    },
    {
      header: "Database / adapter",
      cell: ({ row }) => (
        <div>
          {row.original.databaseType}
          <span className="mt-1 block text-xs text-muted-foreground">
            {row.original.adapterId}
          </span>
        </div>
      ),
    },
    {
      header: "JDBC URL",
      cell: ({ row }) => (
        <div className="max-w-64">
          <p
            className="truncate font-mono text-xs"
            title={row.original.jdbcUrl}
          >
            {row.original.jdbcUrl}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {row.original.username}
          </p>
        </div>
      ),
    },
    {
      header: "Default driver",
      cell: ({ row }) => row.original.defaultDriver?.name ?? "—",
    },
    {
      header: "Last test",
      cell: ({ row }) => (
        <div>
          {row.original.lastConnectionTestStatus ? (
            <StatusBadge
              {...connectionPresentation[row.original.lastConnectionTestStatus]}
            />
          ) : (
            <span className="text-muted-foreground">Not tested</span>
          )}
          <p className="mt-1 whitespace-nowrap text-xs text-muted-foreground">
            {formatDateTime(row.original.lastConnectionTestAt)}
          </p>
        </div>
      ),
    },
    {
      header: "Updated",
      cell: ({ row }) => (
        <span className="whitespace-nowrap text-xs">
          {formatDateTime(row.original.updatedAt)}
        </span>
      ),
    },
    {
      header: "Actions",
      cell: ({ row }) => (
        <div className="flex gap-1">
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
            Test
          </Button>
          <Button size="sm" variant="ghost" asChild>
            <Link to={`/runs/new?connectionId=${row.original.id}`}>
              Start run
            </Link>
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => setDeleting(row.original)}
          >
            Delete
          </Button>
        </div>
      ),
    },
  ];
  return (
    <>
      <PageHeader
        title="Connections"
        description="Your target databases, separate from the platform database."
      />
      <div className="mb-5 flex justify-end">
        <label className="flex items-center gap-3 text-sm text-muted-foreground">
          Sort by
          <select
            aria-label="Sort connections"
            className="select w-48"
            value={sort}
            onChange={(event) => {
              setSort(event.target.value);
              setPage(0);
            }}
          >
            <option value="createdAt,desc">Newest first</option>
            <option value="name,asc">Name A–Z</option>
            <option value="databaseType,asc">Database type</option>
            <option value="lastConnectionTestAt,desc">Last test</option>
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
                title="No database connections yet"
                description="Add a target connection to start testing your JDBC drivers."
                action={
                  <Button asChild>
                    <Link to="/connections/new">Add connection</Link>
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
        title="Delete connection?"
        description="Connections referenced by historical test runs cannot be deleted. No history will be cascade-deleted."
        label="Delete connection"
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
        title={`Test ${testing?.name ?? "connection"}`}
        description="Uses the saved connection and its default driver in an isolated Worker."
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
            label="Password for this test"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required={!testing?.hasSavedPassword}
            hint={
              testing?.hasSavedPassword
                ? "Leave blank to use the saved password."
                : "Used only for this test; not saved."
            }
          />
          <FormError message={error} />
          {probe ? <ProbeResult result={probe} /> : null}
          <Button disabled={test.isPending}>
            {test.isPending ? "Testing connection…" : "Test connection"}
          </Button>
        </form>
      </Dialog>
    </>
  );
}
