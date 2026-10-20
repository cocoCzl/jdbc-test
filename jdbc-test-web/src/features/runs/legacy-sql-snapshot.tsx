import { useTranslation } from "react-i18next";
import { Card } from "@/components/ui/card";

export function LegacySqlSnapshot({ snapshot }: { snapshot: { manifest: string; fingerprint: string } }) {
  const { t } = useTranslation();
  let definition: { id: string; version: string; description: string } | null = null;
  try {
    const parsed: unknown = JSON.parse(snapshot.manifest);
    if (typeof parsed === "object" && parsed !== null && "id" in parsed && typeof parsed.id === "string" &&
      "version" in parsed && typeof parsed.version === "string" && "description" in parsed && typeof parsed.description === "string") {
      definition = { id: parsed.id, version: parsed.version, description: parsed.description };
    }
  } catch {
    // Historical/unreadable source stays visible as evidence; no current-library lookup.
    definition = null;
  }
  return <Card className="mb-4 space-y-2">
    <h2 className="font-semibold">{t("sql.frozen")}</h2>
    {definition ? <><p>{definition.id} · {definition.version} · {t("sql.originLocal")}</p><p className="text-sm">{definition.description}</p></> : <p>{t("sql.unreadable")}</p>}
    <p className="text-sm">{t("sql.boundary")}</p>
    <p className="break-all font-mono text-xs">{snapshot.fingerprint}</p>
    <details><summary>{t("sql.definition")}</summary><pre className="overflow-auto whitespace-pre-wrap break-words text-xs">{snapshot.manifest}</pre></details>
  </Card>;
}
