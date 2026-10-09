import { Card } from "@/components/ui/card";
import i18n from "@/i18n";
import { formatNumber } from "@/lib/format";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { Check, Copy, Inbox, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { toast } from "sonner";
import { Tooltip } from "./ui/tooltip";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { errorMessage } from "@/api/client";
import { cn } from "@/lib/utils";
import type { Tone } from "@/lib/status";
export function PageHeader({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="jdbc-page-header flex flex-wrap items-start justify-between gap-4">
      <div className="min-w-0 flex-1">
        <h1 title={title} className="page-title break-words">{title}</h1>
        {description ? <p className="mt-2 break-words text-sm leading-relaxed text-muted-foreground">{description}</p> : null}
      </div>
      {action}
    </div>
  );
}
export function Loading({ label = t("ui.loadingData") }: { label?: string }) {
  useTranslation();
  return <div role="status" aria-label={label} data-loading-region className="resource-skeleton">
    <span className="sr-only">{label}</span>
    {[0,1,2,3].map(row => <div className="skeleton-row" aria-hidden="true" key={row}><span /><span /><span /></div>)}
  </div>;
}
export function PendingPage({ title, error, retry }: {title:string; error?:unknown; retry?:()=>void}) {
  return <><PageHeader title={title} description="" />{error ? <ErrorState error={error} retry={retry} /> : <Loading />}</>;
}
export function ErrorState({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  useTranslation();
  return (
    <Card role="alert" className="panel flex items-start gap-3">
      <TriangleAlert className="mt-1 size-5 shrink-0 text-destructive" />
      <div className="min-w-0 break-words">
        <h2 className="font-semibold">{t("ui.unableToLoadThisResource")}</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          {errorMessage(error)}
        </p>
        {retry ? (
          <Button variant="outline" size="sm" className="mt-4" onClick={retry}>
            {t("ui.tryAgain")}</Button>
        ) : null}
      </div>
    </Card>
  );
}
export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="jdbc-empty flex min-h-52 flex-col items-center justify-center p-8 text-center">
      <div className="mb-4 rounded-2xl bg-accent p-4">
        <Inbox className="size-7 text-primary" />
      </div>
      <h2 className="text-lg font-semibold">{title}</h2>
      <p className="mb-5 mt-2 max-w-md text-sm text-muted-foreground">
        {description}
      </p>
      {action}
    </div>
  );
}
export function StatusBadge({ label, tone }: { label: string; tone: Tone }) {
  return (
    <span
      className={cn(
        "status-badge inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-medium",
        `tone-${tone}`,
      )}
    >
      <span className="size-1.5 rounded-full bg-current" />
      {label}
    </span>
  );
}
export function Field({
  label,
  id,
  hint,
  children,
  ...props
}: React.ComponentProps<typeof Input> & {
  label: string;
  id: string;
  hint?: string;
  children?: ReactNode;
}) {
  return (
    <div className="field">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {children ?? <Input id={id} {...props} />}
      {hint ? (
        <p className="text-xs leading-relaxed text-muted-foreground">{hint}</p>
      ) : null}
    </div>
  );
}
export function FormError({ message }: { message: string | Error | null }) {
  useTranslation();
  return message ? (
    <p
      role="alert"
      className="break-words rounded-xl bg-destructive/5 p-3 text-sm text-destructive"
    >
      {message instanceof Error ? errorMessage(message) : i18n.exists(message) ? t(message) : message}
    </p>
  ) : null;
}
export function CopyButton({ value, label }: { value: string; label: string }) {
  useTranslation();
  return (
    <Tooltip label={t("common.copy", { label })}><Button
      type="button"
      variant="ghost"
      size="icon-sm"
      aria-label={t("common.copy", { label })}
      onClick={() => {
        void navigator.clipboard.writeText(value).then(
          () => toast.success(t("common.copied", { label })),
          () => toast.error(t("ui.clipboardUnavailable")),
        );
      }}
    >
      <Copy />
    </Button></Tooltip>
  );
}
export function Metric({
  label,
  value,
  note,
  icon,
  tone = "active",
  subdued = false,
}: {
  label: string;
  value: ReactNode;
  note?: string;
  icon?: ReactNode;
  tone?: Tone | "highlight" | "cyan";
  subdued?: boolean;
}) {
  return (
    <Card className={`panel metric tone-${tone}${subdued ? " metric-subdued" : ""}`} data-metric={label}>
      <div className="metric-header">
        <p className="metric-label">{label}</p>
        {icon ? (
          <span className="metric-icon">{icon}</span>
        ) : null}
      </div>
      <p className="metric-value">{typeof value === "number" ? formatNumber(value) : value}</p>
      {note ? (
        <p className="metric-note">{note}</p>
      ) : null}
    </Card>
  );
}
export function ProbeResult({
  result,
}: {
  result: import("@/types/api").Probe;
}) {
  useTranslation();
  return (
    <div
      role="status"
      className="rounded-2xl border border-success/20 bg-success/5 p-5"
    >
      <h3 className="flex items-center gap-2 font-semibold text-success">
        <Check className="size-4" />
        {t("ui.connected")}</h3>
      <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-muted-foreground">{t("ui.database")}</dt>
          <dd>
            {result.databaseProductName ?? "—"} {result.databaseProductVersion}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">{t("ui.driver")}</dt>
          <dd>
            {result.driverName ?? "—"} {result.driverVersion}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">{t("ui.jdbcVersion")}</dt>
          <dd>
            {result.jdbcMajorVersion != null
              ? `${result.jdbcMajorVersion}.${result.jdbcMinorVersion ?? 0}`
              : "—"}
          </dd>
        </div>
      </dl>
    </div>
  );
}
