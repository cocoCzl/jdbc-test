import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { request } from "@/api/client";
import { connectionsApi } from "@/api/connections";
import { adaptersApi } from "@/api/adapters";
import { runsApi } from "@/api/runs";
import { PageHeader, Field, ErrorState, Loading } from "@/components/common";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/select";
import { AdapterDetails } from "@/features/connections/adapter-details";
import type { SqlPackage, Connection } from "@/types/api";

export default function SqlPackages() {
  const { t } = useTranslation();
  const client = useQueryClient();
  const navigate = useNavigate();
  const [file, setFile] = useState<File | null>(null);
  const [fingerprint, setFingerprint] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [connectionId, setConnectionId] = useState("");
  const [password, setPassword] = useState("");
  const [consent, setConsent] = useState(false);
  const packages = useQuery({ queryKey: ["sql-packages"], queryFn: ({ signal }) => request<SqlPackage[]>("/sql-packages", { signal }) });
  const connections = useQuery({ queryKey: ["sql-connections"], queryFn: async ({ signal }) => {
    const items: Connection[] = [];
    let page = 0;
    let total = 1;
    while (page < total) {
      const result = await connectionsApi.list({ page, size: 100 }, signal);
      items.push(...result.items);
      total = result.totalPages;
      page++;
    }
    return items;
  } });
  const adapters = useQuery({ queryKey: ["adapters"], queryFn: ({ signal }) => adaptersApi.list(signal) });
  const chosen = packages.data?.find(item => item.fingerprint === fingerprint);
  const connection = connections.data?.find(item => item.id === connectionId);
  const adapter = adapters.data?.find(item => item.id === connection?.adapterId);
  const upload = useMutation({ mutationFn: () => {
    const body = new FormData();
    body.append("file", file!);
    return request<SqlPackage>("/sql-packages", { method: "POST", body });
  }, onSuccess: data => {
    client.setQueryData<SqlPackage[]>(["sql-packages"], old => [...(old ?? []).filter(item => item.fingerprint !== data.fingerprint), data]);
    void client.invalidateQueries({ queryKey: ["sql-packages"] });
    setFingerprint("");
    setSelected([]);
  } });
  const run = useMutation({ mutationFn: () => runsApi.create({
    connectionId, driverId: connection?.defaultDriver?.id ?? "", profile: "CUSTOM",
    modules: ["sql_feature"], includeScenarios: selected, destructiveConsent: consent,
    sqlPackageFingerprint: fingerprint, catalogVersion: `sql-v1:${fingerprint}`,
    expectedAdapterFingerprint: adapter?.fingerprint ?? undefined,
    ...(password ? { password } : {}),
  }), onSuccess: data => { setPassword(""); void navigate(`/runs/${data.id}`); } });
  return <>
    <PageHeader title={t("sql.manage")} description={t("sql.boundary")} />
    <Card as="form" className="mb-4 space-y-4" onSubmit={event => { event.preventDefault(); upload.mutate(); }}>
      <Field id="sql-file" label={t("sql.file")}><Input id="sql-file" type="file" accept=".json,.zip" onChange={event => { setFile(event.target.files?.[0] ?? null); upload.reset(); }} /></Field>
      <Button disabled={!file || upload.isPending}>{t("sql.import")}</Button>
      {upload.isError ? <ErrorState error={upload.error} /> : null}
      {upload.isSuccess ? <p role="status">{t("sql.imported")}</p> : null}
      <details><summary>{t("sql.template")}</summary><p className="my-3 text-sm">{t("sql.templateHelp")}</p><pre className="overflow-auto text-xs">{`examples/sql/postgresql-basics.json\npython3 scripts/runner.py sql-validate --package examples/sql/postgresql-basics.json`}</pre></details>
    </Card>
    {packages.isPending ? <Loading /> : packages.isError ? <ErrorState error={packages.error} /> : <Card as="form" className="space-y-4" onSubmit={event => { event.preventDefault(); run.mutate(); }}>
      <Field id="sql-package" label={t("sql.package")}><Select id="sql-package" value={fingerprint} onValueChange={value => { setFingerprint(value); setSelected([]); setConsent(false); run.reset(); }}>
        <option value="">{t("sql.choose")}</option>{packages.data.map(item => <option key={item.fingerprint} value={item.fingerprint}>{item.definition.id} · {item.definition.version} · {item.fingerprint.slice(0, 12)} · {item.origin}</option>)}
      </Select></Field>
      {chosen ? <><p>{chosen.definition.description}</p><p className="break-all font-mono text-xs">{chosen.fingerprint}</p>
        <fieldset className="space-y-3"><legend className="mb-2 font-medium">{t("sql.cases")}</legend>{chosen.definition.cases.map(item => <div key={item.id} className="rounded-lg border p-3 text-sm">
          <label className="flex items-center gap-2"><input type="checkbox" checked={selected.includes(item.id)} onChange={event => setSelected(value => event.target.checked ? [...value, item.id] : value.filter(id => id !== item.id))} />{item.id} · {item.name}</label>
          <p>{item.tags.join(", ")} · {item.appliesTo.products.join(", ")} · {item.appliesTo.dialects.join(", ")} · {item.appliesTo.minMajor}–{item.appliesTo.maxMajor}</p>
          <p>{item.appliesTo.modeDescription}</p><p className="text-muted-foreground">{t("sql.unverified")}</p>
          <details><summary>{t("sql.definition")}</summary><pre className="overflow-auto whitespace-pre-wrap break-words text-xs">{JSON.stringify(item, null, 2)}</pre></details>
        </div>)}</fieldset></> : null}
      <Field id="sql-connection" label={t("sql.connection")}><Select id="sql-connection" value={connectionId} onValueChange={value => { setConnectionId(value); setPassword(""); setConsent(false); run.reset(); }}>
        <option value="">{t("sql.choose")}</option>{connections.data?.map(item => <option key={item.id} value={item.id}>{item.name} · {item.adapterId}</option>)}
      </Select></Field>
      {connections.isError ? <ErrorState error={connections.error} /> : null}
      {adapters.isError ? <ErrorState error={adapters.error} /> : null}
      {adapter ? <AdapterDetails adapter={adapter} /> : null}
      {connection ? <p className="text-sm">{t("sql.scope")}: {connection.executionDatabase ?? "—"} / {connection.executionSchema ?? "—"}</p> : null}
      <Field id="sql-password" label={t("sql.password")}><Input id="sql-password" type="password" autoComplete="off" value={password} onChange={event => setPassword(event.target.value)} /></Field>
      <label className="flex items-start gap-2 text-sm"><input type="checkbox" checked={consent} onChange={event => setConsent(event.target.checked)} />{t("sql.consent")}</label>
      <Button disabled={!chosen || !connection?.defaultDriver || !adapter || !selected.length || !consent || run.isPending}>{t("sql.run")}</Button>
      {run.isError ? <ErrorState error={run.error} /> : null}
    </Card>}
  </>;
}
