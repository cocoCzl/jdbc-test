import { Card } from "@/components/ui/card";
import { Textarea } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { adapterStatus } from "@/lib/presentation";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { toast } from "sonner";
import { adaptersApi, adapterKeys } from "@/api/adapters";
import { connectionsApi, connectionsKeys } from "@/api/connections";
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
  PendingPage,
  PageHeader,
  ProbeResult,
  StatusBadge,
} from "@/components/common";
import { connectionPresentation } from "@/lib/status";
import { formatDateTime } from "@/lib/format";
import { AdapterDetails } from "./adapter-details";
function Editor({ connection }: { connection?: Connection }) {
  useTranslation();
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
  const [emptyPassword, setEmptyPassword] = useState(false);
  const [savePassword, setSavePassword] = useState(false);
  const [clearSavedPassword, setClearSavedPassword] = useState(false);
  const [probe, setProbe] = useState<Probe | null>(null);
  const [error, setError] = useState<string | Error | null>(null);
  const [deleting, setDeleting] = useState(false);
  const adapters = useQuery({
    queryKey: adapterKeys.all,
    queryFn: ({ signal }) => adaptersApi.list(signal),
    staleTime: 300_000,
  });
  const selectedAdapter = adapters.data?.find((item) => item.id === form.adapterId);
  const change = (key: keyof typeof form, value: string) =>
    setForm((previous) => ({ ...previous, [key]: value }));
  const save = useMutation({
    mutationFn: () => {
      const input: ConnectionInput = {
        ...form,
        defaultDriverId: driver!.id,
        savePassword,
        ...(connection ? { clearSavedPassword } : {}),
        ...(savePassword && (password || emptyPassword) ? { password: emptyPassword ? "" : password } : {}),
      };
      return connection
        ? connectionsApi.update(connection.id, input)
        : connectionsApi.create(input);
    },
    onSuccess: (result) => {
      toast.success(connection ? t("ui.connectionUpdated") : t("ui.connectionCreated"));
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
      navigate(`/connections/${result.id}`);
    },
    onError: (failure) => setError(failure instanceof Error ? failure : new Error()),
    onSettled: () => { setPassword(""); setEmptyPassword(false); },
  });
  const test = useMutation({
    mutationFn: () =>
      connection
        ? connectionsApi.testSaved(connection.id, emptyPassword ? "" : password || undefined)
        : connectionsApi.testDraft({
            ...form,
            driverId: driver!.id,
            password: emptyPassword ? "" : password || undefined,
          }),
    onSuccess: (result) => {
      setProbe(result);
      toast.success(t("ui.connectionEstablished"));
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
    },
    onError: (failure) => setError(failure instanceof Error ? failure : new Error()),
    onSettled: () => { setPassword(""); setEmptyPassword(false); },
  });
  const remove = useMutation({
    mutationFn: () => connectionsApi.delete(connection!.id),
    onSuccess: () => {
      toast.success(t("ui.connectionDeleted"));
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
      navigate("/connections");
    },
    onError: (failure) => {
      setDeleting(false);
      setError(failure instanceof Error ? failure : new Error());
    },
  });
  const busy = save.isPending || test.isPending;
  if (adapters.isPending) return <PendingPage title={connection?.name ?? t("ui.addAConnection")} />;
  if (!adapters.data)
    return (
      <ErrorState
        error={adapters.error}
        retry={() => void adapters.refetch()}
      />
    );
  return (
    <>
      <PageHeader
        title={connection ? connection.name : t("ui.addAConnection")}
        description={t("ui.connectionMetadataStaysOnThePlatformJDBC")}
        action={
          connection ? (
            <Button asChild>
              <Link to={`/runs/new?connectionId=${connection.id}`}>
                {t("ui.startRun")}</Link>
            </Button>
          ) : undefined
        }
      />
      <form
        className="grid grid-cols-1 max-w-6xl gap-6 xl:grid-cols-[minmax(0,1fr)_320px]"
        onSubmit={(event) => {
          event.preventDefault();
          if (!driver) {
            setError("ui.selectAValidatedDriverSentence");
            return;
          }
          if (savePassword && !password && !emptyPassword) {
            setError("ui.enterAPasswordToSaveOrUncheck");
            return;
          }
          setError(null);
          save.mutate();
        }}
      >
        <div className="space-y-6">
          <Card as="section" className="panel">
            <h2 className="mb-5 text-lg font-semibold">{t("ui.connectionIdentity")}</h2>
            <div className="grid gap-5 sm:grid-cols-2">
              <Field
                id="connection-name"
                label={t("ui.name")}
                required
                maxLength={200}
                value={form.name}
                onChange={(event) => change("name", event.target.value)}
                placeholder={t("ui.postgresqlTestWorkspace")}
              />
              <Field
                id="database-type"
                label={t("ui.databaseType")}
                required
                maxLength={80}
                value={form.databaseType}
                onChange={(event) => change("databaseType", event.target.value)}
                hint={t("adapter.displayLabelHint")}
              />
              <Field id="adapter" label={t("ui.adapter")} hint={t("adapter.selectionHint")}>
                <Select
                  id="adapter"
                  required
                  className=""
                  value={form.adapterId}
                  onValueChange={(value) => {
                    change("adapterId", value);
                    if (!form.databaseType)
                      change(
                        "databaseType",
                        adapters.data.find(
                          (item) => item.id === value,
                        )?.name ?? "",
                      );
                  }}
                >
                  <option value="">{t("ui.selectAnAdapter")}</option>
                  {adapters.data.map((adapter) => (
                    <option key={adapter.id} value={adapter.id}>
                      {adapter.name} · {adapterStatus(adapter.status)}
                    </option>
                  ))}
                </Select>
              </Field>
              <div className="sm:col-span-2">
                <AdapterDetails adapter={selectedAdapter} />
              </div>
              <DriverSelect
                value={driver?.id ?? ""}
                selected={driver}
                onChange={setDriver}
              />
              <div className="sm:col-span-2">
                <Field
                  id="jdbc-url"
                  label={t("ui.jdbcURL")}
                  required
                  maxLength={2000}
                  value={form.jdbcUrl}
                  onChange={(event) => change("jdbcUrl", event.target.value)}
                  placeholder="jdbc:postgresql://localhost:5432/test"
                  hint={t("ui.doNotIncludePasswordsOrTokensIn")}
                />
              </div>
              <Field
                id="username"
                label={t("ui.username")}
                maxLength={200}
                value={form.username}
                onChange={(event) => change("username", event.target.value)}
              />
              <Field id="execution-database" label={t("ui.authorizedTestDatabase")} maxLength={63}
                value={form.executionDatabase} onChange={(event) => change("executionDatabase", event.target.value)}
                hint={t(selectedAdapter?.requiresDatabase === false ? "adapter.databaseOptional" : "adapter.databaseRequired")} />
              <Field id="execution-schema" label={t("ui.authorizedTestSchema")} maxLength={63}
                value={form.executionSchema} onChange={(event) => change("executionSchema", event.target.value)}
                hint={t(selectedAdapter?.namespaceSelection === "catalog" ? "adapter.schemaOptional" : "ui.requiredForSchemaBasedAdaptersUseOnly")} />
              <Field
                id="password"
                label={
                  connection
                    ? t("ui.passwordLeaveBlankToKeepSavedCredential")
                    : t("ui.password")
                }
                type="password"
                autoComplete="new-password"
                maxLength={2000}
                value={password}
                disabled={clearSavedPassword || emptyPassword}
                onChange={(event) => setPassword(event.target.value)}
                hint={t("ui.neverReturnedByTheAPIUsedIn")}
              />
            </div>
            <div className="mt-5 space-y-3">
              {connection?.hasSavedPassword ? (
                <p className="text-sm text-success">{t("ui.savedPasswordAvailable")}</p>
              ) : null}
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={emptyPassword} disabled={clearSavedPassword}
                  onChange={(event) => { setEmptyPassword(event.target.checked); setPassword(""); }} />
                {t("credentials.emptyPassword")}
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={savePassword}
                  disabled={clearSavedPassword}
                  onChange={(event) => setSavePassword(event.target.checked)}
                  className="size-4 accent-primary"
                />
                {t("ui.savePassword")}</label>
              {connection?.hasSavedPassword ? (
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={clearSavedPassword}
                    onChange={(event) => {
                      setClearSavedPassword(event.target.checked);
                      setPassword("");
                      setSavePassword(false);
                      setEmptyPassword(false);
                    }}
                    className="size-4 accent-primary"
                  />
                  {t("ui.clearSavedPassword")}</label>
              ) : null}
            </div>
            <div className="mt-5">
              <Field id="description" label={t("ui.descriptionOptional")}>
                <Textarea
                  id="description"
                  className="min-h-24"
                  maxLength={1000}
                  value={form.description}
                  onChange={(event) =>
                    change("description", event.target.value)
                  }
                />
              </Field>
            </div>
          </Card>
          {probe ? <ProbeResult result={probe} /> : null}
          {connection ? (
            <Card as="section" className="panel">
              <h2 className="font-semibold">{t("ui.savedConnection")}</h2>
              <p className="my-3 text-sm text-muted-foreground">
                {t("ui.savedTestUsesPersistedSettingsSaveEdited")}</p>
              {connection.lastConnectionTestStatus ? (
                <StatusBadge
                  {...connectionPresentation[
                    connection.lastConnectionTestStatus
                  ]}
                />
              ) : (
                <p className="text-sm text-muted-foreground">{t("ui.notTestedYet")}</p>
              )}
              <p className="mt-3 text-xs text-muted-foreground">
                {t("ui.lastTest")}{formatDateTime(connection.lastConnectionTestAt)} {t("ui.updatedInline")}{formatDateTime(connection.updatedAt)}
              </p>
              <Button
                type="button"
                variant="destructive"
                className="mt-5"
                onClick={() => setDeleting(true)}
              >
                {t("ui.deleteConnection")}</Button>
            </Card>
          ) : null}
        </div>
        <Card as="aside" className="panel h-fit space-y-5">
          <p className="eyebrow">{t("ui.connectWithConfidence")}</p>
          <p className="text-sm leading-relaxed text-muted-foreground">
            {t("ui.savingAndTestingAreIndependentYouCan")}</p>
          <FormError message={error} />
          <Button className="w-full" disabled={busy}>
            {save.isPending ? t("ui.saving") : t("ui.saveConnection")}
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
              ? t("ui.testingConnection")
              : connection
                ? t("ui.testSavedConnection")
                : t("ui.testConnection")}
          </Button>
          <p className="text-xs leading-relaxed text-muted-foreground">
            {t("ui.afterARequestCompletesThePasswordInput")}</p>
        </Card>
      </form>
      <ConfirmDialog
        open={deleting}
        onOpenChange={setDeleting}
        title={t("ui.deleteConnectionQuestion")}
        description={t("ui.connectionsWithHistoricalTestRunReferencesCannot")}
        label={t("ui.deleteConnection")}
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
  if (query.isPending) return <PendingPage title={t("ui.connection")} />;
  if (!query.data)
    return (
      <ErrorState error={query.error} retry={() => void query.refetch()} />
    );
  return (
    <Editor key={`${id}-${query.data.updatedAt}`} connection={query.data} />
  );
}
