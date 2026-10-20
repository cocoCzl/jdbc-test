import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { adaptersApi, adapterKeys } from "@/api/adapters";
import { ApiError } from "@/api/client";
import { AdapterDetails } from "@/features/connections/adapter-details";
import { PageHeader, ErrorState, Loading, Field } from "@/components/common";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { ConfirmDialog } from "@/components/ui/alert-dialog";
import type { Adapter } from "@/types/api";

export default function Adapters() {
  const { t } = useTranslation();
  const client = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [updating, setUpdating] = useState("");
  const [deleting, setDeleting] = useState<Adapter | null>(null);
  const query = useQuery({ queryKey: adapterKeys.all, queryFn: ({ signal }) => adaptersApi.list(signal) });
  const target = query.data?.find(item => item.id === updating);
  const refresh = () => void client.invalidateQueries({ queryKey: adapterKeys.all });
  const upload = useMutation({
    mutationFn: () => adaptersApi.import(file!, target?.fingerprint ?? undefined),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: () => adaptersApi.delete(deleting!.id),
    onSuccess: () => { setDeleting(null); refresh(); },
  });
  return <>
    <PageHeader title={t("adapter.manage")} description={t("adapter.importBoundary")} />
    <Card as="form" className="space-y-4" onSubmit={event => { event.preventDefault(); upload.mutate(); }}>
      <Field id="adapter-file" label={t("adapter.zipFile")}>
        <Input id="adapter-file" type="file" accept=".zip" required onChange={event => { setFile(event.target.files?.[0] ?? null); upload.reset(); }} />
      </Field>
      <Field id="adapter-update" label={t("adapter.importMode")}>
        <Select id="adapter-update" value={updating} onValueChange={value => { setUpdating(value); upload.reset(); }}>
          <option value="">{t("adapter.newImport")}</option>
          {query.data?.filter(item => item.origin === "IMPORTED").map(item => <option key={item.id} value={item.id}>{t("adapter.updateName", { name: item.name })}</option>)}
        </Select>
      </Field>
      {target ? <p className="break-all text-sm">{t("adapter.updateBoundary", { fingerprint: target.fingerprint })}</p> : null}
      <Button type="submit" disabled={!file || upload.isPending}>{t(upload.isPending ? "ui.pleaseWait" : "adapter.importAction")}</Button>
      {upload.isError ? <div role="alert"><ErrorState error={upload.error} />{upload.error instanceof ApiError ? <p className="mt-2 break-words text-sm">{upload.error.detail}</p> : null}</div> : null}
      {upload.isSuccess ? <div role="status" className="space-y-3"><p>{t("adapter.importSuccess")}</p><AdapterDetails adapter={upload.data} /><Button asChild><Link to={`/connections/new?adapterId=${encodeURIComponent(upload.data.id)}`}>{t("ui.addAConnection")}</Link></Button></div> : null}
      <details><summary>{t("adapter.template")}</summary><p className="my-3 text-sm">{t("adapter.templateHelp")}</p><pre className="overflow-x-auto rounded-lg bg-muted p-3 text-xs">{`python3 -B scripts/runner.py init-custom --help\npython3 -B scripts/runner.py pack-adapter --adapter /path/to/local-package --output /path/to/adapter.zip`}</pre></details>
    </Card>
    {remove.isError ? <ErrorState error={remove.error} /> : null}
    {query.isPending ? <Loading /> : query.data ? <div className="mt-5 grid gap-4">
      {query.data.map(adapter => <Card key={adapter.id} className="space-y-3"><AdapterDetails adapter={adapter} />
        <div className="flex flex-wrap gap-2"><Button asChild variant="outline"><Link to={`/connections/new?adapterId=${encodeURIComponent(adapter.id)}`}>{t("ui.addAConnection")}</Link></Button>
          {adapter.origin === "IMPORTED" ? <Button variant="ghost" onClick={() => setDeleting(adapter)}>{t("adapter.archive")}</Button> : null}</div>
      </Card>)}
    </div> : <ErrorState error={query.error} retry={() => void query.refetch()} />}
    <ConfirmDialog open={!!deleting} onOpenChange={open => { if (!open) setDeleting(null); }} title={t("adapter.archive")} description={t("adapter.archiveBoundary")} label={t("adapter.archive")} pending={remove.isPending} onConfirm={() => remove.mutate()} />
  </>;
}
