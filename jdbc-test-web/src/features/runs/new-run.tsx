import { TestSelection } from "./test-selection";
import { useTestSelection, missingPrerequisites } from "./test-selection-state";
import { Card } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { adaptersApi, adapterKeys } from "@/api/adapters";
import { AdapterDetails } from "@/features/connections/adapter-details";
import { profileName } from "@/lib/presentation";
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
  const [adapterFilter, setAdapterFilter] = useState("");
  const [connection, setConnection] = useState<Connection | null>(null);
  const [driver, setDriver] = useState<DriverSummary | null>(null);
  const [budgetSeconds, setBudgetSeconds] = useState(1800);
  const [consent, setConsent] = useState(false);
  const [differentPassword, setDifferentPassword] = useState(false);
  const [password, setPassword] = useState("");
  const [emptyPassword, setEmptyPassword] = useState(false);
  const [error, setError] = useState<string | Error | null>(null);
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
  const budgetValid = Number.isInteger(budgetSeconds) && budgetSeconds >= 60 && budgetSeconds <= 7200;
  const outsideFilter = !!chosen && !!adapterFilter && chosen.adapterId !== adapterFilter;
  const chosenAdapter = adapters.data?.find((item) => item.id === chosen?.adapterId);
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
        modules: [],
        includeScenarios: selectedIds,
        excludeScenarios: excludedIds,
        catalogVersion: catalog.data?.catalogVersion,
        expectedAdapterFingerprint: chosenAdapter?.fingerprint ?? undefined,
        budgetSeconds,
        destructiveConsent: consent,
        ...(emptyPassword ? { password: "" } : needsPassword ? { password } : {}),
      }),
    onSuccess: (run) => {
      toast.success(t("ui.runQueued"));
      void client.invalidateQueries({ queryKey: runsKeys.all });
      client.setQueryData(runsKeys.detail(run.id), run);
      navigate(`/runs/${run.id}`);
    },
    onError: (failure) => setError(failure instanceof Error ? failure : new Error()),
    onSettled: () => { setPassword(""); setEmptyPassword(false); },
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
        ? chosenDriver?.status === "VALIDATED" && (!needsPassword || !!password || emptyPassword)
        : step === 2
          ? !!catalog.data && selectionCount > 0 && budgetValid
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
                  : "border-border bg-card text-muted-foreground",
              )}
            >
              <span
                className={cn(
                  "flex size-7 items-center justify-center rounded-full text-xs font-semibold",
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
      <Card as="section" className="panel">
        <p className="eyebrow mb-2">{t("wizard.step", {step: step + 1})}</p>
        {step === 0 ? (
          <>
            <h2 className="mb-3 text-xl font-semibold">{t("ui.chooseAConnection")}</h2>
            <div className="mb-4">
              <Field id="run-adapter-filter" label={t("adapter.filter")} hint={t("adapter.filterHint")}>
                <Select id="run-adapter-filter" value={adapterFilter}
                  disabled={!adapters.data}
                  onValueChange={(value) => { setAdapterFilter(value); setPage(0); }}>
                  <option value="">{t("adapter.all")}</option>
                  {adapters.data?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                </Select>
              </Field>
              {adapters.isError ? <ErrorState error={adapters.error} retry={() => void adapters.refetch()} /> : null}
            </div>
            {outsideFilter ? (
              <div className="mb-4 rounded-xl border border-warning/30 p-4 text-sm" role="status">
                <p>{t("adapter.selectedOutsideFilter", { name: chosen.name })}</p>
                <Button variant="ghost" onClick={() => { setAdapterFilter(""); setPage(0); }}>{t("adapter.clearFilter")}</Button>
              </div>
            ) : null}
            {connections.data.items.length ? (
              <div className="grid gap-4 md:grid-cols-2">
                {connections.data.items.map((item) => (
                  <div key={item.id} data-selected={chosen?.id === item.id} className="step-card min-w-0 cursor-pointer"
                    onClick={(event) => {
                      if ((event.target as HTMLElement).closest("button, input, label")) return;
                      setConnection(item); setDriver(null); setPassword(""); setEmptyPassword(false); setDifferentPassword(false);
                    }}>
                    <label className="mb-2 flex cursor-pointer items-center gap-2 text-sm font-medium text-primary">
                      <input type="radio" name="run-connection" aria-label={item.name}
                        className="size-4 accent-primary" checked={chosen?.id === item.id}
                        onChange={() => { setConnection(item); setDriver(null); setPassword(""); setEmptyPassword(false); setDifferentPassword(false); }} />
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
                title={t(adapterFilter ? "adapter.noMatchingConnections" : "ui.noConnectionsYet")}
                description={t(adapterFilter ? "adapter.createMatchingConnection" : "ui.addATargetDatabaseConnectionFirst")}
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
            {chosen ? <div className="mt-4"><AdapterDetails adapter={chosenAdapter} />
              {chosen.databaseType === "compatible" ? <p className="mt-2 text-sm">{t("adapter.compatibleHint")}</p> : null}
              {adapters.data && !chosenAdapter ? <p role="alert">{t("adapter.legacyConnection")}</p> : null}
            </div> : null}
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
            <p className="text-sm text-muted-foreground">{t("adapter.driverValidationBoundary")}</p>
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
            <label className="my-3 flex items-center gap-2 text-sm">
              <input type="checkbox" checked={emptyPassword}
                onChange={(event) => { setEmptyPassword(event.target.checked); setPassword(""); }} />
              {t("credentials.emptyPassword")}
            </label>
            {needsPassword ? (
              <Field
                id="run-password"
                label={t("ui.passwordForThisRun")}
                type="password"
                autoComplete="new-password"
                maxLength={2000}
                disabled={emptyPassword}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                hint={t("ui.thisPasswordIsUsedOnlyForThis")}
              />
            ) : (
              <p className="text-sm text-muted-foreground">
                {emptyPassword ? t("credentials.emptyPassword") : t("ui.theSavedConnectionPasswordWillBeUsed")}</p>
            )}
          </div>
        ) : null}
        {step === 2 && !catalog.data ? (catalog.isError ? <ErrorState error={catalog.error} retry={() => void catalog.refetch()} /> : <Loading />) : null}
        {step === 2 && catalog.data ? (
          <>
            <h2 className="mb-3 text-xl font-semibold">
              {t("ui.defineYourTestSelection")}</h2>
            <TestSelection catalog={catalog.data} selected={selection.selected} onChange={selection.setSelected} />
            {selection.removed ? <p role="status" className="mt-3 text-sm">{t("selection.removed", { count: selection.removed })}</p> : null}
            <label className="mt-5 block text-sm">{t("selection.budget")}
              <input type="number" min={60} max={7200} step={1} value={budgetSeconds}
                className="mx-3 w-28 rounded-md border bg-background p-2"
                onChange={event => setBudgetSeconds(Number(event.target.value))} />
            </label>
            <p className="mt-2 text-xs text-muted-foreground">{t("selection.budgetHint")}</p>
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
                <div className="mt-3"><AdapterDetails adapter={chosenAdapter} /></div>
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
            {needsPassword && !password && !emptyPassword ? (
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
              !chosenAdapter ||
              !consent ||
              selectionCount === 0 ||
              !catalog.data ||
              !budgetValid ||
              chosenDriver?.status !== "VALIDATED" ||
              (needsPassword && !password && !emptyPassword)
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
