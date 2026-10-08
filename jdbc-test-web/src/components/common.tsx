import i18n from "@/i18n";
import { formatNumber } from "@/lib/format";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { Check, Copy, Inbox, LoaderCircle, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { toast } from "sonner";
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
    <div className="mb-5 flex flex-wrap items-start justify-between gap-4">
      <div className="min-w-0 flex-1">
        <h1 className="page-title break-words">{title}</h1>
        <p className="mt-2 break-words text-sm leading-relaxed text-muted-foreground">
          {description}
        </p>
      </div>
      {action}
    </div>
  );
}
export function Loading({ label = t("ui.loadingWorkspace") }: { label?: string }) {
  useTranslation();
  return (
    <div
      role="status"
      className="panel flex min-h-40 items-center justify-center gap-3 text-muted-foreground"
    >
      <LoaderCircle className="size-5 animate-spin" />
      {label}
    </div>
  );
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
    <div role="alert" className="panel flex items-start gap-3">
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
    </div>
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
    <div className="flex min-h-64 flex-col items-center justify-center p-8 text-center">
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
    <Button
      variant="ghost"
      size="icon"
      aria-label={t("common.copy", { label })}
      onClick={() => {
        void navigator.clipboard.writeText(value).then(
          () => toast.success(t("common.copied", { label })),
          () => toast.error(t("ui.clipboardUnavailable")),
        );
      }}
    >
      <Copy />
    </Button>
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
    <div className={`panel metric tone-${tone}${subdued ? " metric-subdued" : ""}`} data-metric={label}>
      <div className="flex items-center justify-between gap-2">
        <p className="min-w-0 break-words text-sm text-muted-foreground">{label}</p>
        {icon ? (
          <span className="metric-icon rounded-lg p-2">{icon}</span>
        ) : null}
      </div>
      <p className="mt-2 text-3xl font-semibold tracking-tight">{typeof value === "number" ? formatNumber(value) : value}</p>
      {note ? (
        <p className="mt-2 text-xs text-muted-foreground">{note}</p>
      ) : null}
    </div>
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
