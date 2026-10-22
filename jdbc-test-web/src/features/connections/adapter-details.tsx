import { useTranslation } from "react-i18next";
import type { Adapter } from "@/types/api";
import { adapterStatus } from "@/lib/presentation";

export function AdapterDetails({ adapter, compact = false }: { adapter?: Adapter; compact?: boolean }) {
  const { t } = useTranslation();
  if (!adapter) return null;
  if (compact) return (
    <details className="border-t pt-4">
      <summary className="cursor-pointer text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-4">
        {t("connection.adapterDetails")}
      </summary>
      <dl className="mt-4 grid gap-4 text-sm sm:grid-cols-2">
        {[
          [t("connection.adapterId"), adapter.id],
          [t("connection.adapterVersion"), adapter.version ? `${adapter.version}${adapter.revision ? ` (${adapter.revision})` : ""}` : undefined],
          [t("connection.dialect"), adapter.dialect],
          [t("ui.source"), adapter.origin ? t(`adapter.origin.${adapter.origin}`) : undefined],
        ].map(([label, value]) => (
          <div key={label} className="min-w-0">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className="mt-1 break-all">{value ?? "—"}</dd>
          </div>
        ))}
        {adapter.fingerprint ? <div className="min-w-0 sm:col-span-2">
          <dt className="text-muted-foreground">{t(adapter.fingerprintScope === "MANIFEST" ? "adapter.manifestFingerprint" : "adapter.packageFingerprint")}</dt>
          <dd className="mt-1 break-all font-mono text-xs">{adapter.fingerprint}</dd>
        </div> : null}
        {adapter.matchConditions ? Object.entries(adapter.matchConditions).map(([key, value]) => (
          <div key={key} className="min-w-0">
            <dt className="text-muted-foreground">{t(`adapter.match.${key}`, { defaultValue: key })}</dt>
            <dd className="mt-1 break-all font-mono text-xs">{value}</dd>
          </div>
        )) : null}
      </dl>
    </details>
  );
  return (
    <div className="space-y-2 rounded-xl border border-border bg-muted/30 p-4 text-sm">
      <p className="font-medium">{adapter.name} · {adapterStatus(adapter.status)}</p>
      <p className="break-words">{adapter.id}{adapter.version ? ` · ${adapter.version} (${adapter.revision})` : ""}{adapter.origin ? ` · ${t(`adapter.origin.${adapter.origin}`)}` : ""}</p>
      {adapter.fingerprint ? <p className="break-all font-mono text-xs">{t(adapter.fingerprintScope === "MANIFEST" ? "adapter.manifestFingerprint" : "adapter.packageFingerprint")}{": "}{adapter.fingerprint}</p> : null}
      {adapter.matchConditions ? <dl className="space-y-1">{Object.entries(adapter.matchConditions).map(([key, value]) => <div key={key} className="break-all"><dt className="inline text-muted-foreground">{t(`adapter.match.${key}`, { defaultValue: key })}: </dt><dd className="inline">{value}</dd></div>)}</dl> : null}
      <p className="text-muted-foreground">{t("adapter.declarationBoundary")}</p>
      {adapter.origin === "IMPORTED" ? <p className="text-muted-foreground">{t("adapter.unverified")}</p> : null}
      {adapter.qualificationNoteKey ? (
        <p className="text-muted-foreground">{t(adapter.qualificationNoteKey)}</p>
      ) : null}
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
