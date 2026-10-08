import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { useState } from "react";
import { CopyButton } from "./common";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";
import { redactJdbcUrl } from "@/lib/jdbc-url";

export function JdbcUrl({ value }: { value: string }) {
  useTranslation();
  const [open, setOpen] = useState(false);
  const safe = redactJdbcUrl(value);
  return <div className="min-w-0">
    <p className="break-all font-mono text-xs leading-relaxed">{safe}</p>
    <Button type="button" variant="ghost" size="sm" className="mt-1" onClick={() => setOpen(true)}>
      {t("ui.viewCopyURL")}</Button>
    <Dialog open={open} onOpenChange={setOpen} title={t("ui.jdbcURL")}
      description={t("ui.fullTargetAddressRecognizedCredentialsAreMasked")}>
      <p className="break-all rounded-lg bg-muted p-3 font-mono text-sm leading-relaxed">{safe}</p>
      <div className="mt-3 flex items-center gap-2 text-sm"><CopyButton value={safe} label={t("ui.sanitizedJDBCURL")} />{t("ui.copySanitizedURL")}</div>
    </Dialog>
  </div>;
}
