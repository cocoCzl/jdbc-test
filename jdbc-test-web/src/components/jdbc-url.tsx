import { useTranslation } from "react-i18next";
import { useState } from "react";
import { Copy, Expand } from "lucide-react";
import { toast } from "sonner";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";
import { Tooltip } from "./ui/tooltip";
import { redactJdbcUrl } from "@/lib/jdbc-url";

export function JdbcUrl({ value, compact = false }: { value: string; compact?: boolean }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const safe = redactJdbcUrl(value);
  const copy = () => {
    if (!navigator.clipboard?.writeText) { toast.error(t("ui.clipboardUnavailable")); return; }
    void navigator.clipboard.writeText(safe).then(
      () => toast.success(t("common.copied", {label: t("ui.sanitizedJDBCURL")})),
      () => toast.error(t("ui.clipboardUnavailable")),
    );
  };
  return <div className={compact ? "jdbc-url-compact" : "min-w-0"} onClick={event => {
    if (!compact || (event.target as HTMLElement).closest("button, [role='dialog']")) event.stopPropagation();
  }}>
    <p className="truncate font-mono text-xs leading-relaxed" title={safe}>{safe}</p>
    {compact ? <div className="flex items-center gap-1">
      <Tooltip label={t("url.view")}><Button type="button" variant="ghost" size="icon-sm" aria-label={t("url.view")} onClick={() => setOpen(true)}><Expand aria-hidden="true" /></Button></Tooltip>
      <Tooltip label={t("ui.copySanitizedURL")}><Button type="button" variant="ghost" size="icon-sm" aria-label={t("ui.copySanitizedURL")} onClick={copy}><Copy aria-hidden="true" /></Button></Tooltip>
    </div> : <div className="mt-2 flex flex-wrap gap-1">
      <Button type="button" variant="outline" size="xs" onClick={() => setOpen(true)}><Expand />{t("url.view")}</Button>
      <Button type="button" variant="outline" size="xs" onClick={copy}><Copy />{t("url.copy")}</Button>
    </div>}
    <Dialog open={open} onOpenChange={setOpen} title={t("ui.jdbcURL")} description={t("ui.fullTargetAddressRecognizedCredentialsAreMasked")}>
      <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all rounded-2xl border bg-muted p-4 font-mono text-sm leading-relaxed select-text">{safe}</pre>
      <div className="mt-4 flex justify-end"><Button type="button" variant="outline" size="sm" onClick={copy}><Copy />{t("ui.copySanitizedURL")}</Button></div>
    </Dialog>
  </div>;
}
