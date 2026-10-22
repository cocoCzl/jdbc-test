import { Card } from "@/components/ui/card";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { toast } from "sonner";
import { driversApi, driversKeys } from "@/api/drivers";
import { errorMessage } from "@/api/client";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/alert-dialog";
import {
  CopyButton,
  ErrorState,
  Field,
  FormError,
  PendingPage,
  PageHeader,
  StatusBadge,
} from "@/components/common";
import { driverPresentation } from "@/lib/status";
import { formatBytes, formatDateTime } from "@/lib/format";
import type { Driver, DriverInput } from "@/types/api";
import { DialectField } from "./dialect-field";
import { DataSourceFields } from "./data-source-fields";
import { DriverCheckResult } from "./driver-check-result";
import { ResourceTechnicalInfo } from "@/components/resource-technical-info";

function MetadataForm({
  driver,
  close,
}: {
  driver: Driver;
  close: () => void;
}) {
  useTranslation();
  const [form, setForm] = useState<DriverInput>({
    name: driver.name,
    vendor: driver.vendor,
    version: driver.version,
    driverClass: driver.driverClass,
    urlPrefix: driver.urlPrefix ?? "",
    dataSources: driver.dataSources ?? {},
    defaultSqlDialect: driver.defaultSqlDialect ?? "",
  });
  const [error, setError] = useState<string | Error | null>(null);
  const client = useQueryClient();
  const update = useMutation({
    mutationFn: () => driversApi.update(driver.id, form),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: driversKeys.all });
      toast.success(t("ui.driverMetadataUpdated"));
      close();
    },
    onError: (failure) => setError(failure instanceof Error ? failure : new Error()),
  });
  return (
    <Card as="form"
      className="panel space-y-4"
      onSubmit={(event) => {
        event.preventDefault();
        update.mutate();
      }}
    >
      <h2 className="font-semibold">{t("ui.editMetadata")}</h2>
      <div className="grid gap-4 sm:grid-cols-2">
        {(
          ["name", "vendor", "version", "driverClass", "urlPrefix"] as const
        ).map((key) => (
          <Field
            key={key}
            id={`edit-${key}`}
            label={
              {
                name: t("ui.name"),
                vendor: t("ui.vendor"),
                version: t("ui.registeredDriverVersion"),
                driverClass: t("ui.driverClass"),
                urlPrefix: t("ui.jdbcURLPrefix"),
              }[key]
            }
            required={key !== "urlPrefix"}
            hint={key === "urlPrefix" ? t("driverPreset.urlPrefixHint") : undefined}
            placeholder={key === "urlPrefix" ? "jdbc:kingbase8:" : undefined}
            value={form[key] ?? ""}
            onChange={(event) =>
              setForm((previous) => ({
                ...previous,
                [key]: event.target.value,
              }))
            }
          />
        ))}
      </div>
      <DialectField driverDefault value={form.defaultSqlDialect ?? ""}
        onChange={value => setForm(previous => ({ ...previous, defaultSqlDialect: value }))} />
      <DataSourceFields title={t("ui.driverAdvancedInterfaces")} value={form.dataSources ?? {}}
        onChange={(dataSources) => setForm((previous) => ({ ...previous, dataSources }))} />
      <FormError message={error} />
      <div className="flex gap-2">
        <Button disabled={update.isPending}>
          {update.isPending ? t("ui.saving") : t("ui.saveMetadata")}
        </Button>
        <Button type="button" variant="outline" onClick={close}>
          {t("ui.cancelEdit")}</Button>
      </div>
    </Card>
  );
}
export default function DriverDetail() {
  useTranslation();
  const id = useParams().id!;
  const client = useQueryClient();
  const navigate = useNavigate();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const query = useQuery({
    queryKey: driversKeys.detail(id),
    queryFn: ({ signal }) => driversApi.get(id, signal),
  });
  const validate = useMutation({
    mutationFn: () => driversApi.validate(id),
    onSuccess: (result) => {
      if (result.warnings?.length) toast.warning(t("ui.driverValidatedWithWarnings"));
      else toast.success(t("ui.driverValidated"));
    },
    onError: (error) => toast.error(errorMessage(error)),
    onSettled: () => void client.invalidateQueries({ queryKey: driversKeys.all }),
  });
  const remove = useMutation({
    mutationFn: () => driversApi.delete(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: driversKeys.all });
      toast.success(t("ui.driverDeleted"));
      navigate("/drivers");
    },
    onError: (error) => {
      setDeleting(false);
      toast.error(errorMessage(error));
    },
  });
  if (query.isPending) return <PendingPage title={t("ui.driverPackage")} />;
  if (!query.data) return <PendingPage title={t("ui.driverPackage")} error={query.error} retry={() => void query.refetch()} />;
  const driver = query.data;
  return (
    <>
      {query.isError ? <ErrorState error={query.error} retry={() => void query.refetch()} /> : null}
      <PageHeader
        title={driver.name}
        description={`${driver.vendor} · ${t("ui.registeredDriverVersion")}: ${driver.version}`}
        action={
          <div className="flex w-full flex-wrap gap-2 sm:w-auto">
            <Button variant="outline" onClick={() => setEditing(!editing)}>
              {t("ui.editMetadata")}</Button>
            <Button
              disabled={validate.isPending}
              onClick={() => validate.mutate()}
            >
              {validate.isPending ? t("ui.validating") : t("ui.validateDriver")}
            </Button>
          </div>
        }
      />
      <div className="space-y-4">
        <FormError message={validate.error} />
        {validate.isSuccess && validate.data ? (
          <DriverCheckResult driver={driver} result={validate.data} />
        ) : null}
        {editing ? (
          <MetadataForm driver={driver} close={() => setEditing(false)} />
        ) : null}
        <Card as="section" className="panel min-w-0">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
            <h2 className="font-semibold">{t("ui.driverBasicInfo")}</h2>
            <StatusBadge {...driverPresentation[driver.status]} />
          </div>
          <dl className="grid gap-x-6 gap-y-4 text-sm md:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,1fr)]">
            {[
              [t("ui.driverClass"), driver.driverClass],
              [t("ui.jdbcURLPrefix"), driver.urlPrefix ?? "—"],
              [t("ui.defaultSqlDialect"), driver.defaultSqlDialect
                ? t(`driverPreset.${driver.defaultSqlDialect}`) : t("connection.followConnection")],
            ].map(([label, value]) => (
              <div key={label} className="min-w-0">
                <dt className="text-muted-foreground">{label}</dt>
                <dd className="mt-1 break-all font-medium">{value}</dd>
              </div>
            ))}
          </dl>
          <dl className="mt-4 flex flex-wrap gap-x-6 gap-y-2 border-t pt-3 text-xs text-muted-foreground">
            {[
              [t("ui.source"), t(`driverSource.${driver.sourceType}`)],
              [t("ui.created"), formatDateTime(driver.createdAt)],
              [t("ui.updated"), formatDateTime(driver.updatedAt)],
            ].map(([label, value]) => (
              <div key={label} className="flex flex-wrap gap-x-2">
                <dt>{label}</dt><dd>{value}</dd>
              </div>
            ))}
          </dl>
          <details className="mt-3 border-t pt-3" aria-labelledby="driver-advanced-interfaces-title">
            <summary id="driver-advanced-interfaces-title" className="cursor-pointer text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-4">
              {t("ui.driverAdvancedInterfaces")}
            </summary>
            <dl className="mt-3 grid gap-3 text-sm md:grid-cols-3">
              {Object.entries({
                DataSource: driver.dataSources?.dataSource?.className,
                ConnectionPoolDataSource: driver.dataSources?.connectionPoolDataSource?.className,
                XADataSource: driver.dataSources?.xaDataSource?.className,
              }).map(([label, value]) => (
                <div key={label} className="min-w-0">
                  <dt className="break-all text-muted-foreground">{label}</dt>
                  <dd className="mt-1 break-all">{value ?? t("ui.driverInterfaceNotConfigured")}</dd>
                </div>
              ))}
            </dl>
          </details>
        </Card>
        <Card as="section" className="panel min-w-0" aria-labelledby="driver-artifacts-title">
          <h2 id="driver-artifacts-title" className="mb-3 font-semibold">{t("ui.artifacts")}</h2>
          <ul className="divide-y">
            {driver.artifacts.map((artifact) => (
              <li key={artifact.id} className="flex items-start justify-between gap-3 py-2 text-sm">
                <span className="min-w-0 break-all font-medium">{artifact.fileName}</span>
                <span className="shrink-0 text-muted-foreground">{formatBytes(artifact.size)}</span>
              </li>
            ))}
          </ul>
          <details className="mt-3 border-t pt-3" aria-labelledby="driver-fingerprints-title">
            <summary id="driver-fingerprints-title" className="cursor-pointer text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-4">
              {t("ui.driverFingerprints")}
            </summary>
            <dl className="mt-3 space-y-3 text-sm">
              {[
                [t("ui.packageFingerprint"), driver.packageFingerprint],
                ...driver.artifacts.map(artifact => [`${artifact.fileName} · SHA-256`, artifact.sha256]),
              ].map(([label, value]) => (
                <div key={label} className="min-w-0">
                  <dt className="break-all text-muted-foreground">{label}</dt>
                  <dd className="mt-1 flex items-start gap-2">
                    <code className="min-w-0 break-all text-xs">{value ?? "—"}</code>
                    {value ? <CopyButton value={value} label={label!} /> : null}
                  </dd>
                </div>
              ))}
            </dl>
          </details>
        </Card>
        <ResourceTechnicalInfo id={driver.id} />
        <Button variant="destructive" onClick={() => setDeleting(true)}>
          {t("ui.deleteDriverPackage")}</Button>
      </div>
      <ConfirmDialog
        open={deleting}
        onOpenChange={setDeleting}
        title={t("ui.deleteDriverPackageQuestion")}
        description={t("ui.thisDeletesItsArtifactsReferencedDriversCannot")}
        label={t("ui.deleteDriver")}
        pending={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
    </>
  );
}
