import { Card } from "@/components/ui/card";
import { moduleName, profileName } from "@/lib/presentation";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { JdbcUrl } from "@/components/jdbc-url";
import { useState } from "react";
import {
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
  PendingPage,
  StatusBadge,
} from "@/components/common";
import { connectionPresentation } from "@/lib/status";
import { formatDateTime, plural, shortId } from "@/lib/format";
import { cn } from "@/lib/utils";
export default function NewRun() {
  useTranslation();
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
  const [error, setError] = useState<string | Error | null>(null);
  const params = { page, size: 12, sort: "name,asc" };
  const connections = useQuery({
    queryKey: connectionsKeys.list(params),
    queryFn: ({ signal }) => connectionsApi.list(params, signal),
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
      toast.success(t("ui.runQueued"));
      void client.invalidateQueries({ queryKey: runsKeys.all });
      client.setQueryData(runsKeys.detail(run.id), run);
      navigate(`/runs/${run.id}`);
    },
    onError: (failure) => setError(failure instanceof Error ? failure : new Error()),
    onSettled: () => setPassword(""),
  });
  if (
    connections.isPending ||
    (presetId && preset.isPending)
  )
    return <PendingPage title={t("ui.newTestRun")} />;
  if (!connections.data || (presetId && preset.isError && !preset.data))
    return (
      <PendingPage title={t("ui.newTestRun")}
        error={connections.error ?? preset.error}
        retry={() => {
          void catalog.refetch();
          void connections.refetch();
          if (presetId) void preset.refetch();
        }}
      />
    );
  const selectionCount =
    profile === "CUSTOM"
      ? catalog.data?.modules
          .filter((module) => modules.includes(module.id))
          .reduce((total, module) => total + module.scenarioCount, 0)
      : catalog.data?.profiles.find((item) => item.id === profile)
          ?.scenarioCount;
  const continueAllowed =
    step === 0
      ? !!chosen
      : step === 1
        ? chosenDriver?.status === "VALIDATED" && (!needsPassword || !!password)
        : step === 2
          ? !!catalog.data && (profile !== "CUSTOM" || modules.length > 0)
          : false;
  return (
    <div className="mx-auto max-w-5xl">
      {connections.isError && connections.data ? <ErrorState error={connections.error} retry={() => void connections.refetch()} /> : null}
      {catalog.isError && catalog.data ? <ErrorState error={catalog.error} retry={() => void catalog.refetch()} /> : null}
      <PageHeader
        title={t("ui.newTestRun")}
        description={t("ui.chooseAConnectionAndDriverSelectTests")}
      />
      <div className="mb-4 grid grid-cols-2 gap-2 md:grid-cols-4">
        {[t("ui.connection"), t("ui.driver"), t("ui.testSelection"), t("ui.reviewConfirm")].map(
          (label, index) => (
            <button
              key={index}
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
      <Card as="section" className="panel">
        <p className="eyebrow mb-2">{t("wizard.step", {step: step + 1})}</p>
        {step === 0 ? (
          <>
            <h2 className="mb-3 text-xl font-semibold">{t("ui.chooseAConnection")}</h2>
            {connections.data.items.length ? (
              <div className="grid gap-4 md:grid-cols-2">
                {connections.data.items.map((item) => (
                  <div key={item.id} data-selected={chosen?.id === item.id} className="step-card min-w-0 cursor-pointer"
                    onClick={(event) => {
                      if ((event.target as HTMLElement).closest("button, input, label")) return;
                      setConnection(item); setDriver(null); setPassword(""); setDifferentPassword(false);
                    }}>
                    <label className="mb-2 flex cursor-pointer items-center gap-2 text-sm font-medium text-primary">
                      <input type="radio" name="run-connection" aria-label={item.name}
                        className="size-4 accent-primary" checked={chosen?.id === item.id}
                        onChange={() => { setConnection(item); setDriver(null); setPassword(""); setDifferentPassword(false); }} />
                      {chosen?.id === item.id ? t("ui.selected") : t("ui.selectConnection")}
                    </label>
                    <div className="flex items-center justify-between">
                      <h3 className="min-w-0 break-words font-semibold">{item.name}</h3>
                      {chosen?.id === item.id ? (
                        <Check className="size-4 text-primary" />
                      ) : null}
                    </div>
                    <p className="my-2 text-xs text-muted-foreground">
                      {t("ui.databaseLabel")}{item.databaseType}  {t("ui.adapterInline")} {item.adapterId}
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
                        {t("ui.notTested")}</span>
                    )}
                    <p className="mt-2 text-xs text-muted-foreground">{t("ui.lastTestLabel")} {formatDateTime(item.lastConnectionTestAt)}</p>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                title={t("ui.noConnectionsYet")}
                description={t("ui.addATargetDatabaseConnectionFirst")}
                action={
                  <Button asChild>
                    <Link to="/connections/new">{t("ui.addConnection")}</Link>
                  </Button>
                }
              />
            )}
            <Pagination {...connections.data} onPage={setPage} />
            {chosen &&
            !connections.data.items.some((item) => item.id === chosen.id) ? (
              <p className="mt-4 text-sm">{t("ui.selectedConnection")} {chosen.name}</p>
            ) : null}
          </>
        ) : null}
        {step === 1 ? (
          <div className="max-w-xl space-y-4">
            <h2 className="text-xl font-semibold">{t("ui.chooseAValidatedDriver")}</h2>
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
                {t("ui.useADifferentPasswordForThisRun")}</label>
            ) : null}
            {needsPassword ? (
              <Field
                id="run-password"
                label={t("ui.passwordForThisRun")}
                type="password"
                autoComplete="new-password"
                maxLength={2000}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                hint={t("ui.thisPasswordIsUsedOnlyForThis")}
              />
            ) : (
              <p className="text-sm text-muted-foreground">
                {t("ui.theSavedConnectionPasswordWillBeUsed")}</p>
            )}
          </div>
        ) : null}
        {step === 2 && !catalog.data ? (catalog.isError ? <ErrorState error={catalog.error} retry={() => void catalog.refetch()} /> : <Loading />) : null}
        {step === 2 && catalog.data ? (
          <>
            <h2 className="mb-3 text-xl font-semibold">
              {t("ui.defineYourTestSelection")}</h2>
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
                  <h3 className="font-semibold">{profileName(item.id, item.name)}</h3>
                  <p className="mt-2 text-sm text-muted-foreground">
                    {item.scenarioCount != null
                      ? plural(item.scenarioCount, "scenario")
                      : t("ui.chooseIndividualModules")}
                  </p>
                </button>
              ))}
            </div>
            {profile === "CUSTOM" ? (
              <>
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="font-medium">
                    {t("ui.modules")}{" "}
                    <span className="text-muted-foreground">
                      ({catalog.data?.modules.length})
                    </span>
                  </h3>
                  <div className="flex gap-2">
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() =>
                        setModules(catalog.data!.modules.map((item) => item.id))
                      }
                    >
                      {t("ui.selectAll")}</Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setModules([])}
                    >
                      {t("ui.clearAll")}</Button>
                  </div>
                </div>
                <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                  {catalog.data?.modules.map((item) => (
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
                        aria-label={`${moduleName(item.id, item.name)} ${plural(item.scenarioCount, "scenario")}`}
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
                        <span className="block font-medium">{moduleName(item.id, item.name)}</span>
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
                {t("wizard.selection", {profile: profileName(profile)})}</p>
            )}
            <p className="mt-5 text-sm font-semibold" aria-live="polite">
              {t("wizard.selectedCount", {countText: selectionCount == null ? t("ui.scenariosInline") : plural(selectionCount, "scenario")})}</p>
          </>
        ) : null}
        {step === 3 ? (
          <>
            <h2 className="mb-3 text-xl font-semibold">
              {t("ui.reviewBeforeYouRun")}</h2>
            <div className="grid gap-6 md:grid-cols-3">
              <div>
                <p className="eyebrow">{t("ui.targetConnection")}</p>
                <p className="mt-3 break-words font-semibold">{chosen?.name}</p>
                <p className="mt-2 text-sm text-muted-foreground">
                  {t("ui.databaseLabel")}{chosen?.databaseType}  {t("ui.adapterInline")} {chosen?.adapterId}
                </p>
                {chosen ? <div className="mt-2"><JdbcUrl value={chosen.jdbcUrl} /></div> : null}
                <p className="mt-2 text-sm">{t("ui.authorizedTestScope")} {chosen?.executionDatabase || "—"} / {chosen?.executionSchema || "—"}</p>
              </div>
              <div>
                <p className="eyebrow">{t("ui.driverPackage")}</p>
                <p className="mt-3 break-words font-semibold">{chosenDriver?.name}</p>
                <p className="mt-2 text-sm text-muted-foreground">
                  {chosenDriver?.vendor} · {chosenDriver?.version}
                </p>
                <p className="mt-2 font-mono text-xs">
                  {t("ui.fingerprint")}{" "}
                  {shortId(driverDetail.data?.packageFingerprint ?? null)}
                </p>
              </div>
              <div>
                <p className="eyebrow">{t("ui.selection")}</p>
                <p className="mt-3 break-words font-semibold">
                  {profileName(profile)} · {selectionCount == null ? t("ui.scenariosInline") : plural(selectionCount, "scenario")}</p>
                <p className="mt-2 text-sm text-muted-foreground">
                  {profile === "CUSTOM"
                    ? catalog.data?.modules
                        .filter((item) => modules.includes(item.id))
                        .map((item) => moduleName(item.id, item.name))
                        .join(", ")
                    : t("ui.allModulesIncludedByThisProfile")}
                </p>
              </div>
            </div>
            <div className="mt-4 rounded-xl border border-warning/30 bg-warning/5 p-4">
              <h3 className="flex items-center gap-2 font-semibold text-warning">
                <ShieldAlert className="size-5" />
                {t("ui.destructiveTestWarning")}</h3>
              <p className="mt-3 text-sm leading-relaxed">
                {t("ui.useADedicatedTestDatabaseOrNamespace")}</p>
              <label className="mt-4 flex cursor-pointer items-start gap-3 text-sm">
                <input
                  type="checkbox"
                  className="mt-1 size-4 shrink-0 accent-primary"
                  checked={consent}
                  onChange={(event) => setConsent(event.target.checked)}
                />
                {t("ui.iUnderstandThatTheseJDBCTestsMay")}</label>
            </div>
            {needsPassword && !password ? (
              <p role="alert" className="mt-4 text-sm text-destructive">
                {t("ui.reEnterTheTemporaryPasswordInThe")}</p>
            ) : null}
          </>
        ) : null}
      </Card>
      {error ? <div className="mt-3"><FormError message={error} /></div> : null}
      <div className="mt-4 flex items-center justify-between gap-4">
        <Button
          variant="outline"
          disabled={step === 0 || create.isPending}
          onClick={() => setStep((previous) => previous - 1)}
        >
          {t("ui.back")}</Button>
        {step < 3 ? (
          <div className="flex flex-wrap items-center justify-end gap-3">
          {!continueAllowed ? <p id="continue-reason" className="text-sm text-muted-foreground">
            {step === 0 ? t("ui.selectAConnectionToContinue") : step === 1 ? chosenDriver?.status !== "VALIDATED" ? t("ui.selectAValidatedDriverToContinue") : t("ui.enterThePasswordForThisRunTo") : t("ui.selectAtLeastOneModuleToContinue")}
          </p> : null}
          <Button aria-describedby={!continueAllowed ? "continue-reason" : undefined}
            disabled={!continueAllowed}
            onClick={() => setStep((previous) => previous + 1)}
          >
            {step === 0 ? t("ui.nextChooseDriver") : step === 1 ? t("ui.nextSelectTests") : t("ui.nextReviewRun")}
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
            {create.isPending ? t("ui.creatingRun") : t("ui.startTestRun")}
          </Button>
        )}
      </div>
    </div>
  );
}
