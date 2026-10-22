import { RunPassword, type RunPasswordMode } from "./run-password";
import { TestSelection } from "./test-selection";
import { useTestSelection, missingPrerequisites } from "./test-selection-state";
import { Card } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { adaptersApi, adapterKeys } from "@/api/adapters";
import { ConnectionChoiceList } from "./connection-choice-list";
import { profileName, runSelectionName } from "@/lib/presentation";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { JdbcUrl } from "@/components/jdbc-url";
import { useRef, useState } from "react";
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
import type { Connection, DriverSummary } from "@/types/api";
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
} from "@/components/common";
import { formatDateTime, plural, shortId } from "@/lib/format";
import { cn } from "@/lib/utils";
export default function NewRun() {
  useTranslation();
  const navigate = useNavigate();
  const client = useQueryClient();
  const [search] = useSearchParams();
  const presetId = search.get("connectionId");
  const [step, setStep] = useState(0);
  const [name, setName] = useState<string | null>(null);
  const [nameTime] = useState(() => new Date().toISOString());
  const [page, setPage] = useState(0);
  const [adapterFilter, setAdapterFilter] = useState("");
  const [connection, setConnection] = useState<Connection | null>(null);
  const [driver, setDriver] = useState<DriverSummary | null>(null);
  const [budgetSeconds, setBudgetSeconds] = useState(1800);
  const [consent, setConsent] = useState(false);
  const [credentials, setCredentials] = useState<{
    connectionId: string; mode: RunPasswordMode; password: string;
  } | null>(null);
  const [error, setError] = useState<string | Error | null>(null);
  const submitting = useRef(false);
  const params = { page, size: 12, sort: "name,asc", adapterId: adapterFilter || undefined };
  const adapters = useQuery({
    queryKey: adapterKeys.all,
    queryFn: ({ signal }) => adaptersApi.list(signal),
    staleTime: 300_000,
  });
  const connections = useQuery({
    queryKey: connectionsKeys.list(params),
    queryFn: ({ signal }) => connectionsApi.list(params, signal),
  });
  const preset = useQuery({
    queryKey: connectionsKeys.detail(presetId ?? ""),
    queryFn: ({ signal }) => connectionsApi.get(presetId!, signal),
    enabled: !!presetId,
  });
  const chosen = connection ?? preset.data ?? null;
  const chosenDriver = driver ?? chosen?.defaultDriver ?? null;
  const catalog = useQuery({
    queryKey: catalogKeys.target(chosen?.id ?? "", chosen?.adapterId ?? "", chosenDriver?.id ?? ""),
    queryFn: ({ signal }) => catalogApi.get(signal, chosen?.adapterId),
    enabled: !!chosen && !!chosenDriver,
    staleTime: 300_000,
  });
  const selection = useTestSelection(catalog.data);
  const selectedIds = [...selection.selected];
  const selectionCount = selectedIds.length;
  const excludedIds = catalog.data?.scenarios.filter(item => !selection.selected.has(item.id)).map(item => item.id) ?? [];
  const profile = excludedIds.length ? "CUSTOM" : "FULL";
  const runName = name ?? `${chosen?.name ?? ""} · ${runSelectionName({profile, selectedModules: []})} · ${formatDateTime(nameTime)}`;
  const nameValid = runName.trim().length > 0 && runName.trim().length <= 200;
  const budgetValid = Number.isInteger(budgetSeconds) && budgetSeconds >= 60 && budgetSeconds <= 7200;
  const outsideFilter = !!chosen && !!adapterFilter && chosen.adapterId !== adapterFilter;
  const chosenAdapter = adapters.data?.find((item) => item.id === chosen?.adapterId);
  const driverDetail = useQuery({
    queryKey: driversKeys.detail(chosenDriver?.id ?? ""),
    queryFn: ({ signal }) => driversApi.get(chosenDriver!.id, signal),
    enabled: !!chosenDriver,
  });
  // Scope transient credentials to their connection, including asynchronously loaded presets.
  const currentCredentials = credentials?.connectionId === chosen?.id ? credentials : null;
  const requestedPasswordMode = currentCredentials?.mode;
  const passwordMode = requestedPasswordMode === "saved" && !chosen?.hasSavedPassword
    ? "other" : requestedPasswordMode ?? (chosen?.hasSavedPassword ? "saved" : "other");
  const password = currentCredentials?.password ?? "";
  const passwordReady = passwordMode === "saved" ? !!chosen?.hasSavedPassword
    : passwordMode === "empty" || password.length > 0;
  const create = useMutation({
    mutationFn: () =>
      runsApi.create({
        name: runName.trim(),
        connectionId: chosen!.id,
        driverId: chosenDriver!.id,
        profile,
        modules: [],
        includeScenarios: selectedIds,
        excludeScenarios: excludedIds,
        catalogVersion: catalog.data?.catalogVersion,
        expectedAdapterFingerprint: chosenAdapter?.fingerprint ?? undefined,
        budgetSeconds,
        destructiveConsent: consent,
        ...(passwordMode === "empty" ? { password: "" } : passwordMode === "other" ? { password } : {}),
      }),
    onSuccess: (run) => {
      toast.success(t("ui.runQueued"));
      void client.invalidateQueries({ queryKey: runsKeys.all });
      client.setQueryData(runsKeys.detail(run.id), run);
      navigate(`/runs/${run.id}`);
    },
    onError: (failure) => {
      submitting.current = false;
      setError(failure instanceof Error ? failure : new Error());
    },
    onSettled: () => { setCredentials(previous => previous ? { ...previous, password: "" } : null); },
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
  const unmet = catalog.data?.scenarios.filter(item => selection.selected.has(item.id) && missingPrerequisites(item, driverDetail.data?.dataSources).length) ?? [];
  const continueAllowed =
    step === 0
      ? !!chosen && !!chosenAdapter && !outsideFilter
      : step === 1
        ? chosenDriver?.status === "VALIDATED" && passwordReady
        : step === 2
          ? !!catalog.data && selectionCount > 0 && budgetValid
          : false;
  return (
    <div className="workspace-page">
      {connections.isError && connections.data ? <ErrorState error={connections.error} retry={() => void connections.refetch()} /> : null}
      {catalog.isError && catalog.data ? <ErrorState error={catalog.error} retry={() => void catalog.refetch()} /> : null}
      <PageHeader
        title={t("ui.newTestRun")}
        description={t("ui.chooseAConnectionAndDriverSelectTests")}
      />
      <div className="wizard-steps" role="group" aria-label={t("wizard.steps")}>
        {[t("ui.connection"), t("ui.driver"), t("ui.testSelection"), t("ui.reviewConfirm")].map(
          (label, index) => (
            <button
              key={index}
              disabled={index > step}
              aria-current={index === step ? "step" : undefined}
              onClick={() => setStep(index)}
              className={cn(
                "wizard-step",
                index === step
                  ? "wizard-step-current"
                  : "border-border bg-card text-muted-foreground",
              )}
            >
              <span
                className={cn(
                  "flex size-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold",
                  index === step ? "bg-primary text-primary-foreground" : "bg-secondary",
                )}
              >
                {index < step ? <Check className="size-4" /> : index + 1}
              </span>
              {label}
            </button>
          ),
        )}
      </div>
      <Card as="section" className="wizard-panel">
        <p className="eyebrow mb-2">{t("wizard.step", {step: step + 1})}</p>
        {step === 0 ? (
          <>
            <div className="run-connection-toolbar">
              <div className="flex min-w-0 flex-wrap items-baseline gap-2">
                <h2 className="text-lg font-semibold">{t("ui.chooseAConnection")}</h2>
                <span className="text-xs text-muted-foreground">{plural(connections.data.totalElements, "record")}</span>
              </div>
              <div className="run-connection-filter">
              <Field id="run-adapter-filter" label={t("adapter.filter")}>
                <Select id="run-adapter-filter" value={adapterFilter}
                  disabled={!adapters.data}
                  onValueChange={(value) => { setAdapterFilter(value); setPage(0); }}>
                  <option value="">{t("adapter.all")}</option>
                  {adapters.data?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                </Select>
              </Field>
              </div>
            </div>
            {adapters.isError ? <ErrorState error={adapters.error} retry={() => void adapters.refetch()} /> : null}
            {outsideFilter ? (
              <div id="selected-outside-filter" className="mb-4 rounded-xl border border-warning/30 p-4 text-sm" role="status">
                <p>{t("adapter.selectedOutsideFilter", { name: chosen.name })}</p>
                {connections.data.items.length ? <Button variant="ghost" onClick={() => { setAdapterFilter(""); setPage(0); }}>{t("adapter.clearFilter")}</Button> : null}
              </div>
            ) : null}
            {connections.data.items.length ? (
              <ConnectionChoiceList items={connections.data.items} selectedId={chosen?.id}
                onSelect={item => { setConnection(item); setDriver(null); setCredentials(null); }} />
            ) : (
              <EmptyState compact
                title={t(adapterFilter ? "adapter.noMatchingConnections" : "ui.noConnectionsYet")}
                description={t(adapterFilter ? "wizard.noFilterResultsHint" : "ui.addATargetDatabaseConnectionFirst")}
                action={
                  adapterFilter ? <Button variant="outline" onClick={() => { setAdapterFilter(""); setPage(0); }}>
                    {t("adapter.clearFilter")}
                  </Button> : <Button asChild>
                    <Link to="/connections/new">{t("ui.addConnection")}</Link>
                  </Button>
                }
              />
            )}
            {connections.data.items.length && connections.data.totalPages > 1 ? <Pagination {...connections.data} onPage={setPage} /> : null}
            {chosen && adapters.data && !chosenAdapter ? <p role="alert" className="mt-3 text-sm text-destructive">{t("adapter.legacyConnection")}</p> : null}
          </>
        ) : null}
        {step === 1 ? (
          <div className="wizard-driver-step">
            <h2 className="text-lg font-semibold">{t("ui.chooseAValidatedDriver")}</h2>
            <DriverSelect
              presentation="cards"
              value={chosenDriver?.id ?? ""}
              selected={chosenDriver}
              onChange={setDriver}
            />
            <p className="text-xs text-muted-foreground">{t("adapter.driverValidationBoundary")}</p>
            <RunPassword hasSavedPassword={!!chosen?.hasSavedPassword} mode={passwordMode} password={password}
              onModeChange={mode => setCredentials({ connectionId: chosen!.id, mode, password: "" })}
              onPasswordChange={value => setCredentials({ connectionId: chosen!.id, mode: "other", password: value })} />
          </div>
        ) : null}
        {step === 2 && !catalog.data ? (catalog.isError ? <ErrorState error={catalog.error} retry={() => void catalog.refetch()} /> : <Loading />) : null}
        {step === 2 && catalog.data ? (
          <>
            <h2 className="mb-3 text-lg font-semibold">
              {t("ui.defineYourTestSelection")}</h2>
            <TestSelection catalog={catalog.data} selected={selection.selected} onChange={selection.setSelected} />
            {selection.removed ? <p role="status" className="mt-3 text-sm">{t("selection.removed", { count: selection.removed })}</p> : null}
            <label className="mt-5 flex flex-wrap items-center gap-3 text-sm">{t("selection.budget")}
              <input type="number" min={60} max={7200} step={1} value={budgetSeconds}
                className="w-28 rounded-md border bg-background p-2"
                onChange={event => setBudgetSeconds(Number(event.target.value))} />
            </label>
            <p className="mt-2 text-xs text-muted-foreground">{t("selection.budgetHint")}</p>
            <p className="mt-5 text-sm font-semibold" aria-live="polite">
              {t("wizard.selectedCount", {countText: selectionCount == null ? t("ui.scenariosInline") : plural(selectionCount, "scenario")})}</p>
          </>
        ) : null}
        {step === 3 ? (
          <>
            <h2 className="mb-3 text-lg font-semibold">
              {t("ui.reviewBeforeYouRun")}</h2>
            <div className="mb-5 max-w-2xl"><Field id="run-name" label={t("record.taskName")} required
              maxLength={200} value={runName} onChange={event => setName(event.target.value)}
              error={!nameValid ? t("record.nameRequired") : undefined} /></div>
            <div className="grid min-w-0 gap-5 md:grid-cols-3 [&>div]:min-w-0">
              <div>
                <p className="eyebrow">{t("ui.targetConnection")}</p>
                <p className="mt-3 break-words font-semibold">{chosen?.name}</p>
                <p className="mt-2 text-sm text-muted-foreground">
                  {t("ui.databaseLabel")}{chosen?.databaseType}  {t("ui.adapterInline")} {chosen?.adapterId}
                </p>
                {chosen ? <div className="mt-2"><JdbcUrl value={chosen.jdbcUrl} /></div> : null}
                <p className="mt-2 text-sm">{t("ui.authorizedTestScope")} {chosen?.executionDatabase || "—"} / {chosen?.executionSchema || "—"}</p>
                <p className="mt-2 text-sm">{t("ui.passwordForThisRun")}: {t(`runPassword.${passwordMode}`)}</p>
                <p className="mt-2 text-sm">{t("connection.sqlDialect")}: {chosen?.sqlDialect ?? chosenAdapter?.dialect ?? "—"}</p>
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
                  {t("selection.review", { selected: selectionCount, excluded: excludedIds.length, total: catalog.data?.scenarios.length ?? 0 })}

                </p>
              </div>
            </div>
            <div className="my-4 text-sm" role="status">
              <p>{t("selection.conditions", { count: unmet.length })}</p>
              {unmet.length ? <details className="mt-2"><summary>{t("selection.showConditions")}</summary>
                <ul className="mt-2 max-h-48 overflow-auto">{unmet.map(item => <li key={item.id} className="break-words font-mono text-xs">{item.id}: {missingPrerequisites(item, driverDetail.data?.dataSources).join(", ")}</li>)}</ul>
              </details> : null}
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
            {!passwordReady ? (
              <p role="alert" className="mt-4 text-sm text-destructive">
                {t("ui.reEnterTheTemporaryPasswordInThe")}</p>
            ) : null}
          </>
        ) : null}
      </Card>
      {error ? <div className="mt-3"><FormError message={error} /></div> : null}
      <div className="wizard-actions">
        <div className="wizard-back-selection flex min-w-0 flex-1 items-center gap-3">
        <Button
          variant="outline"
          disabled={step === 0 || create.isPending}
          onClick={() => setStep((previous) => previous - 1)}
        >
          {t("ui.back")}</Button>
        {step === 0 ? <p className="wizard-current-connection min-w-0 text-sm" aria-live="polite">
          <span className="text-muted-foreground">{t("ui.selectedConnection")} </span>
          <strong className="break-words">{chosen?.name ?? t("wizard.noConnectionSelected")}</strong>
        </p> : null}
        </div>
        {step < 3 ? (
          <div className="flex flex-wrap items-center justify-end gap-3">
          {!continueAllowed && !(step === 0 && outsideFilter) ? <p id="continue-reason" className="text-sm text-muted-foreground">
            {step === 0 ? t("ui.selectAConnectionToContinue") : step === 1 ? chosenDriver?.status !== "VALIDATED" ? t("ui.selectAValidatedDriverToContinue") : t("ui.enterThePasswordForThisRunTo") : t("ui.selectAtLeastOneModuleToContinue")}
          </p> : null}
          <Button aria-describedby={!continueAllowed ? step === 0 && outsideFilter ? "selected-outside-filter" : "continue-reason" : undefined}
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
              !chosenAdapter ||
              !consent ||
              selectionCount === 0 ||
              !catalog.data ||
              !budgetValid ||
              !nameValid ||
              chosenDriver?.status !== "VALIDATED" ||
              (!passwordReady)
            }
            onClick={() => {
              if (submitting.current) return;
              // Mutation state updates after this event; guard a second click immediately.
              submitting.current = true;
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
