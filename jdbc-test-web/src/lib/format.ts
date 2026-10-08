import i18n from "@/i18n";
export function formatDateTime(value: string | null | undefined) {
  if (!value) return "—";
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "—";
  return new Intl.DateTimeFormat(i18n.resolvedLanguage, {
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).format(date);
}
export function formatNumber(value: number | null | undefined) {
  return value == null ? "—" : new Intl.NumberFormat(i18n.resolvedLanguage).format(value);
}
export function formatDuration(value: number | null | undefined) {
  if (value == null) return "—";
  if (value < 1000) return i18n.t("duration.ms", { value });
  const seconds = Math.round(value / 1000);
  return seconds < 60 ? i18n.t("duration.seconds", { value: seconds })
    : i18n.t("duration.minutes", { minutes: Math.floor(seconds / 60), seconds: seconds % 60 });
}
export function formatBytes(value: number) {
  const [amount, unit] = value < 1024 ? [value, "B"] : value < 1024 ** 2 ? [value / 1024, "KB"] : [value / 1024 ** 2, "MB"];
  return `${new Intl.NumberFormat(i18n.resolvedLanguage, { minimumFractionDigits: unit === "B" ? 0 : 1, maximumFractionDigits: unit === "B" ? 0 : 1 }).format(amount as number)} ${unit}`;
}
export function shortId(value: string | null) {
  return value ? `${value.slice(0, 10)}…` : "—";
}
export function plural(count: number, unit: "record" | "loaded run" | "scenario" | "artifact" | "JAR") {
  const keys = { record: "record", "loaded run": "loadedRun", scenario: "scenario", artifact: "artifact", JAR: "jar" };
  return i18n.t(`count.${keys[unit]}`, { count });
}
