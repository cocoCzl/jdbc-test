import { useTranslation } from "react-i18next";
import type { Adapter } from "@/types/api";
import { adapterStatus } from "@/lib/presentation";

export function AdapterDetails({ adapter }: { adapter?: Adapter }) {
  const { t } = useTranslation();
  if (!adapter) return null;
  return (
    <div className="space-y-2 rounded-xl border border-border bg-muted/30 p-4 text-sm">
      <p className="font-medium">{adapter.name} · {adapterStatus(adapter.status)}</p>
      <p className="text-muted-foreground">{t("adapter.declarationBoundary")}</p>
      {adapter.dialect ? <p>{t("adapter.dialect", { value: adapter.dialect })}</p> : null}
      {adapter.namespaceSelection ? <p>{t(`adapter.scope.${adapter.namespaceSelection}`)}</p> : null}
      {adapter.driverClass ? <p className="break-all">{t("adapter.driverClass", { value: adapter.driverClass })}</p> : null}
      {adapter.recommendedMavenCoordinate ? (
        <p className="break-all">{t("adapter.coordinate", { value: adapter.recommendedMavenCoordinate })}</p>
      ) : null}
      <p className="text-muted-foreground">{t("adapter.prepareDriver")}</p>
      {adapter.validatedCombinations ? (
        <div>
          <p className="font-medium">{t("adapter.declaredCombinations")}</p>
          {adapter.validatedCombinations.length ? (
            <ul className="mt-1 list-inside list-disc">
              {adapter.validatedCombinations.map((item, index) => (
                <li key={index}>{t("adapter.combination", { database: item.databaseVersion, driver: item.driverVersion })}</li>
              ))}
            </ul>
          ) : <p className="text-muted-foreground">{t("adapter.noCombinations")}</p>}
        </div>
      ) : null}
    </div>
  );
}
