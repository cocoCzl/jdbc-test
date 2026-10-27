import { useQuery } from "@tanstack/react-query";
import { adaptersApi, adapterKeys } from "@/api/adapters";
import { t } from "i18next";
import { Field } from "@/components/common";
import { Select } from "@/components/ui/select";
import type { SqlDialect } from "@/types/api";

export function DialectField({ value, onChange, driverDefault = false, hint, error }: {
  value: string;
  onChange: (value: SqlDialect | "") => void;
  driverDefault?: boolean;
  hint?: string;
  error?: string;
}) {
  const adapters = useQuery({
    queryKey: adapterKeys.all,
    queryFn: ({ signal }) => adaptersApi.list(signal),
    staleTime: 300_000,
  });
  const dialects = [...new Set(adapters.data?.map(adapter => adapter.dialect).filter(Boolean))];
  return <Field id="sql-dialect" label={t(driverDefault ? "connection.defaultDialect" : "connection.sqlDialect")}
    hint={hint ?? (driverDefault ? t("connection.defaultDialectHint") : undefined)} error={error}>
    <Select id="sql-dialect" value={value} onValueChange={next => onChange(next as SqlDialect | "")}>
      <option value="">{t(driverDefault ? "connection.followConnection" : "connection.adapterDialect")}</option>
      {dialects.map(dialect => <option key={dialect} value={dialect}>{t(`driverPreset.${dialect}`)}</option>)}
      {value === "sqlserver" ? <option value="sqlserver" disabled>{t("database.sqlServerRetired")}</option> : null}
    </Select>
  </Field>;
}
