import type { CleanupEvidence as Evidence } from "@/types/api";
import { useTranslation } from "react-i18next";

export function CleanupEvidence({ evidence }: { evidence?: Evidence }) {
  const { t } = useTranslation();
  return <div className={`cleanup-evidence my-4 text-sm cleanup-${evidence?.status ?? "UNKNOWN"}`}>
    <p>{t("cleanup.label")}: {t(`cleanup.${evidence?.status ?? "UNKNOWN"}`)}</p>
    {evidence?.attempted != null ? <p>{t("cleanup.counts", {
      attempted: evidence.attempted, deleted: evidence.confirmedDeleted ?? 0,
    })}</p> : null}
    {evidence?.retainedObjects?.length ? <details>
      <summary>{t("cleanup.retained")}</summary>
      <pre className="overflow-auto whitespace-pre-wrap">{JSON.stringify(evidence.retainedObjects, null, 2)}</pre>
    </details> : null}
  </div>;
}
