import { t } from "i18next";
import { ApiError } from "@/api/client";
import { FormError } from "@/components/common";

export function ProbeError({ error }: { error: string | Error | null }) {
  const fields = error instanceof ApiError ? error.properties : {};
  const details = Array.isArray(fields.diagnostics) ? fields.diagnostics : [];
  return <div className="min-w-0 space-y-2">
    <FormError message={error} />
    {typeof fields.stage === "string" || details.length ? <details className="rounded-lg border p-3 text-sm">
      <summary className="cursor-pointer font-medium">{t("connection.errorDetails")}</summary>
      <div className="mt-3 space-y-3 break-words [overflow-wrap:anywhere]">
        {typeof fields.stage === "string" ? <p>{t("connection.failureStage")}: {t(`connection.stages.${fields.stage}`, { defaultValue: fields.stage })}</p> : null}
        {details.map((item: unknown, index) => {
          if (!item || typeof item !== "object") return null;
          const detail = item as Record<string, unknown>;
          return <dl key={index} className="space-y-1 font-mono text-xs">
            {["sqlState", "vendorCode", "exceptionType", "message"].map(key =>
              typeof detail[key] === "string" || typeof detail[key] === "number"
                ? <div key={key}><dt className="inline font-semibold">{t(`connection.diagnostic.${key}`)}: </dt><dd className="inline whitespace-pre-wrap">{String(detail[key])}</dd></div> : null)}
          </dl>;
        })}
      </div>
    </details> : null}
  </div>;
}
