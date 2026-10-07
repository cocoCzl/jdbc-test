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
  Loading,
  PageHeader,
  StatusBadge,
} from "@/components/common";
import { driverPresentation } from "@/lib/status";
import { formatBytes, formatDateTime } from "@/lib/format";
import type { Driver, DriverInput } from "@/types/api";
function MetadataForm({
  driver,
  close,
}: {
  driver: Driver;
  close: () => void;
}) {
  const [form, setForm] = useState<DriverInput>({
    name: driver.name,
    vendor: driver.vendor,
    version: driver.version,
    driverClass: driver.driverClass,
    urlPrefix: driver.urlPrefix ?? "",
  });
  const [error, setError] = useState<string | null>(null);
  const client = useQueryClient();
  const update = useMutation({
    mutationFn: () => driversApi.update(driver.id, form),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: driversKeys.all });
      toast.success("Driver metadata updated");
      close();
    },
    onError: (failure) => setError(errorMessage(failure)),
  });
  return (
    <form
      className="panel space-y-4"
      onSubmit={(event) => {
        event.preventDefault();
        update.mutate();
      }}
    >
      <h2 className="font-semibold">Edit metadata</h2>
      <div className="grid gap-4 sm:grid-cols-2">
        {(
          ["name", "vendor", "version", "driverClass", "urlPrefix"] as const
        ).map((key) => (
          <Field
            key={key}
            id={`edit-${key}`}
            label={
              {
                name: "Name",
                vendor: "Vendor",
                version: "Version",
                driverClass: "Driver class",
                urlPrefix: "URL prefix",
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
      <FormError message={error} />
      <div className="flex gap-2">
        <Button disabled={update.isPending}>
          {update.isPending ? "Saving…" : "Save metadata"}
        </Button>
        <Button type="button" variant="outline" onClick={close}>
          Cancel edit
        </Button>
      </div>
    </form>
  );
}
export default function DriverDetail() {
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
      toast.success("Driver validated");
      void client.invalidateQueries({ queryKey: driversKeys.all });
    },
    onError: (error) => toast.error(errorMessage(error)),
  });
  const remove = useMutation({
    mutationFn: () => driversApi.delete(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: driversKeys.all });
      toast.success("Driver deleted");
      navigate("/drivers");
    },
    onError: (error) => {
      setDeleting(false);
      toast.error(errorMessage(error));
    },
  });
  if (query.isPending) return <Loading />;
  if (query.isError)
    return (
      <ErrorState error={query.error} retry={() => void query.refetch()} />
    );
  const driver = query.data;
  return (
    <>
      <PageHeader
        title={driver.name}
        description={`${driver.vendor} · ${driver.version}`}
        action={
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setEditing(!editing)}>
              Edit metadata
            </Button>
            <Button
              disabled={validate.isPending}
              onClick={() => validate.mutate()}
            >
              {validate.isPending ? "Validating…" : "Validate driver"}
            </Button>
          </div>
        }
      />
      <div className="space-y-6">
        {editing ? (
          <MetadataForm driver={driver} close={() => setEditing(false)} />
        ) : null}
        <section className="panel">
          <div className="mb-6 flex items-center justify-between">
            <h2 className="text-lg font-semibold">Driver package</h2>
            <StatusBadge {...driverPresentation[driver.status]} />
          </div>
          <dl className="grid gap-6 text-sm md:grid-cols-3">
            {Object.entries({
              "Driver class": driver.driverClass,
              "JDBC URL prefix": driver.urlPrefix ?? "—",
              Source: driver.sourceType,
              Created: formatDateTime(driver.createdAt),
              Updated: formatDateTime(driver.updatedAt),
              Version: driver.version,
            }).map(([label, value]) => (
              <div key={label}>
                <dt className="mb-2 text-muted-foreground">{label}</dt>
                <dd className="break-all">{value}</dd>
              </div>
            ))}
          </dl>
          <div className="mt-6 border-t pt-5">
            <p className="text-sm text-muted-foreground">Package fingerprint</p>
            <div className="flex items-center gap-2">
              <code className="break-all text-xs">
                {driver.packageFingerprint ?? "—"}
              </code>
              {driver.packageFingerprint ? (
                <CopyButton
                  value={driver.packageFingerprint}
                  label="fingerprint"
                />
              ) : null}
            </div>
          </div>
        </section>
        {validate.data ? (
          <section role="status" className="panel border-emerald-100">
            <h2 className="font-semibold text-success">
              Validated in an isolated Worker
            </h2>
            <p className="mt-2 text-sm">
              {validate.data.driverClass} · Version {validate.data.majorVersion}
              .{validate.data.minorVersion} · JDBC compliant:{" "}
              {validate.data.jdbcCompliant ? "Yes" : "No"}
            </p>
            <p className="mt-2 text-xs text-muted-foreground">
              Metadata from this validation response; not a permanently stored
              probe.
            </p>
          </section>
        ) : null}
        <section className="panel">
          <h2 className="mb-5 text-lg font-semibold">Artifacts</h2>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b text-xs text-muted-foreground">
                <tr>
                  <th className="py-3">Filename</th>
                  <th>Size</th>
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
            Binary artifacts are immutable. To replace a JAR, create a new
            driver package.
          </p>
        </section>
        <Button variant="destructive" onClick={() => setDeleting(true)}>
          Delete driver package
        </Button>
      </div>
      <ConfirmDialog
        open={deleting}
        onOpenChange={setDeleting}
        title="Delete driver package?"
        description="This deletes its artifacts. Referenced drivers cannot be deleted."
        label="Delete driver"
        pending={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
    </>
  );
}
