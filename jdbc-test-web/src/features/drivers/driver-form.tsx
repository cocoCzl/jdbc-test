import { Card } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { FileArchive, UploadCloud, X } from "lucide-react";
import { toast } from "sonner";
import { driversApi, driversKeys } from "@/api/drivers";
import { ApiError } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Field, FormError, PageHeader } from "@/components/common";
import { formatBytes } from "@/lib/format";
import { validateFiles } from "@/lib/driver-upload";
import { DialectField } from "./dialect-field";
import { DataSourceFields } from "./data-source-fields";
import type { DataSourceProviders, DriverPreset, SqlDialect } from "@/types/api";

const presetFields = ["vendor", "driverClass", "urlPrefix"] as const;
type PresetField = (typeof presetFields)[number];

export default function DriverForm() {
  useTranslation();
  const navigate = useNavigate();
  const client = useQueryClient();
  const uploadInput = useRef<HTMLInputElement>(null);
  // An explicit edit (including clearing or choosing a candidate) gives the user ownership.
  const editedFields = useRef(new Set<PresetField>());
  const [presetCode, setPresetCode] = useState("");
  const presets = useQuery({
    queryKey: driversKeys.presets,
    queryFn: ({ signal }) => driversApi.presets(signal),
    retry: false,
  });
  const selectedPreset = presets.data?.find((preset) => preset.code === presetCode);
  const [form, setForm] = useState({
    name: "",
    vendor: "",
    version: "",
    driverClass: "",
    urlPrefix: "",
    defaultSqlDialect: "" as SqlDialect | "",
  });
  const [dataSources, setDataSources] = useState<DataSourceProviders>({});
  const [files, setFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | Error | null>(null);
  const [uploadedId, setUploadedId] = useState<string>();
  const [candidates, setCandidates] = useState<string[]>([]);
  const upload = useMutation({
    mutationFn: () =>
      driversApi.upload(
        {
          ...form,
          defaultSqlDialect: form.defaultSqlDialect || undefined,
          dataSources,
          driverClass: form.driverClass || undefined,
          urlPrefix: form.urlPrefix || undefined,
        },
        files,
      ),
    onSuccess: (driver) => {
      toast.success(t("ui.driverPackageUploaded"));
      void client.invalidateQueries({ queryKey: driversKeys.all });
      navigate(`/drivers/${driver.id}`);
    },
    onError: (failure) => {
      setError(failure instanceof Error ? failure : new Error());
      if (failure instanceof ApiError && failure.code === "DRIVER_DIALECT_NOT_SAVED"
        && typeof failure.properties.driverId === "string") {
        setUploadedId(failure.properties.driverId);
        void client.invalidateQueries({ queryKey: driversKeys.all });
      }
      if (
        failure instanceof ApiError &&
        Array.isArray(failure.properties.candidates)
      )
        setCandidates(
          failure.properties.candidates.filter(
            (item): item is string => typeof item === "string",
          ),
        );
    },
  });
  const change = (key: keyof typeof form, value: string) => {
    if (key === "vendor" || key === "driverClass" || key === "urlPrefix")
      editedFields.current.add(key);
    setForm((previous) => ({ ...previous, [key]: value }));
  };
  const applyPreset = (preset: DriverPreset, restore = false) => {
    // Capture ownership before scheduling the update; the updater stays pure.
    const fields = presetFields.filter((key) => restore || !editedFields.current.has(key));
    if (restore) editedFields.current.clear();
    setForm((previous) => {
      const next = { ...previous };
      for (const key of fields) next[key] = preset[key];
      return next;
    });
  };
  return (
    <div className="workspace-page">
      <PageHeader
        title={t("ui.addADriver")}
        description={t("driverUpload.description")}
      />
      <form
        className="upload-layout"
        onSubmit={(event) => {
          event.preventDefault();
          const failure = validateFiles(files);
          setError(failure);
          if (!failure && !uploadedId) upload.mutate();
        }}
      >
        <div className="min-w-0 space-y-4">
          <Card as="section" className="panel min-w-0" aria-labelledby="driver-files-heading">
            <h2 id="driver-files-heading" className="mb-3 text-lg font-semibold">{t("ui.driverArtifacts")}</h2>
            <input
              ref={uploadInput}
              type="file"
              multiple
              accept=".jar"
              aria-label={t("ui.jarFiles")}
              className="sr-only"
              onChange={(event) => {
                const selectedFiles = Array.from(event.target.files ?? []);
                setFiles((previous) => [...previous, ...selectedFiles]);
                event.target.value = "";
              }}
            />
            <div
              role="group"
              aria-label={t("ui.driverArtifacts")}
              onDragOver={(event) => event.preventDefault()}
              onDrop={(event) => {
                event.preventDefault();
                if (!upload.isPending)
                  setFiles((previous) => [
                    ...previous,
                    ...Array.from(event.dataTransfer.files),
                  ]);
              }}
              className="flex flex-col items-start gap-3 rounded-xl border-2 border-dashed border-input bg-background p-4 sm:flex-row sm:items-center"
            >
              <UploadCloud className="size-6 shrink-0 text-primary" aria-hidden="true" />
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium">{t("driverUpload.fileHint")}</p>
                <p className="mt-1 text-xs text-muted-foreground">{t("driverUpload.dropHint")}</p>
              </div>
              <Button type="button" variant="outline" disabled={upload.isPending}
                className="shrink-0" onClick={() => uploadInput.current?.click()}>
                {t("driverUpload.chooseFiles")}
              </Button>
            </div>
            <p className="mt-3 text-xs text-muted-foreground">{t("ui.upTo20Files100MBPer")}</p>
            <details className="mt-3 text-sm text-muted-foreground">
              <summary className="cursor-pointer">{t("driverUpload.dependencyHelp")}</summary>
              <p className="mt-2 leading-relaxed">{t("ui.driverCompanionHint")}</p>
            </details>
            <ul className={files.length ? "mt-3 divide-y divide-border" : undefined}>
              {files.map((file, index) => (
                <li
                  key={`${file.name}-${index}`}
                  className="flex items-center gap-3 py-3"
                >
                  <FileArchive className="size-5 text-muted-foreground" />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm">{file.name}</p>
                    <p className="text-xs text-muted-foreground">
                      {formatBytes(file.size)}
                    </p>
                  </div>
                  <Button
                    type="button"
                    size="icon"
                    variant="ghost"
                    disabled={upload.isPending}
                    aria-label={t("common.removeName", {name: file.name})}
                    onClick={() =>
                      setFiles((previous) =>
                        previous.filter((_, position) => position !== index),
                      )
                    }
                  >
                    <X />
                  </Button>
                </li>
              ))}
            </ul>
          </Card>
          <Card as="section" className="panel">
            <h2 className="mb-4 text-lg font-semibold">{t("ui.packageIdentity")}</h2>
            <div className="mb-4 grid items-start gap-3 md:grid-cols-[minmax(0,1fr)_auto]">
              <Field id="driver-preset" label={t("driverPreset.label")}
                hint={t("driverPreset.hint")}>
                <Select id="driver-preset" value={presetCode}
                  disabled={!presets.data?.length}
                  onValueChange={(code) => {
                    setPresetCode(code);
                    const preset = presets.data?.find((item) => item.code === code);
                    if (preset) applyPreset(preset);
                  }}>
                  <option value="" disabled>{t("driverPreset.placeholder")}</option>
                  {presets.data?.map((preset) => (
                    <option key={preset.code} value={preset.code}>{t(preset.nameKey)}</option>
                  ))}
                </Select>
              </Field>
              {selectedPreset ? (
                <Button type="button" variant="outline" size="sm"
                  className="md:mt-7" onClick={() => applyPreset(selectedPreset, true)}>
                  {t("driverPreset.restore")}
                </Button>
              ) : null}
              {presets.isError ? (
                <p role="status" className="text-sm text-muted-foreground">
                  {t("driverPreset.loadFailed")}
                </p>
              ) : null}
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field
                label={t("ui.name")}
                id="driver-name"
                required
                maxLength={200}
                value={form.name}
                onChange={(event) => change("name", event.target.value)}
                placeholder="PostgreSQL JDBC"
              />
              <Field
                label={t("ui.vendor")}
                id="driver-vendor"
                required
                maxLength={120}
                value={form.vendor}
                onChange={(event) => change("vendor", event.target.value)}
                placeholder="PostgreSQL"
              />
              <Field
                label={t("ui.registeredDriverVersion")}
                id="driver-version"
                required
                maxLength={80}
                value={form.version}
                onChange={(event) => change("version", event.target.value)}
                placeholder={t("ui.yourDriverVersion")}
              />
              <DialectField driverDefault value={form.defaultSqlDialect}
                onChange={value => setForm(previous => ({ ...previous, defaultSqlDialect: value }))} />
              <div className="sm:col-span-2">
                <Field
                  label={t("ui.jdbcURLPrefixOptional")}
                  id="driver-prefix"
                  placeholder="jdbc:kingbase8:"
                  maxLength={200}
                  value={form.urlPrefix}
                  onChange={(event) => change("urlPrefix", event.target.value)}
                  hint={t("driverPreset.urlPrefixHint")}
                />
              </div>
              <div className="sm:col-span-2">
                <Field
                  label={t("ui.driverClassOptional")}
                  id="driver-class"
                  maxLength={300}
                  value={form.driverClass}
                  onChange={(event) =>
                    change("driverClass", event.target.value)
                  }
                  hint={t("driverPreset.driverClassHint")}
                />
              </div>
            </div>
            {candidates.length ? (
              <div className="mt-4">
                <label htmlFor="candidate-class" className="text-sm">
                  {t("ui.classesReturnedByTheServer")}</label>
                <Select
                  id="candidate-class"
                  className=" mt-2"
                  value={form.driverClass}
                  onValueChange={(value) =>
                    change("driverClass", value)
                  }
                >
                  <option value="">{t("ui.chooseADriverClass")}</option>
                  {candidates.map((value) => (
                    <option key={value}>{value}</option>
                  ))}
                </Select>
              </div>
            ) : null}
          </Card>
          <DataSourceFields value={dataSources} onChange={setDataSources} />
        </div>
        <Card as="aside" className="upload-summary">
          <p className="eyebrow">{t("ui.readyToPackage")}</p>
          <p className="mt-3 text-xl font-semibold">
            {files.length} {t("ui.artifactsInline")}</p>
          <p className="mt-1 text-sm text-muted-foreground">
            {formatBytes(files.reduce((total, file) => total + file.size, 0))}{" "}
            {t("ui.totalInline")}</p>
          {!files.length ? (
            <p className="mt-3 text-xs text-muted-foreground" role="status">{t("driverUpload.selectFirst")}</p>
          ) : null}
          <FormError message={error} />
          {uploadedId ? <Button asChild variant="outline">
            <Link to={`/drivers/${encodeURIComponent(uploadedId)}`}>{t("ui.openUploadedDriver")}</Link>
          </Button> : null}
          <Button className="mt-4 w-full" disabled={upload.isPending || !!uploadedId}>
            {upload.isPending ? t("ui.uploadingPackage") : t("driverUpload.confirm")}
          </Button>
        </Card>
      </form>
    </div>
  );
}
