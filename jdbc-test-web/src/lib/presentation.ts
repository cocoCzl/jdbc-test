import i18n from "@/i18n";

// Display-only mapping. Payloads, snapshots and cached records keep their original IDs.
const moduleKeys = new Set(["advanced-type", "blob-clob", "callable-statement", "connection", "database-metadata", "datasource", "jdbc-type", "lifecycle", "parameter-metadata", "prepared-statement", "result-set", "result-set-metadata", "rowset", "savepoint", "sqlxml", "statement", "wrapper"]);
export function moduleName(id: string, backendName = id) {
  return moduleKeys.has(id) ? i18n.t(`module.${id}`) : backendName;
}
export function profileName(id: string, backendName = id) {
  return ["CORE", "FULL", "CUSTOM"].includes(id) ? i18n.t(`profile.${id}`) : backendName;
}
export function adapterStatus(status: string) {
  const key = `adapter.${status}`;
  return i18n.exists(key) ? i18n.t(key) : status;
}
