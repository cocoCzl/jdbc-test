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
}: {
  value: string;
  selected?: DriverSummary | null;
  onChange: (driver: DriverSummary) => void;
}) {
  const [page, setPage] = useState(0);
  const params = { page, size: 20, sort: "name,asc" };
  const query = useQuery({
    queryKey: driversKeys.list(params),
    queryFn: ({ signal }) => driversApi.list(params, signal),
    placeholderData: keepPreviousData,
  });
  if (query.isPending) return <Loading label="Loading drivers…" />;
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
      <Field
        id="default-driver"
        label="Driver package"
        hint="Only validated packages can be used. Browse pages to find another driver."
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
          <option value="">Select a validated driver</option>
          {options.map((driver) => (
            <option key={driver.id} value={driver.id}>
              {driver.name} · {driver.vendor} · {driver.version}
            </option>
          ))}
        </select>
      </Field>
      {!options.length ? (
        <p className="mt-3 text-sm text-muted-foreground">
          No validated drivers on this page. Validate a package in Drivers or
          browse the next page.
        </p>
      ) : null}
      {query.data.totalPages > 1 ? (
        <Pagination {...query.data} onPage={setPage} />
      ) : null}
    </div>
  );
}
