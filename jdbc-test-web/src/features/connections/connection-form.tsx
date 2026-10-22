import { Card } from "@/components/ui/card";
import { Textarea } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
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
  PendingPage,
  PageHeader,
  ProbeResult,
  StatusBadge,
} from "@/components/common";
import { connectionPresentation } from "@/lib/status";
import { formatDateTime } from "@/lib/format";
import { DialectField } from "@/features/drivers/dialect-field";
import { ProbeError } from "./probe-error";
import { ResourceTechnicalInfo } from "@/components/resource-technical-info";
function Editor({ connection }: { connection?: Connection }) {
  useTranslation();
  const navigate = useNavigate();
  const [search] = useSearchParams();
  const client = useQueryClient();
  const [form, setForm] = useState({
    sqlDialect: connection?.sqlDialect ?? "",
    name: connection?.name ?? "",
    executionDatabase: connection?.executionDatabase ?? "",
    executionSchema: connection?.executionSchema ?? "",
    databaseType: connection?.databaseType ?? search.get("adapterId") ?? "",
    adapterId: connection?.adapterId ?? search.get("adapterId") ?? "",
    jdbcUrl: connection?.jdbcUrl ?? "",
    username: connection?.username ?? "",
    description: connection?.description ?? "",
  });
  const [driver, setDriver] = useState<DriverSummary | null>(
    connection?.defaultDriver ?? null,
  );
  const [password, setPassword] = useState("");
  const [emptyPassword, setEmptyPassword] = useState(false);
  const [savePassword, setSavePassword] = useState(true);
  const [clearSavedPassword, setClearSavedPassword] = useState(false);
  const [probe, setProbe] = useState<Probe | null>(null);
  const [error, setError] = useState<string | Error | null>(null);
  const dialectEdited = useRef(false);
  const databaseTypeEdited = useRef(false);
  const formElement = useRef<HTMLFormElement>(null);
  // Keep submitted credentials out of the mutation cache and browser storage.
  const requestDraft = useRef<{ form: typeof form; password: string } | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [deleting, setDeleting] = useState(false);
  const adapters = useQuery({
    queryKey: adapterKeys.all,
    queryFn: ({ signal }) => adaptersApi.list(signal),
    staleTime: 300_000,
  });
  const selectedAdapter = adapters.data?.find((item) => item.id === form.adapterId);
  const compatibleType = form.databaseType === "compatible" || (!!form.databaseType && !!selectedAdapter
    && form.databaseType !== selectedAdapter.id && form.databaseType !== selectedAdapter.name);
  const clearFieldError = (key: string) => setFieldErrors(previous => {
    const next = { ...previous };
    delete next[key];
    return next;
  });
  const change = (key: keyof typeof form, value: string) => {
    setForm((previous) => ({ ...previous, [key]: value }));
    clearFieldError(key);
  };
  const readDraft = () => {
    const current = { ...form };
    const fields = { name: "connection-name", jdbcUrl: "jdbc-url", username: "username",
      executionDatabase: "execution-database", executionSchema: "execution-schema", description: "description" } as const;
    for (const key of Object.keys(fields) as (keyof typeof fields)[]) {
      const input = formElement.current?.elements.namedItem(fields[key]);
      if (input instanceof HTMLInputElement || input instanceof HTMLTextAreaElement) current[key] = input.value;
    }
    const input = formElement.current?.elements.namedItem("password");
    return { form: current, password: input instanceof HTMLInputElement ? input.value : password };
  };
  const syncDraft = () => {
    const draft = readDraft();
    setForm(draft.form);
    setPassword(draft.password);
    setFieldErrors(previous => {
      const next = { ...previous };
      for (const key of Object.keys(draft.form) as (keyof typeof form)[]) {
        if (draft.form[key] !== form[key]) delete next[key];
      }
      if (draft.password !== password) delete next.password;
      return next;
    });
    return draft;
  };
  const save = useMutation({
    mutationFn: () => {
      const draft = requestDraft.current!;
      const input: ConnectionInput = {
        ...draft.form,
        sqlDialect: draft.form.sqlDialect || selectedAdapter?.dialect,
        defaultDriverId: driver!.id,
        savePassword,
        ...(connection ? { clearSavedPassword } : {}),
        ...(savePassword && (draft.password || emptyPassword) ? { password: emptyPassword ? "" : draft.password } : {}),
      };
      return connection
        ? connectionsApi.update(connection.id, input)
        : connectionsApi.create(input);
    },
    onSuccess: (result) => {
      setPassword(""); setEmptyPassword(false);
      toast.success(connection ? t("ui.connectionUpdated") : t("ui.connectionCreated"));
      void client.invalidateQueries({ queryKey: connectionsKeys.all });
      navigate(`/connections/${result.id}`);
    },
    onError: (failure) => setError(failure instanceof Error ? failure : new Error()),
    onSettled: () => { requestDraft.current = null; },
  });
  const test = useMutation({
    mutationFn: () => connectionsApi.testDraft({
      ...requestDraft.current!.form,
      ...(connection && !clearSavedPassword ? { savedConnectionId: connection.id } : {}),
      driverId: driver!.id,
      password: emptyPassword ? "" : requestDraft.current!.password || undefined,
    }),
    onSuccess: (result) => {
      setProbe(result);
      toast.success(t("ui.connectionEstablished"));
    },
    onError: (failure) => setError(failure instanceof Error ? failure : new Error()),
    onSettled: () => { requestDraft.current = null; },
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
  const dialectMismatch = !!form.sqlDialect && !!selectedAdapter && form.sqlDialect !== selectedAdapter.dialect;
  const databaseRequired = selectedAdapter?.requiresDatabase !== false;
  const schemaRequired = selectedAdapter?.namespaceSelection !== "catalog";
  const conflictingMysqlSchema = !schemaRequired && !!form.executionSchema && form.executionSchema !== form.executionDatabase;
  const passwordRequired = !connection && savePassword && !clearSavedPassword && !emptyPassword;
  const fieldError = (key: string) => {
    const value = fieldErrors[key];
    return value?.startsWith("required.") ? t("connection.fieldRequired", { field: t(value.slice(9)) }) : value ? t(value) : undefined;
  };
  const validate = (saving: boolean, draft: { form: typeof form; password: string }) => {
    const { form, password } = draft;
    const errors: Record<string, string> = {};
    const required = (key: string, value: string | undefined, label: string) => {
      if (!value?.trim()) errors[key] = `required.${label}`;
    };
    if (saving) required("name", form.name, "ui.name");
    if (!form.databaseType || (form.databaseType !== "compatible" && !selectedAdapter))
      errors.databaseType = "connection.selectDatabaseType";
    if (!selectedAdapter && form.databaseType === "compatible") errors.adapterId = "connection.selectCompatibilityAdapter";
    if (!driver || driver.status !== "VALIDATED") errors.driver = "connection.selectDriver";
    required("jdbcUrl", form.jdbcUrl, "ui.jdbcURL");
    if (!emptyPassword && !password && !(connection?.hasSavedPassword && !clearSavedPassword) && (!saving || (!connection && savePassword)))
      errors.password = "required.ui.password";
    if (saving && selectedAdapter) {
      if (databaseRequired) required("executionDatabase", form.executionDatabase, "ui.authorizedTestDatabase");
      if (schemaRequired) required("executionSchema", form.executionSchema, "ui.authorizedTestSchema");
      for (const key of [databaseRequired ? "executionDatabase" : null, schemaRequired ? "executionSchema" : null] as const) {
        if (key && form[key] && !/^[A-Za-z][A-Za-z0-9_]{0,62}$/.test(form[key])) errors[key] = "connection.identifierError";
      }
      if (conflictingMysqlSchema) errors.executionSchema = "connection.mysqlScopeConflict";
    }
    if (saving && dialectMismatch) errors.sqlDialect = "errors.ADAPTER_DIALECT_UNSUPPORTED";
    setFieldErrors(errors);
    if (Object.keys(errors).length) {
      const ids: Record<string, string> = { name: "connection-name", databaseType: "database-type", adapterId: "adapter",
        driver: "default-driver", jdbcUrl: "jdbc-url", password: "password", sqlDialect: "sql-dialect", executionDatabase: "execution-database", executionSchema: "execution-schema" };
      const firstError = Object.keys(ids).find(key => errors[key]);
      if (firstError) document.getElementById(ids[firstError])?.focus();
      return false;
    }
    return true;
  };
  const submit = (saving: boolean) => {
    if (busy || requestDraft.current) return;
    const draft = syncDraft();
    setError(null);
    if (!saving) setProbe(null);
    if (!validate(saving, draft)) return;
    requestDraft.current = draft;
    if (saving) save.mutate();
    else test.mutate();
  };
  return (
    <>
      <PageHeader
        title={connection ? connection.name : t("ui.addAConnection")}
        description=""
        action={connection ? (
          <div className="w-full sm:w-auto">
            <Button asChild><Link to={`/runs/new?connectionId=${connection.id}`}>{t("ui.startRun")}</Link></Button>
          </div>
        ) : undefined}
      />
      <form
        ref={formElement}
        className="max-w-4xl space-y-5"
        noValidate
        aria-busy={busy}
        onAnimationStart={event => {
          if (event.animationName === "jdbc-autofill-sync") syncDraft();
        }}
        onSubmit={(event) => {
          event.preventDefault();
          submit(true);
        }}
      >
        <Card as="section" aria-label={t("ui.connectionIdentity")} className="space-y-5">
          <div className="grid gap-x-5 gap-y-4 sm:grid-cols-2">
            <Field id="connection-name" label={t("ui.name")} error={fieldError("name")} required maxLength={200}
              value={form.name} onChange={(event) => change("name", event.target.value)}
              placeholder={t("ui.postgresqlTestWorkspace")} />
            <Field id="database-type" label={t("ui.databaseType")} required error={fieldError("databaseType")}
              hint={!selectedAdapter && !fieldErrors.databaseType ? t("connection.selectDatabaseType") : undefined}>
              <Select id="database-type" required
                value={compatibleType ? "compatible" : selectedAdapter?.id ?? ""}
                onValueChange={(value) => { databaseTypeEdited.current = true; clearFieldError("databaseType"); clearFieldError("adapterId"); setForm(previous => ({ ...previous,
                  databaseType: value === "compatible" ? value : adapters.data.find(item => item.id === value)?.name ?? value,
                  adapterId: value === "compatible" ? "" : value,
                })); }}>
                <option value="">{t("connection.selectDatabaseType")}</option>
                {adapters.data.map(adapter => <option key={adapter.id} value={adapter.id}>{adapter.name}</option>)}
                <option value="compatible">{t("adapter.compatibleType")}</option>
              </Select>
            </Field>
            {compatibleType ? (
              <div className="sm:col-span-2">
                <Field id="adapter" label={t("adapter.compatibleDialect")} hint={t("connection.compatibleHint")} required error={fieldError("adapterId")}>
                  <Select id="adapter" required value={form.adapterId} onValueChange={value => change("adapterId", value)}>
                    <option value="">{t("connection.selectCompatibilityAdapter")}</option>
                    {adapters.data.map(adapter => <option key={adapter.id} value={adapter.id}>{adapter.name} · {adapter.dialect}</option>)}
                  </Select>
                </Field>
              </div>
            ) : null}
            {connection && !selectedAdapter ? <p role="alert" className="text-sm text-destructive sm:col-span-2">{t("adapter.legacyConnection")}</p> : null}
            <DriverSelect value={driver?.id ?? ""} selected={driver} onChange={value => {
              setDriver(value);
              clearFieldError("driver");
              // A dialect (or vendor label) is not a database identity. Prefill only
              // from one adapter's exact declared driver class, before a manual choice.
              if (!connection && !databaseTypeEdited.current && !form.databaseType && "driverClass" in value) {
                const matches = adapters.data.filter(adapter => adapter.driverClass && adapter.driverClass === value.driverClass);
                if (matches.length === 1) {
                  setForm(previous => ({ ...previous, databaseType: matches[0].name, adapterId: matches[0].id }));
                  clearFieldError("databaseType"); clearFieldError("adapterId");
                }
              }
              if (!connection && !dialectEdited.current) change("sqlDialect", value.defaultSqlDialect ?? "");
            }}
              hint={t("connection.driverHint")} error={fieldError("driver")} />
            <Field id="jdbc-url" label={t("ui.jdbcURL")} error={fieldError("jdbcUrl")} required maxLength={2000}
              value={form.jdbcUrl} onChange={(event) => change("jdbcUrl", event.target.value)}
              placeholder="jdbc:postgresql://localhost:5432/test" hint={t(selectedAdapter?.id === "oracle" ? "connection.oracleServiceHint" : "connection.urlHint")} />
            <Field id="username" label={t("ui.username")} autoComplete="username" maxLength={200}
              value={form.username} onChange={(event) => change("username", event.target.value)} />
            <Field id="password"
              label={t("ui.password")} required={passwordRequired} error={fieldError("password")}
              hint={connection?.hasSavedPassword ? t("connection.keepPassword") : undefined}
              type="password" autoComplete="current-password" maxLength={2000}
              value={password} disabled={clearSavedPassword || emptyPassword}
              onChange={(event) => { setPassword(event.target.value); clearFieldError("password"); }} />
            <div className="flex flex-wrap items-center gap-x-5 gap-y-3 text-sm">
              {connection?.hasSavedPassword ? <p className="text-success">{t("ui.savedPasswordAvailable")}</p> : null}
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={emptyPassword} disabled={clearSavedPassword}
                  onChange={(event) => { setEmptyPassword(event.target.checked); setPassword(""); clearFieldError("password"); }} />
                {t("credentials.emptyPassword")}
              </label>
              </div>
            <DialectField value={form.sqlDialect || selectedAdapter?.dialect || ""}
              hint={!selectedAdapter ? t("connection.dialectNeedsDatabaseType") : undefined}
              error={dialectMismatch ? t("errors.ADAPTER_DIALECT_UNSUPPORTED") : undefined}
              onChange={value => { dialectEdited.current = true; change("sqlDialect", value); }} />
            {selectedAdapter ? <fieldset className="space-y-3 border-t pt-4 sm:col-span-2">
              <legend className="text-sm font-medium">{t("connection.scope")}</legend>
              <p className="text-xs text-muted-foreground">{t("connection.scopeHint")}</p>
              <div className="grid gap-4 sm:grid-cols-2">
                {databaseRequired ? <Field id="execution-database" label={t("ui.authorizedTestDatabase")} required
                  error={fieldError("executionDatabase")} maxLength={63} value={form.executionDatabase}
                  onChange={event => change("executionDatabase", event.target.value)} /> : null}
                {schemaRequired || conflictingMysqlSchema ? <Field id="execution-schema" label={t("ui.authorizedTestSchema")} required={schemaRequired}
                  error={fieldError("executionSchema")} maxLength={63} value={form.executionSchema}
                  onChange={event => change("executionSchema", event.target.value)} /> : null}
              </div>
              {conflictingMysqlSchema ? <p role="alert" className="text-sm text-destructive">{t("connection.mysqlScopeConflict")}</p> : null}
            </fieldset> : null}
          </div>
          <ProbeError error={error} />
          {probe ? <ProbeResult result={probe} /> : null}
          <div className="space-y-3 border-t pt-4">
            <div className="flex flex-wrap gap-2">
              <Button type="button" variant="outline"
                disabled={busy}
                onClick={() => submit(false)}>
                {test.isPending ? t("ui.testingConnection") : t("ui.testConnection")}
              </Button>
              <Button disabled={busy}>
                {save.isPending ? t("ui.saving") : t("ui.saveConnection")}
              </Button>
            </div>
            <p className="text-xs leading-relaxed text-muted-foreground">{t("connection.saveTestHint")}</p>
          </div>
          <details className="border-t pt-4">
              <summary className="cursor-pointer text-sm font-medium">{t("connection.advanced")}</summary>
              <div className="mt-3 flex flex-wrap gap-4 text-sm">
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={savePassword} disabled={clearSavedPassword}
                  onChange={(event) => setSavePassword(event.target.checked)} className="size-4 accent-primary" />
                {t("ui.savePassword")}
              </label>
              {connection?.hasSavedPassword ? <label className="flex items-center gap-2">
                <input type="checkbox" checked={clearSavedPassword} className="size-4 accent-primary"
                  onChange={(event) => {
                    setClearSavedPassword(event.target.checked);
                    setPassword(""); setSavePassword(!event.target.checked); setEmptyPassword(false);
                  }} />
                {t("ui.clearSavedPassword")}
              </label> : null}
              </div>
            </details>
          <details className="border-t pt-4">
            <summary className="cursor-pointer text-sm font-medium">{t("ui.descriptionOptional")}</summary>
            <div className="mt-3">
              <Field id="description" label={t("ui.descriptionOptional")}>
                <Textarea id="description" className="min-h-20" maxLength={1000} value={form.description}
                  onChange={(event) => change("description", event.target.value)} />
              </Field>
            </div>
          </details>
        </Card>
        {connection ? <ResourceTechnicalInfo id={connection.id} /> : null}
        {connection ? (
          <Card as="section" className="flex flex-wrap items-center justify-between gap-4">
            <div className="space-y-2 text-sm">
              <h2 className="font-medium">{t("ui.savedConnection")}</h2>
              {connection.lastConnectionTestStatus ? <StatusBadge {...connectionPresentation[connection.lastConnectionTestStatus]} />
                : <p className="text-muted-foreground">{t("ui.notTestedYet")}</p>}
              <p className="text-xs text-muted-foreground">
                {t("ui.lastTest")}{formatDateTime(connection.lastConnectionTestAt)} {t("ui.updatedInline")}{formatDateTime(connection.updatedAt)}
              </p>
            </div>
            <Button type="button" variant="destructive" onClick={() => setDeleting(true)}>{t("ui.deleteConnection")}</Button>
          </Card>
        ) : null}
      </form>
      <ConfirmDialog open={deleting} onOpenChange={setDeleting} title={t("ui.deleteConnectionQuestion")}
        description={t("ui.connectionsWithHistoricalTestRunReferencesCannot")}
        label={t("ui.deleteConnection")} pending={remove.isPending} onConfirm={() => remove.mutate()} />
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
    <Editor key={id} connection={query.data} />
  );
}
