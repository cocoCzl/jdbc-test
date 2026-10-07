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
    <div className="mb-8 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="page-title">{title}</h1>
        <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
          {description}
        </p>
      </div>
      {action}
    </div>
  );
}
export function Loading({ label = "Loading workspace…" }: { label?: string }) {
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
  return (
    <div role="alert" className="panel flex items-start gap-3">
      <TriangleAlert className="mt-1 size-5 shrink-0 text-destructive" />
      <div>
        <h2 className="font-semibold">Unable to load this resource</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          {errorMessage(error)}
        </p>
        {retry ? (
          <Button variant="outline" size="sm" className="mt-4" onClick={retry}>
            Try again
          </Button>
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
        "inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-medium",
        {
          "bg-blue-50 text-blue-700": tone === "active",
          "bg-emerald-50 text-emerald-700": tone === "success",
          "bg-rose-50 text-rose-700": tone === "danger",
          "bg-slate-100 text-slate-600": tone === "neutral",
          "bg-amber-50 text-amber-800": tone === "warning",
        },
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
export function FormError({ message }: { message: string | null }) {
  return message ? (
    <p
      role="alert"
      className="rounded-xl bg-rose-50 p-3 text-sm text-destructive"
    >
      {message}
    </p>
  ) : null;
}
export function CopyButton({ value, label }: { value: string; label: string }) {
  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label={`Copy ${label}`}
      onClick={() => {
        void navigator.clipboard.writeText(value).then(
          () => toast.success(`${label} copied`),
          () => toast.error("Clipboard unavailable"),
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
}: {
  label: string;
  value: ReactNode;
  note?: string;
  icon?: ReactNode;
}) {
  return (
    <div className="panel">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">{label}</p>
        {icon ? (
          <span className="rounded-xl bg-accent p-2 text-primary">{icon}</span>
        ) : null}
      </div>
      <p className="mt-4 text-3xl font-semibold tracking-tight">{value}</p>
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
  return (
    <div
      role="status"
      className="rounded-2xl border border-emerald-100 bg-emerald-50/60 p-5"
    >
      <h3 className="flex items-center gap-2 font-semibold text-success">
        <Check className="size-4" />
        Connected
      </h3>
      <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-muted-foreground">Database</dt>
          <dd>
            {result.databaseProductName ?? "—"} {result.databaseProductVersion}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Driver</dt>
          <dd>
            {result.driverName ?? "—"} {result.driverVersion}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">JDBC version</dt>
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
