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
  return <Field id="sql-dialect" label={t(driverDefault ? "connection.defaultDialect" : "connection.sqlDialect")}
    hint={hint ?? (driverDefault ? t("connection.defaultDialectHint") : undefined)} error={error}>
    <Select id="sql-dialect" value={value} onValueChange={next => onChange(next as SqlDialect | "")}>
      <option value="">{t(driverDefault ? "connection.followConnection" : "connection.adapterDialect")}</option>
      <option value="oracle">{t("driverPreset.oracle")}</option>
      <option value="mysql">{t("driverPreset.mysql")}</option>
      <option value="postgresql">{t("driverPreset.postgresql")}</option>
      {value === "sqlserver" ? <option value="sqlserver">{t("driverPreset.sqlserver")}</option> : null}
    </Select>
  </Field>;
}
