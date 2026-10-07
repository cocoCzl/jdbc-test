import { JdbcUrl } from "@/components/jdbc-url";
import { useState } from "react";
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { Check, ChevronRight, ShieldAlert } from "lucide-react";
import { toast } from "sonner";
import { connectionsApi, connectionsKeys } from "@/api/connections";
import { driversApi, driversKeys } from "@/api/drivers";
import { catalogApi, catalogKeys } from "@/api/catalog";
import { runsApi, runsKeys } from "@/api/runs";
import { errorMessage } from "@/api/client";
import type { Connection, CreateRun, DriverSummary } from "@/types/api";
import { DriverSelect } from "@/features/drivers/driver-select";
import { Button } from "@/components/ui/button";
import { Pagination } from "@/components/data-table";
import {
  EmptyState,
  ErrorState,
  Field,
  FormError,
  Loading,
  PageHeader,
  StatusBadge,
} from "@/components/common";
import { connectionPresentation } from "@/lib/status";
import { formatDateTime, plural, shortId } from "@/lib/format";
import { cn } from "@/lib/utils";
export default function NewRun() {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [search] = useSearchParams();
  const presetId = search.get("connectionId");
  const [step, setStep] = useState(0);
  const [page, setPage] = useState(0);
  const [connection, setConnection] = useState<Connection | null>(null);
  const [driver, setDriver] = useState<DriverSummary | null>(null);
  const [profile, setProfile] = useState<CreateRun["profile"]>("CORE");
  const [modules, setModules] = useState<string[]>([]);
  const [consent, setConsent] = useState(false);
  const [differentPassword, setDifferentPassword] = useState(false);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const params = { page, size: 12, sort: "name,asc" };
  const connections = useQuery({
    queryKey: connectionsKeys.list(params),
    queryFn: ({ signal }) => connectionsApi.list(params, signal),
    placeholderData: keepPreviousData,
  });
  const preset = useQuery({
    queryKey: connectionsKeys.detail(presetId ?? ""),
    queryFn: ({ signal }) => connectionsApi.get(presetId!, signal),
    enabled: !!presetId,
  });
  const catalog = useQuery({
    queryKey: catalogKeys.all,
    queryFn: ({ signal }) => catalogApi.get(signal),
    staleTime: 300_000,
  });
  const chosen = connection ?? preset.data ?? null;
  const chosenDriver = driver ?? chosen?.defaultDriver ?? null;
  const driverDetail = useQuery({
    queryKey: driversKeys.detail(chosenDriver?.id ?? ""),
    queryFn: ({ signal }) => driversApi.get(chosenDriver!.id, signal),
    enabled: !!chosenDriver,
  });
  const needsPassword =
    !!chosen && (!chosen.hasSavedPassword || differentPassword);
  const create = useMutation({
    mutationFn: () =>
      runsApi.create({
        connectionId: chosen!.id,
        driverId: chosenDriver!.id,
        profile,
        modules: profile === "CUSTOM" ? modules : [],
        destructiveConsent: consent,
        ...(needsPassword ? { password } : {}),
      }),
    onSuccess: (run) => {
      toast.success("Run queued");
      void client.invalidateQueries({ queryKey: runsKeys.all });
      client.setQueryData(runsKeys.detail(run.id), run);
      navigate(`/runs/${run.id}`);
    },
    onError: (failure) => setError(errorMessage(failure)),
    onSettled: () => setPassword(""),
  });
  if (
    catalog.isPending ||
    connections.isPending ||
    (presetId && preset.isPending)
  )
    return <Loading />;
  if (catalog.isError || connections.isError || preset.isError)
    return (
      <ErrorState
        error={catalog.error ?? connections.error ?? preset.error}
        retry={() => {
          void catalog.refetch();
          void connections.refetch();
          if (presetId) void preset.refetch();
        }}
      />
    );
  const selectionCount =
    profile === "CUSTOM"
      ? catalog.data.modules
          .filter((module) => modules.includes(module.id))
          .reduce((total, module) => total + module.scenarioCount, 0)
      : catalog.data.profiles.find((item) => item.id === profile)
          ?.scenarioCount;
  const continueAllowed =
    step === 0
      ? !!chosen
      : step === 1
        ? chosenDriver?.status === "VALIDATED" && (!needsPassword || !!password)
        : step === 2
          ? profile !== "CUSTOM" || modules.length > 0
          : false;
  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader
        title="New test run"
        description="Choose a connection and driver, select tests, then review before starting."
      />
      <div className="mb-4 grid grid-cols-2 gap-2 md:grid-cols-4">
        {["Connection", "Driver", "Test selection", "Review & confirm"].map(
          (label, index) => (
            <button
              key={label}
              disabled={index > step}
              onClick={() => setStep(index)}
              className={cn(
                "flex items-center gap-3 rounded-xl border p-3 text-left text-sm",
                index === step
                  ? "border-primary bg-accent text-primary"
                  : "border-border bg-white text-muted-foreground",
              )}
            >
              <span
                className={cn(
                  "flex size-7 items-center justify-center rounded-full text-xs font-semibold",
                  index === step ? "bg-primary text-white" : "bg-secondary",
                )}
              >
                {index < step ? <Check className="size-4" /> : index + 1}
              </span>
              {label}
            </button>
          ),
        )}
      </div>
      <section className="panel">
        <p className="eyebrow mb-2">Step {step + 1} of 4</p>
        {step === 0 ? (
          <>
            <h2 className="mb-3 text-xl font-semibold">Choose a connection</h2>
            {connections.data.items.length ? (
              <div className="grid gap-4 md:grid-cols-2">
                {connections.data.items.map((item) => (
                  <div key={item.id} className="step-card min-w-0 cursor-pointer"
                    onClick={(event) => {
                      if ((event.target as HTMLElement).closest("button, input, label")) return;
                      setConnection(item); setDriver(null); setPassword(""); setDifferentPassword(false);
                    }}>
                    <label className="mb-2 flex cursor-pointer items-center gap-2 text-sm font-medium text-primary">
                      <input type="radio" name="run-connection" aria-label={item.name}
                        className="size-4 accent-primary" checked={chosen?.id === item.id}
                        onChange={() => { setConnection(item); setDriver(null); setPassword(""); setDifferentPassword(false); }} />
                      {chosen?.id === item.id ? "Selected" : "Select connection"}
                    </label>
                    <div className="flex items-center justify-between">
                      <h3 className="min-w-0 break-words font-semibold">{item.name}</h3>
                      {chosen?.id === item.id ? (
                        <Check className="size-4 text-primary" />
                      ) : null}
                    </div>
                    <p className="my-2 text-xs text-muted-foreground">
                      Database: {item.databaseType} · Adapter: {item.adapterId}
                    </p>
                    <div className="mb-3" onClick={(event) => event.stopPropagation()}>
                      <JdbcUrl value={item.jdbcUrl} />
                    </div>
                    {item.lastConnectionTestStatus ? (
                      <StatusBadge
                        {...connectionPresentation[
                          item.lastConnectionTestStatus
                        ]}
                      />
                    ) : (
                      <span className="text-xs text-muted-foreground">
                        Not tested
                      </span>
                    )}
                    <p className="mt-2 text-xs text-muted-foreground">Last test: {formatDateTime(item.lastConnectionTestAt)}</p>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                title="No connections yet"
                description="Add a target database connection first."
                action={
                  <Button asChild>
                    <Link to="/connections/new">Add connection</Link>
                  </Button>
                }
              />
            )}
            <Pagination {...connections.data} onPage={setPage} />
            {chosen &&
            !connections.data.items.some((item) => item.id === chosen.id) ? (
              <p className="mt-4 text-sm">Selected connection: {chosen.name}</p>
            ) : null}
          </>
        ) : null}
        {step === 1 ? (
          <div className="max-w-xl space-y-4">
            <h2 className="text-xl font-semibold">Choose a validated driver</h2>
            <DriverSelect
              presentation="cards"
              value={chosenDriver?.id ?? ""}
              selected={chosenDriver}
              onChange={setDriver}
            />
            {chosen?.hasSavedPassword ? (
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  className="size-4 accent-primary"
                  checked={differentPassword}
                  onChange={(event) => {
                    setDifferentPassword(event.target.checked);
                    setPassword("");
                  }}
                />
                Use a different password for this run
              </label>
            ) : null}
            {needsPassword ? (
              <Field
                id="run-password"
                label="Password for this run"
                type="password"
                autoComplete="new-password"
                maxLength={2000}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                hint="This password is used only for this run and will not be saved to the connection."
              />
            ) : (
              <p className="text-sm text-muted-foreground">
                The saved connection password will be used.
              </p>
            )}
          </div>
        ) : null}
        {step === 2 ? (
          <>
            <h2 className="mb-3 text-xl font-semibold">
              Define your test selection
            </h2>
            <div className="mb-6 grid gap-4 sm:grid-cols-3">
              {[
                ...catalog.data.profiles.filter(
                  (item) => item.id === "CORE" || item.id === "FULL",
                ),
                { id: "CUSTOM", name: "Custom", scenarioCount: undefined },
              ].map((item) => (
                <button
                  key={item.id}
                  aria-pressed={profile === item.id}
                  className={cn(
                    "step-card",
                    profile === item.id && "border-primary bg-accent",
                  )}
                  onClick={() => setProfile(item.id as CreateRun["profile"])}
                >
                  <h3 className="font-semibold">{item.name}</h3>
                  <p className="mt-2 text-sm text-muted-foreground">
                    {item.scenarioCount != null
                      ? plural(item.scenarioCount, "scenario")
                      : "Choose individual modules"}
                  </p>
                </button>
              ))}
            </div>
            {profile === "CUSTOM" ? (
              <>
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="font-medium">
                    Modules{" "}
                    <span className="text-muted-foreground">
                      ({catalog.data.modules.length})
                    </span>
                  </h3>
                  <div className="flex gap-2">
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() =>
                        setModules(catalog.data.modules.map((item) => item.id))
                      }
                    >
                      Select all
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setModules([])}
                    >
                      Clear all
                    </Button>
                  </div>
                </div>
                <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                  {catalog.data.modules.map((item) => (
                    <label
                      key={item.id}
                      className={cn(
                        "step-card flex cursor-pointer items-start gap-3",
                        modules.includes(item.id) &&
                          "border-primary bg-accent",
                      )}
                    >
                      <input
                        type="checkbox"
                        aria-label={`${item.name} ${plural(item.scenarioCount, "scenario")}`}
                        className="mt-1 size-4 accent-primary"
                        checked={modules.includes(item.id)}
                        onChange={(event) =>
                          setModules((previous) =>
                            event.target.checked
                              ? [...previous, item.id]
                              : previous.filter((value) => value !== item.id),
                          )
                        }
                      />
                      <span>
                        <span className="block font-medium">{item.name}</span>
                        <span className="mt-1 block text-xs text-muted-foreground">
                          {plural(item.scenarioCount, "scenario")}
                        </span>
                      </span>
                    </label>
                  ))}
                </div>
              </>
            ) : (
              <p className="text-sm text-muted-foreground">
                The {profile.toLowerCase()} selection uses the tests defined in the current catalog.
              </p>
            )}
            <p className="mt-5 text-sm font-semibold" aria-live="polite">
              {selectionCount == null ? "— scenarios" : plural(selectionCount, "scenario")} selected
            </p>
          </>
        ) : null}
        {step === 3 ? (
          <>
            <h2 className="mb-3 text-xl font-semibold">
              Review before you run
            </h2>
            <div className="grid gap-6 md:grid-cols-3">
              <div>
                <p className="eyebrow">Target connection</p>
                <p className="mt-3 break-words font-semibold">{chosen?.name}</p>
                <p className="mt-2 text-sm text-muted-foreground">
                  Database: {chosen?.databaseType} · Adapter: {chosen?.adapterId}
                </p>
                {chosen ? <div className="mt-2"><JdbcUrl value={chosen.jdbcUrl} /></div> : null}
                <p className="mt-2 text-sm">Authorized test scope: {chosen?.executionDatabase || "—"} / {chosen?.executionSchema || "—"}</p>
              </div>
              <div>
                <p className="eyebrow">Driver package</p>
                <p className="mt-3 break-words font-semibold">{chosenDriver?.name}</p>
                <p className="mt-2 text-sm text-muted-foreground">
                  {chosenDriver?.vendor} · {chosenDriver?.version}
                </p>
                <p className="mt-2 font-mono text-xs">
                  Fingerprint{" "}
                  {shortId(driverDetail.data?.packageFingerprint ?? null)}
                </p>
              </div>
              <div>
                <p className="eyebrow">Selection</p>
                <p className="mt-3 break-words font-semibold">
                  {profile} · {selectionCount ?? "—"} scenarios
                </p>
                <p className="mt-2 text-sm text-muted-foreground">
                  {profile === "CUSTOM"
                    ? catalog.data.modules
                        .filter((item) => modules.includes(item.id))
                        .map((item) => item.name)
                        .join(", ")
                    : "All modules included by this profile"}
                </p>
              </div>
            </div>
            <div className="mt-4 rounded-xl border border-warning/30 bg-warning/5 p-4">
              <h3 className="flex items-center gap-2 font-semibold text-warning">
                <ShieldAlert className="size-5" />
                Destructive test warning
              </h3>
              <p className="mt-3 text-sm leading-relaxed">
                Use a dedicated test database or namespace. These tests execute
                JDBC operations that may modify database objects.
              </p>
              <label className="mt-4 flex cursor-pointer items-start gap-3 text-sm">
                <input
                  type="checkbox"
                  className="mt-1 size-4 shrink-0 accent-primary"
                  checked={consent}
                  onChange={(event) => setConsent(event.target.checked)}
                />
                I understand that these JDBC tests may execute DDL/DML and
                modify objects in the configured test namespace.
              </label>
            </div>
            {needsPassword && !password ? (
              <p role="alert" className="mt-4 text-sm text-destructive">
                Re-enter the temporary password in the Driver step before
                retrying.
              </p>
            ) : null}
          </>
        ) : null}
      </section>
      {error ? <div className="mt-3"><FormError message={error} /></div> : null}
      <div className="mt-4 flex items-center justify-between gap-4">
        <Button
          variant="outline"
          disabled={step === 0 || create.isPending}
          onClick={() => setStep((previous) => previous - 1)}
        >
          Back
        </Button>
        {step < 3 ? (
          <div className="flex flex-wrap items-center justify-end gap-3">
          {!continueAllowed ? <p id="continue-reason" className="text-sm text-muted-foreground">
            {step === 0 ? "Select a connection to continue." : step === 1 ? chosenDriver?.status !== "VALIDATED" ? "Select a validated driver to continue." : "Enter the password for this run to continue." : "Select at least one module to continue."}
          </p> : null}
          <Button aria-describedby={!continueAllowed ? "continue-reason" : undefined}
            disabled={!continueAllowed}
            onClick={() => setStep((previous) => previous + 1)}
          >
            {step === 0 ? "Next: Choose driver" : step === 1 ? "Next: Select tests" : "Next: Review run"}
            <ChevronRight />
          </Button>
          </div>
        ) : (
          <Button
            disabled={
              create.isPending ||
              !consent ||
              chosenDriver?.status !== "VALIDATED" ||
              (needsPassword && !password)
            }
            onClick={() => {
              setError(null);
              create.mutate();
            }}
          >
            {create.isPending ? "Creating run…" : "Start test run"}
          </Button>
        )}
      </div>
    </div>
  );
}
