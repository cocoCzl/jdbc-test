const dateTime = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
});
const number = new Intl.NumberFormat();
export function formatDateTime(value: string | null | undefined) {
  return value ? dateTime.format(new Date(value)) : "—";
}
export function formatNumber(value: number) {
  return number.format(value);
}
export function formatDuration(value: number | null | undefined) {
  if (value == null) return "—";
  if (value < 1000) return `${value} ms`;
  const seconds = Math.round(value / 1000);
  return seconds < 60
    ? `${seconds}s`
    : `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}
export function formatBytes(value: number) {
  return value < 1024
    ? `${value} B`
    : value < 1024 ** 2
      ? `${(value / 1024).toFixed(1)} KB`
      : `${(value / 1024 ** 2).toFixed(1)} MB`;
}
export function shortId(value: string | null) {
  return value ? `${value.slice(0, 10)}…` : "—";
}
