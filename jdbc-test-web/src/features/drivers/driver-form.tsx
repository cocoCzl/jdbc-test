import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { FileArchive, UploadCloud, X } from "lucide-react";
import { toast } from "sonner";
import { driversApi, driversKeys } from "@/api/drivers";
import { ApiError, errorMessage } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Field, FormError, PageHeader } from "@/components/common";
import { formatBytes } from "@/lib/format";
import { validateFiles } from "@/lib/driver-upload";
export default function DriverForm() {
  const navigate = useNavigate();
  const client = useQueryClient();
  const uploadInput = useRef<HTMLInputElement>(null);
  const [form, setForm] = useState({
    name: "",
    vendor: "",
    version: "",
    driverClass: "",
    urlPrefix: "",
  });
  const [files, setFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [candidates, setCandidates] = useState<string[]>([]);
  const upload = useMutation({
    mutationFn: () =>
      driversApi.upload(
        {
          ...form,
          driverClass: form.driverClass || undefined,
          urlPrefix: form.urlPrefix || undefined,
        },
        files,
      ),
    onSuccess: (driver) => {
      toast.success("Driver package uploaded");
      void client.invalidateQueries({ queryKey: driversKeys.all });
      navigate(`/drivers/${driver.id}`);
    },
    onError: (failure) => {
      setError(errorMessage(failure));
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
  const change = (key: keyof typeof form, value: string) =>
    setForm((previous) => ({ ...previous, [key]: value }));
  return (
    <>
      <PageHeader
        title="Add a driver"
        description="A versioned, immutable package of JDBC artifacts. No driver code executes in the server."
      />
      <form
        className="grid max-w-5xl gap-6 xl:grid-cols-[1fr_320px]"
        onSubmit={(event) => {
          event.preventDefault();
          const failure = validateFiles(files);
          setError(failure);
          if (!failure) upload.mutate();
        }}
      >
        <div className="space-y-6">
          <section className="panel">
            <h2 className="mb-5 text-lg font-semibold">Package identity</h2>
            <div className="grid gap-5 sm:grid-cols-2">
              <Field
                label="Name"
                id="driver-name"
                required
                maxLength={200}
                value={form.name}
                onChange={(event) => change("name", event.target.value)}
                placeholder="PostgreSQL JDBC"
              />
              <Field
                label="Vendor"
                id="driver-vendor"
                required
                maxLength={120}
                value={form.vendor}
                onChange={(event) => change("vendor", event.target.value)}
                placeholder="PostgreSQL"
              />
              <Field
                label="Version"
                id="driver-version"
                required
                maxLength={80}
                value={form.version}
                onChange={(event) => change("version", event.target.value)}
                placeholder="Your driver version"
              />
              <Field
                label="JDBC URL prefix (optional)"
                id="driver-prefix"
                maxLength={200}
                value={form.urlPrefix}
                onChange={(event) => change("urlPrefix", event.target.value)}
                placeholder="jdbc:postgresql:"
              />
              <div className="sm:col-span-2">
                <Field
                  label="Driver class (optional)"
                  id="driver-class"
                  maxLength={300}
                  value={form.driverClass}
                  onChange={(event) =>
                    change("driverClass", event.target.value)
                  }
                  hint="Leave empty to discover the class from the JAR service metadata."
                  placeholder="org.postgresql.Driver"
                />
              </div>
            </div>
            {candidates.length ? (
              <div className="mt-4">
                <label htmlFor="candidate-class" className="text-sm">
                  Classes returned by the server
                </label>
                <select
                  id="candidate-class"
                  className="select mt-2"
                  value={form.driverClass}
                  onChange={(event) =>
                    change("driverClass", event.target.value)
                  }
                >
                  <option value="">Choose a driver class</option>
                  {candidates.map((value) => (
                    <option key={value}>{value}</option>
                  ))}
                </select>
              </div>
            ) : null}
          </section>
          <section className="panel">
            <h2 className="mb-5 text-lg font-semibold">Driver artifacts</h2>
            <input
              ref={uploadInput}
              type="file"
              multiple
              accept=".jar"
              aria-label="JAR files"
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
                Click to upload, or drop JAR files
              </span>
              <span className="mt-2 text-xs text-muted-foreground">
                Up to 20 files · 100 MB per file · 250 MB per package
              </span>
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
                    aria-label={`Remove ${file.name}`}
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
          </section>
        </div>
        <aside className="panel h-fit">
          <p className="eyebrow">Ready to package</p>
          <p className="mt-5 text-2xl font-semibold">
            {files.length} artifacts
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            {formatBytes(files.reduce((total, file) => total + file.size, 0))}{" "}
            total
          </p>
          <p className="my-6 text-sm leading-relaxed text-muted-foreground">
            Artifacts cannot be replaced after upload. To test a different
            binary, create a new package.
          </p>
          <FormError message={error} />
          <Button className="mt-4 w-full" disabled={upload.isPending}>
            {upload.isPending ? "Uploading package…" : "Upload driver package"}
          </Button>
        </aside>
      </form>
    </>
  );
}
