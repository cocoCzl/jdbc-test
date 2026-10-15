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
import { DataSourceFields } from "./data-source-fields";

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
                version: t("ui.version"),
                driverClass: t("ui.driverClass"),
                urlPrefix: t("ui.urlPrefix"),
              }[key]
            }
            required={key !== "urlPrefix"}
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
      <DataSourceFields value={form.dataSources ?? {}}
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
    onSuccess: () => {
      toast.success(t("ui.driverValidated"));
      void client.invalidateQueries({ queryKey: driversKeys.all });
    },
    onError: (error) => toast.error(errorMessage(error)),
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
        description={`${driver.vendor} · ${driver.version}`}
        action={
          <div className="flex gap-2">
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
      <div className="space-y-6">
        {editing ? (
          <MetadataForm driver={driver} close={() => setEditing(false)} />
        ) : null}
        <Card as="section" className="panel">
          <div className="mb-6 flex items-center justify-between">
            <h2 className="text-lg font-semibold">{t("ui.driverPackage")}</h2>
            <StatusBadge {...driverPresentation[driver.status]} />
          </div>
          <dl className="grid gap-6 text-sm md:grid-cols-3">
            {Object.entries({
              [t("ui.driverClass")]: driver.driverClass,
              [t("ui.jdbcURLPrefix")]: driver.urlPrefix ?? "—",
              DataSource: driver.dataSources?.dataSource?.className ?? "—",
              ConnectionPoolDataSource: driver.dataSources?.connectionPoolDataSource?.className ?? "—",
              XADataSource: driver.dataSources?.xaDataSource?.className ?? "—",
              [t("ui.source")]: t(`driverSource.${driver.sourceType}`),
              [t("ui.created")]: formatDateTime(driver.createdAt),
              [t("ui.updated")]: formatDateTime(driver.updatedAt),
              [t("ui.version")]: driver.version,
            }).map(([label, value]) => (
              <div key={label}>
                <dt className="mb-2 text-muted-foreground">{label}</dt>
                <dd className="break-all">{value}</dd>
              </div>
            ))}
          </dl>
          <div className="mt-6 border-t pt-5">
            <p className="text-sm text-muted-foreground">{t("ui.packageFingerprint")}</p>
            <div className="flex items-center gap-2">
              <code className="break-all text-xs">
                {driver.packageFingerprint ?? "—"}
              </code>
              {driver.packageFingerprint ? (
                <CopyButton
                  value={driver.packageFingerprint}
                  label={t("ui.fingerprintInline")}
                />
              ) : null}
            </div>
          </div>
        </Card>
        {validate.data ? (
          <Card as="section" role="status" className="panel border-success/20">
            <h2 className="font-semibold text-success">
              {t("ui.validatedInAnIsolatedWorker")}</h2>
            <p className="mt-2 text-sm">
              {validate.data.driverClass}  {t("ui.versionInline")} {validate.data.majorVersion}
              .{validate.data.minorVersion}  {t("ui.jdbcCompliant")}{" "}
              {validate.data.jdbcCompliant ? t("ui.yes") : t("ui.no")}
            </p>
            <p className="mt-2 text-xs text-muted-foreground">
              {t("ui.metadataFromThisValidationResponseNotA")}</p>
          </Card>
        ) : null}
        <Card as="section" className="panel">
          <h2 className="mb-5 text-lg font-semibold">{t("ui.artifacts")}</h2>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b text-xs text-muted-foreground">
                <tr>
                  <th className="py-3">{t("ui.filename")}</th>
                  <th>{t("ui.size")}</th>
                  <th>SHA-256</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {driver.artifacts.map((artifact) => (
                  <tr key={artifact.id}>
                    <td className="py-4 pr-4">{artifact.fileName}</td>
                    <td className="whitespace-nowrap pr-4">
                      {formatBytes(artifact.size)}
                    </td>
                    <td>
                      <div className="flex items-center">
                        <code className="break-all text-xs">
                          {artifact.sha256}
                        </code>
                        <CopyButton
                          value={artifact.sha256}
                          label={`${artifact.fileName} SHA-256`}
                        />
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="mt-5 text-xs text-muted-foreground">
            {t("ui.binaryArtifactsAreImmutableToReplaceA")}</p>
        </Card>
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
