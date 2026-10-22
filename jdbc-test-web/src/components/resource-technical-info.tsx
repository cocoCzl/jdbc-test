import { t } from "i18next";
import { CopyButton } from "./common";

export function ResourceTechnicalInfo({ id }: { id: string }) {
  return <details className="border-t pt-3">
    <summary className="cursor-pointer text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-4">
      {t("resource.technicalInfo")}
    </summary>
    <dl className="mt-3 text-sm">
      <dt className="text-muted-foreground">{t("resource.internalId")}</dt>
      <dd className="mt-1 flex items-start gap-2">
        <code className="min-w-0 break-all text-xs">{id}</code>
        <CopyButton value={id} label={t("resource.internalId")} />
      </dd>
    </dl>
  </details>;
}
