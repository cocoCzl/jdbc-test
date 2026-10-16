import { Card } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { FileArchive, UploadCloud, X } from "lucide-react";
import { toast } from "sonner";
import { driversApi, driversKeys } from "@/api/drivers";
import { ApiError } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Field, FormError, PageHeader } from "@/components/common";
import { formatBytes } from "@/lib/format";
import { validateFiles } from "@/lib/driver-upload";
import { DataSourceFields } from "./data-source-fields";
import type { DataSourceProviders, DriverPreset } from "@/types/api";

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
  });
  const [dataSources, setDataSources] = useState<DataSourceProviders>({});
  const [files, setFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | Error | null>(null);
  const [candidates, setCandidates] = useState<string[]>([]);
  const upload = useMutation({
    mutationFn: () =>
      driversApi.upload(
        {
          ...form,
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
    <>
      <PageHeader
        title={t("ui.addADriver")}
        description={t("ui.aVersionedImmutablePackageOfJDBCArtifacts")}
      />
      <form
        className="grid grid-cols-1 max-w-5xl gap-6 xl:grid-cols-[minmax(0,1fr)_320px]"
        onSubmit={(event) => {
          event.preventDefault();
          const failure = validateFiles(files);
          setError(failure);
          if (!failure) upload.mutate();
        }}
      >
        <div className="space-y-6">
          <Card as="section" className="panel">
            <h2 className="mb-5 text-lg font-semibold">{t("ui.packageIdentity")}</h2>
            <div className="mb-5 space-y-3">
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
                  onClick={() => applyPreset(selectedPreset, true)}>
                  {t("driverPreset.restore")}
                </Button>
              ) : null}
              {presets.isError ? (
                <p role="status" className="text-sm text-muted-foreground">
                  {t("driverPreset.loadFailed")}
                </p>
              ) : null}
            </div>
            <div className="grid gap-5 sm:grid-cols-2">
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
                label={t("ui.version")}
                id="driver-version"
                required
                maxLength={80}
                value={form.version}
                onChange={(event) => change("version", event.target.value)}
                placeholder={t("ui.yourDriverVersion")}
              />
              <Field
                label={t("ui.jdbcURLPrefixOptional")}
                id="driver-prefix"
                maxLength={200}
                value={form.urlPrefix}
                onChange={(event) => change("urlPrefix", event.target.value)}
                hint={t("driverPreset.urlPrefixHint")}
              />
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
          <Card as="section" className="panel">
            <h2 className="mb-5 text-lg font-semibold">{t("ui.driverArtifacts")}</h2>
            <p className="mb-4 text-sm text-muted-foreground">{t("ui.driverCompanionHint")}</p>
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
            <button
              type="button"
              disabled={upload.isPending}
              onClick={() => uploadInput.current?.click()}
              onDragOver={(event) => event.preventDefault()}
              onDrop={(event) => {
                event.preventDefault();
                if (!upload.isPending)
                  setFiles((previous) => [
                    ...previous,
                    ...Array.from(event.dataTransfer.files),
                  ]);
              }}
              className="flex w-full flex-col items-center rounded-2xl border-2 border-dashed border-input bg-background px-5 py-9 text-center hover:border-primary"
            >
              <UploadCloud className="mb-3 size-7 text-primary" />
              <span className="text-sm font-medium">
                {t("ui.clickToUploadOrDropJARFiles")}</span>
              <span className="mt-2 text-xs text-muted-foreground">
                {t("ui.upTo20Files100MBPer")}</span>
            </button>
            <ul className="mt-4 divide-y divide-border">
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
        </div>
        <Card as="aside" className="panel h-fit">
          <p className="eyebrow">{t("ui.readyToPackage")}</p>
          <p className="mt-5 text-2xl font-semibold">
            {files.length} {t("ui.artifactsInline")}</p>
          <p className="mt-1 text-sm text-muted-foreground">
            {formatBytes(files.reduce((total, file) => total + file.size, 0))}{" "}
            {t("ui.totalInline")}</p>
          <p className="my-6 text-sm leading-relaxed text-muted-foreground">
            {t("ui.artifactsCannotBeReplacedAfterUploadTo")}</p>
          <FormError message={error} />
          <Button className="mt-4 w-full" disabled={upload.isPending}>
            {upload.isPending ? t("ui.uploadingPackage") : t("ui.uploadDriverPackage")}
          </Button>
        </Card>
      </form>
    </>
  );
}
