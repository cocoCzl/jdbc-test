const dateTime = new Intl.DateTimeFormat("en-CA", {
  year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
});
const number = new Intl.NumberFormat();
export function formatDateTime(value: string | null | undefined) {
  if (!value) return "—";
  const parts = Object.fromEntries(dateTime.formatToParts(new Date(value)).map(({type, value}) => [type, value]));
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
}
export function formatNumber(value: number | null | undefined) {
  return value == null ? "—" : number.format(value);
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

export function plural(count: number, singular: string, pluralForm = `${singular}s`) {
  return `${count} ${count === 1 ? singular : pluralForm}`;
}
