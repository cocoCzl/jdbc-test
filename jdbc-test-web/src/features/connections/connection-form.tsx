import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { toast } from "sonner";
import { adaptersApi, adapterKeys } from "@/api/adapters";
import { connectionsApi, connectionsKeys } from "@/api/connections";
import { errorMessage } from "@/api/client";
import type {
  Connection,
  ConnectionInput,
  DriverSummary,
  Probe,
} from "@/types/api";
import { DriverSelect } from "@/features/drivers/driver-select";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/alert-dialog";
import {
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
function Editor({ connection }: { connection?: Connection }) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [form, setForm] = useState({
    name: connection?.name ?? "",
    executionDatabase: connection?.executionDatabase ?? "",
    executionSchema: connection?.executionSchema ?? "",
    databaseType: connection?.databaseType ?? "",
    adapterId: connection?.adapterId ?? "",
    jdbcUrl: connection?.jdbcUrl ?? "",
    username: connection?.username ?? "",
    description: connection?.description ?? "",
  });
  const [driver, setDriver] = useState<DriverSummary | null>(
    connection?.defaultDriver ?? null,
  );
  const [password, setPassword] = useState("");
  const [savePassword, setSavePassword] = useState(false);
  const [clearSavedPassword, setClearSavedPassword] = useState(false);
  const [probe, setProbe] = useState<Probe | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const adapters = useQuery({
    queryKey: adapterKeys.all,
    queryFn: ({ signal }) => adaptersApi.list(signal),
    staleTime: 300_000,
  });
  const change = (key: keyof typeof form, value: string) =>
    setForm((previous) => ({ ...previous, [key]: value }));
  const save = useMutation({
    mutationFn: () => {
      const input: ConnectionInput = {
        ...form,
        defaultDriverId: driver!.id,
        savePassword,
        ...(connection ? { clearSavedPassword } : {}),
        ...(savePassword && password ? { password } : {}),
      };
      return connection
        ? connectionsApi.update(connection.id, input)
        : connectionsApi.create(input);
    },
    onSuccess: (result) => {
      toast.success(connection ? "Connection updated" : "Connection created");
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
      navigate(`/connections/${result.id}`);
    },
    onError: (failure) => setError(errorMessage(failure)),
    onSettled: () => setPassword(""),
  });
  const test = useMutation({
    mutationFn: () =>
      connection
        ? connectionsApi.testSaved(connection.id, password || undefined)
        : connectionsApi.testDraft({
            ...form,
            driverId: driver!.id,
            password: password || undefined,
          }),
    onSuccess: (result) => {
      setProbe(result);
      toast.success("Connection established");
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
    },
    onError: (failure) => setError(errorMessage(failure)),
    onSettled: () => setPassword(""),
  });
  const remove = useMutation({
    mutationFn: () => connectionsApi.delete(connection!.id),
    onSuccess: () => {
      toast.success("Connection deleted");
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
      navigate("/connections");
    },
    onError: (failure) => {
      setDeleting(false);
      setError(errorMessage(failure));
    },
  });
  const busy = save.isPending || test.isPending;
  if (adapters.isPending) return <Loading label="Loading adapters…" />;
  if (adapters.isError)
    return (
      <ErrorState
        error={adapters.error}
        retry={() => void adapters.refetch()}
      />
    );
  return (
    <>
      <PageHeader
        title={connection ? connection.name : "Add a connection"}
        description="Connection metadata stays on the platform. JDBC work happens in a separate Worker."
        action={
          connection ? (
            <Button asChild>
              <Link to={`/runs/new?connectionId=${connection.id}`}>
                Start run
              </Link>
            </Button>
          ) : undefined
        }
      />
      <form
        className="grid max-w-6xl gap-6 xl:grid-cols-[1fr_320px]"
        onSubmit={(event) => {
          event.preventDefault();
          if (!driver) {
            setError("Select a validated driver.");
            return;
          }
          if (savePassword && !password) {
            setError("Enter a password to save, or uncheck Save password.");
            return;
          }
          setError(null);
          save.mutate();
        }}
      >
        <div className="space-y-6">
          <section className="panel">
            <h2 className="mb-5 text-lg font-semibold">Connection identity</h2>
            <div className="grid gap-5 sm:grid-cols-2">
              <Field
                id="connection-name"
                label="Name"
                required
                maxLength={200}
                value={form.name}
                onChange={(event) => change("name", event.target.value)}
                placeholder="PostgreSQL test workspace"
              />
              <Field
                id="database-type"
                label="Database type"
                required
                maxLength={80}
                value={form.databaseType}
                onChange={(event) => change("databaseType", event.target.value)}
                hint="An open label, e.g. PostgreSQL, AcmeDB or VendorDB."
              />
              <Field id="adapter" label="Adapter">
                <select
                  id="adapter"
                  required
                  className="select"
                  value={form.adapterId}
                  onChange={(event) => {
                    change("adapterId", event.target.value);
                    if (!form.databaseType)
                      change(
                        "databaseType",
                        adapters.data.find(
                          (item) => item.id === event.target.value,
                        )?.name ?? "",
                      );
                  }}
                >
                  <option value="">Select an adapter</option>
                  {adapters.data.map((adapter) => (
                    <option key={adapter.id} value={adapter.id}>
                      {adapter.name} · {adapter.status}
                    </option>
                  ))}
                </select>
              </Field>
              <DriverSelect
                value={driver?.id ?? ""}
                selected={driver}
                onChange={setDriver}
              />
              <div className="sm:col-span-2">
                <Field
                  id="jdbc-url"
                  label="JDBC URL"
                  required
                  maxLength={2000}
                  value={form.jdbcUrl}
                  onChange={(event) => change("jdbcUrl", event.target.value)}
                  placeholder="jdbc:postgresql://localhost:5432/test"
                  hint="Do not include passwords or tokens in the URL. Use the separate password field."
                />
              </div>
              <Field
                id="username"
                label="Username"
                maxLength={200}
                value={form.username}
                onChange={(event) => change("username", event.target.value)}
              />
              <Field id="execution-database" label="Authorized test database" maxLength={63}
                value={form.executionDatabase} onChange={(event) => change("executionDatabase", event.target.value)}
                hint="Required for MySQL and PostgreSQL test runs. A connection probe does not require a write scope." />
              <Field id="execution-schema" label="Authorized test schema" maxLength={63}
                value={form.executionSchema} onChange={(event) => change("executionSchema", event.target.value)}
                hint="Required for schema-based adapters. Use only an explicitly authorized test scope; the run freezes this selection." />
              <Field
                id="password"
                label={
                  connection
                    ? "Password (leave blank to keep saved credential)"
                    : "Password"
                }
                type="password"
                autoComplete="new-password"
                maxLength={2000}
                value={password}
                disabled={clearSavedPassword}
                onChange={(event) => setPassword(event.target.value)}
                hint="Never returned by the API. Used in memory only."
              />
            </div>
            <div className="mt-5 space-y-3">
              {connection?.hasSavedPassword ? (
                <p className="text-sm text-success">Saved password available</p>
              ) : null}
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={savePassword}
                  disabled={clearSavedPassword}
                  onChange={(event) => setSavePassword(event.target.checked)}
                  className="size-4 accent-primary"
                />
                Save password
              </label>
              {connection?.hasSavedPassword ? (
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={clearSavedPassword}
                    onChange={(event) => {
                      setClearSavedPassword(event.target.checked);
                      setPassword("");
                      setSavePassword(false);
                    }}
                    className="size-4 accent-primary"
                  />
                  Clear saved password
                </label>
              ) : null}
            </div>
            <div className="mt-5">
              <Field id="description" label="Description (optional)">
                <textarea
                  id="description"
                  className="min-h-24 w-full rounded-xl border border-input p-3 text-sm"
                  maxLength={1000}
                  value={form.description}
                  onChange={(event) =>
                    change("description", event.target.value)
                  }
                />
              </Field>
            </div>
          </section>
          {probe ? <ProbeResult result={probe} /> : null}
          {connection ? (
            <section className="panel">
              <h2 className="font-semibold">Saved connection</h2>
              <p className="my-3 text-sm text-muted-foreground">
                Saved Test uses persisted settings. Save edited metadata first
                to test those changes.
              </p>
              {connection.lastConnectionTestStatus ? (
                <StatusBadge
                  {...connectionPresentation[
                    connection.lastConnectionTestStatus
                  ]}
                />
              ) : (
                <p className="text-sm text-muted-foreground">Not tested yet</p>
              )}
              <p className="mt-3 text-xs text-muted-foreground">
                Last test {formatDateTime(connection.lastConnectionTestAt)} ·
                Updated {formatDateTime(connection.updatedAt)}
              </p>
              <Button
                type="button"
                variant="destructive"
                className="mt-5"
                onClick={() => setDeleting(true)}
              >
                Delete connection
              </Button>
            </section>
          ) : null}
        </div>
        <aside className="panel h-fit space-y-5">
          <p className="eyebrow">Connect with confidence</p>
          <p className="text-sm leading-relaxed text-muted-foreground">
            Saving and testing are independent. You can save a connection even
            when the target is offline.
          </p>
          <FormError message={error} />
          <Button className="w-full" disabled={busy}>
            {save.isPending ? "Saving…" : "Save connection"}
          </Button>
          <Button
            type="button"
            className="w-full"
            variant="outline"
            disabled={
              busy ||
              !driver ||
              (!connection &&
                (!form.jdbcUrl || !form.adapterId || !form.databaseType))
            }
            onClick={() => {
              setError(null);
              setProbe(null);
              test.mutate();
            }}
          >
            {test.isPending
              ? "Testing connection…"
              : connection
                ? "Test saved connection"
                : "Test connection"}
          </Button>
          <p className="text-xs leading-relaxed text-muted-foreground">
            After a request completes, the password input is cleared. Re-enter
            it if a later action needs an unsaved credential.
          </p>
        </aside>
      </form>
      <ConfirmDialog
        open={deleting}
        onOpenChange={setDeleting}
        title="Delete connection?"
        description="Connections with historical test run references cannot be deleted. History is never cascade-deleted."
        label="Delete connection"
        pending={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
    </>
  );
}
export default function ConnectionForm() {
  const { id } = useParams();
  const query = useQuery({
    queryKey: connectionsKeys.detail(id ?? ""),
    queryFn: ({ signal }) => connectionsApi.get(id!, signal),
    enabled: !!id,
  });
  if (!id) return <Editor />;
  if (query.isPending) return <Loading />;
  if (query.isError)
    return (
      <ErrorState error={query.error} retry={() => void query.refetch()} />
    );
  return (
    <Editor key={`${id}-${query.data.updatedAt}`} connection={query.data} />
  );
}
