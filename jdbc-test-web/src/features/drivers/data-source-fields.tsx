import { useTranslation } from "react-i18next";
import { Field } from "@/components/common";
import type { DataSourceProviders } from "@/types/api";

const interfaces = ["dataSource", "connectionPoolDataSource", "xaDataSource"] as const;
const properties = ["urlProperty", "userProperty", "passwordProperty"] as const;
const defaults = { urlProperty: "URL", userProperty: "user", passwordProperty: "password" };

export function DataSourceFields({ value, onChange }: {
  value: DataSourceProviders;
  onChange: (value: DataSourceProviders) => void;
}) {
  const { t } = useTranslation();
  return (
    <details className="rounded-lg border border-border p-4">
      <summary className="cursor-pointer font-medium">{t("ui.dataSourceProviders")}</summary>
      <p className="mt-3 text-sm text-muted-foreground">{t("ui.dataSourceProvidersHint")}</p>
      <div className="mt-4 space-y-5">
        {interfaces.map((key) => (
          <div key={key} className="space-y-3">
            <Field
              id={`provider-${key}`}
              label={`${key} — ${t("ui.implementationClass")}`}
              maxLength={300}
              value={value[key]?.className ?? ""}
              onChange={(event) => onChange({ ...value, [key]: event.target.value.trim()
                ? { ...value[key], className: event.target.value.trim() } : undefined })}
            />
            {value[key]?.className ? (
              <div className="grid gap-3 sm:grid-cols-3">
                {properties.map((property) => (
                  <Field key={property} id={`provider-${key}-${property}`}
                    label={t(`ui.${property}`)} maxLength={100}
                    value={value[key]?.[property] ?? defaults[property]}
                    onChange={(event) => onChange({ ...value, [key]: {
                      ...value[key]!, [property]: event.target.value,
                    } })}
                  />
                ))}
              </div>
            ) : null}
          </div>
        ))}
      </div>
    </details>
  );
}
