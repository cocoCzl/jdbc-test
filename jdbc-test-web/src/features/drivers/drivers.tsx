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
import { formatDateTime } from "@/lib/format";
export default function Drivers() {
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
      toast.success("Driver validated");
      void client.invalidateQueries({ queryKey: driversKeys.all });
    },
    onError: (error) => toast.error(errorMessage(error)),
  });
  const remove = useMutation({
    mutationFn: () => driversApi.delete(deleting!.id),
    onSuccess: () => {
      setDeleting(null);
      toast.success("Driver deleted");
      void client.invalidateQueries({ queryKey: driversKeys.all });
    },
    onError: (error) => {
      setDeleting(null);
      toast.error(errorMessage(error));
    },
  });
  const columns: ColumnDef<Driver>[] = [
    {
      header: "Driver package",
      accessorKey: "name",
      cell: ({ row }) => (
        <Link to={`/drivers/${row.original.id}`} className="subtle-link">
          {row.original.name}
        </Link>
      ),
    },
    {
      header: "Vendor / version",
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
      header: "Driver class",
      accessorKey: "driverClass",
      cell: ({ getValue }) => (
        <span className="font-mono text-xs">{getValue<string>()}</span>
      ),
    },
    {
      header: "Status",
      cell: ({ row }) => (
        <StatusBadge {...driverPresentation[row.original.status]} />
      ),
    },
    {
      header: "Artifacts",
      cell: ({ row }) =>
        `${row.original.artifacts.length} JAR${row.original.artifacts.length === 1 ? "" : "s"}`,
    },
    {
      header: "Created",
      cell: ({ row }) => (
        <span className="whitespace-nowrap text-xs text-muted-foreground">
          {formatDateTime(row.original.createdAt)}
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
            disabled={validate.isPending}
            onClick={() => validate.mutate(row.original.id)}
          >
            <ShieldCheck />
            Validate
          </Button>
          <Button
            size="sm"
            variant="ghost"
            aria-label={`Delete ${row.original.name}`}
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
        title="Driver packages"
        description="Bring your own JDBC driver. Each package runs in an isolated worker."
        action={
          <Button asChild>
            <Link to="/drivers/new">
              <Plus />
              Add driver
            </Link>
          </Button>
        }
      />
      <div className="mb-3 flex justify-end">
        <label className="flex items-center gap-3 text-sm text-muted-foreground">
          Sort by
          <select
            aria-label="Sort drivers"
            className="select w-48"
            value={sort}
            onChange={(event) => {
              setSort(event.target.value);
              setPage(0);
            }}
          >
            <option value="createdAt,desc">Newest first</option>
            <option value="name,asc">Name A–Z</option>
            <option value="vendor,asc">Vendor A–Z</option>
            <option value="status,asc">Status</option>
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
                title="No JDBC drivers yet"
                description="Upload a driver package to get started."
                action={
                  <Button asChild variant="outline">
                    <Link to="/drivers/new">Upload your first driver</Link>
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
        title="Delete driver package?"
        description={`Delete ${deleting?.name ?? "this driver"} and its artifacts. Drivers referenced by connections or historical runs cannot be deleted.`}
        label="Delete driver"
        pending={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
    </>
  );
}
