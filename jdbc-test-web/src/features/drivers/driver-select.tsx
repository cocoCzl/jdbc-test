import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { driversApi, driversKeys } from "@/api/drivers";
import { ErrorState, Field, Loading } from "@/components/common";
import { Pagination } from "@/components/data-table";
import type { DriverSummary } from "@/types/api";
export function DriverSelect({
  value,
  selected,
  onChange,
  presentation = "select",
}: {
  value: string;
  presentation?: "select" | "cards";
  selected?: DriverSummary | null;
  onChange: (driver: DriverSummary) => void;
}) {
  useTranslation();
  const [page, setPage] = useState(0);
  const params = { page, size: 20, sort: "name,asc" };
  const query = useQuery({
    queryKey: driversKeys.list(params),
    queryFn: ({ signal }) => driversApi.list(params, signal),
    placeholderData: keepPreviousData,
  });
  if (query.isPending) return <Loading label={t("ui.loadingDrivers")} />;
  if (query.isError)
    return (
      <ErrorState error={query.error} retry={() => void query.refetch()} />
    );
  const options: DriverSummary[] = query.data.items.filter(
    (driver) => driver.status === "VALIDATED",
  );
  if (
    selected?.status === "VALIDATED" &&
    !options.some((driver) => driver.id === selected.id)
  )
    options.unshift(selected);
  return (
    <div>
      {presentation === "cards" ? <fieldset className="space-y-3">
        <legend className="mb-3 text-sm font-medium">{t("ui.driverPackage")}</legend>
        {options.map((driver) => <label key={driver.id} className="step-card flex cursor-pointer items-start gap-3">
          <input type="radio" name="run-driver" aria-label={`${driver.name} · ${driver.version}`}
            className="mt-1 size-4 shrink-0 accent-primary" checked={value === driver.id} onChange={() => onChange(driver)} />
          <span className="min-w-0">
            <span className="block break-words font-semibold">{driver.name}</span>
            <span className="mt-1 block text-sm text-muted-foreground">{driver.vendor} · {driver.version}</span>
            <span className="mt-2 block text-xs font-medium text-primary">{value === driver.id ? t("ui.selectedValidated") : t("ui.validated")}</span>
          </span>
        </label>)}
      </fieldset> : <Field
        id="default-driver"
        label={t("ui.driverPackage")}
        hint={t("ui.onlyValidatedPackagesCanBeUsedBrowse")}
      >
        <select
          id="default-driver"
          className="select"
          value={value}
          required
          onChange={(event) => {
            const driver = options.find(
              (item) => item.id === event.target.value,
            );
            if (driver) onChange(driver);
          }}
        >
          <option value="">{t("ui.selectAValidatedDriver")}</option>
          {options.map((driver) => (
            <option key={driver.id} value={driver.id}>
              {driver.name} · {driver.vendor} · {driver.version}
            </option>
          ))}
        </select>
      </Field>}
      {!options.length ? (
        <p className="mt-3 text-sm text-muted-foreground">
          {t("ui.noValidatedDriversOnThisPageValidate")}</p>
      ) : null}
      {query.data.totalPages > 1 ? (
        <Pagination {...query.data} onPage={setPage} />
      ) : null}
    </div>
  );
}
