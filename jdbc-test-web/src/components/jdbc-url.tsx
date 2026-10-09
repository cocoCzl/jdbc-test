import { useTranslation } from "react-i18next";
import { useState } from "react";
import { Copy, Expand } from "lucide-react";
import { toast } from "sonner";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";
import { redactJdbcUrl } from "@/lib/jdbc-url";

export function JdbcUrl({ value }: { value: string }) {
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
  return <div className="min-w-0" onClick={event => event.stopPropagation()}>
    <p className="truncate font-mono text-xs leading-relaxed" title={safe}>{safe}</p>
    <div className="mt-2 flex flex-wrap gap-1">
      <Button type="button" variant="outline" size="xs" onClick={() => setOpen(true)}><Expand />{t("url.view")}</Button>
      <Button type="button" variant="outline" size="xs" onClick={copy}><Copy />{t("url.copy")}</Button>
    </div>
    <Dialog open={open} onOpenChange={setOpen} title={t("ui.jdbcURL")} description={t("ui.fullTargetAddressRecognizedCredentialsAreMasked")}>
      <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all rounded-2xl border bg-muted p-4 font-mono text-sm leading-relaxed select-text">{safe}</pre>
      <div className="mt-4 flex justify-end"><Button type="button" variant="outline" size="sm" onClick={copy}><Copy />{t("ui.copySanitizedURL")}</Button></div>
    </Dialog>
  </div>;
}
