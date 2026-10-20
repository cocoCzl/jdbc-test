import i18n from "@/i18n";

// Display-only mapping. Payloads, snapshots and cached records keep their original IDs.
const moduleKeys = new Set(["sql_feature", "advanced-type", "blob-clob", "callable-statement", "connection", "database-metadata", "datasource", "jdbc-type", "lifecycle", "parameter-metadata", "prepared-statement", "result-set", "result-set-metadata", "rowset", "savepoint", "sqlxml", "statement", "wrapper"]);
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

export function runSelectionName(run: {profile: string; selectedModules: string[]}) {
  if (run.profile === "CORE" || run.profile === "FULL") return i18n.t(`runTitle.${run.profile}`);
  if (run.profile === "CUSTOM") return run.selectedModules.length === 1
    ? moduleShortName(run.selectedModules[0])
    : i18n.t("runTitle.CUSTOM");
  return profileName(run.profile);
}
export function runTitle(run: {profile: string; selectedModules: string[]; connection: {name: string}}) {
  return `${runSelectionName(run)} · ${run.connection.name}`;
}

export function moduleShortName(id: string) {
  return moduleKeys.has(id) ? i18n.t(`moduleShort.${id}`) : id;
}
