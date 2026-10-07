import type {
  Adapter,
  CaseResult,
  Catalog,
  Connection,
  Driver,
  Page,
  Probe,
  Run,
} from "@/types/api";

export const driver: Driver = {
  id: "driver-1",
  name: "PostgreSQL JDBC",
  vendor: "PostgreSQL",
  version: "42.7.10",
  status: "VALIDATED",
  driverClass: "org.postgresql.Driver",
  urlPrefix: "jdbc:postgresql:",
  sourceType: "UPLOAD",
  packageFingerprint: "abcdef0123456789",
  createdAt: "2026-10-01T01:00:00Z",
  updatedAt: "2026-10-01T01:00:00Z",
  artifacts: [
    {
      id: "artifact-1",
      fileName: "postgresql.jar",
      size: 1024,
      sha256: "abcdef",
    },
  ],
};
export const connection: Connection = {
  id: "connection-1",
  name: "Test database",
  databaseType: "AcmeDB",
  adapterId: "postgresql",
  jdbcUrl: "jdbc:postgresql://localhost/test",
  username: "tester",
  hasSavedPassword: true,
  defaultDriver: driver,
  description: null,
  lastConnectionTestAt: null,
  lastConnectionTestStatus: null,
  createdAt: driver.createdAt,
  updatedAt: driver.updatedAt,
};
export const adapters: Adapter[] = [
  { id: "postgresql", name: "PostgreSQL", status: "OFFICIAL" },
  { id: "vendor", name: "VendorDB", status: "CANDIDATE" },
];
export const catalog: Catalog = {
  profiles: [
    { id: "CORE", name: "Core", scenarioCount: 136 },
    { id: "FULL", name: "Full", scenarioCount: 202 },
  ],
  modules: [
    {
      id: "connection",
      name: "Connection",
      description: null,
      scenarioCount: 17,
      coreScenarioCount: 17,
    },
    {
      id: "statement",
      name: "Statement",
      description: null,
      scenarioCount: 23,
      coreScenarioCount: 23,
    },
  ],
  scenarios: [],
  totalScenarioCount: 202,
};
export const run: Run = {
  id: "run-1",
  status: "RUNNING",
  profile: "CUSTOM",
  connection: {
    name: connection.name,
    databaseType: connection.databaseType,
    adapterId: connection.adapterId,
    jdbcUrl: connection.jdbcUrl,
  },
  driver,
  selectedModules: ["statement"],
  totalCases: 23,
  completedCases: 0,
  passed: 0,
  failed: 0,
  skipped: 0,
  currentModule: null,
  currentTestCase: null,
  createdAt: driver.createdAt,
  startedAt: driver.createdAt,
  finishedAt: null,
  durationMs: null,
  failureReason: null,
};
export const finishedRun: Run = {
  ...run,
  status: "SUCCEEDED",
  completedCases: 23,
  passed: 23,
  finishedAt: "2026-10-01T01:00:02Z",
  durationMs: 2000,
};
export const result: CaseResult = {
  id: "result-1",
  scenarioId: "statement.execute",
  moduleId: "statement",
  displayName: "Execute statement",
  status: "FAILED",
  durationMs: 42,
  message: "<script>unsafe</script>",
  stackTrace: "sanitized trace",
  startedAt: run.startedAt,
  finishedAt: finishedRun.finishedAt,
};
export const probe: Probe = {
  success: true,
  databaseProductName: "PostgreSQL",
  databaseProductVersion: "16",
  driverName: "PostgreSQL JDBC Driver",
  driverVersion: "42.7.10",
  jdbcMajorVersion: 4,
  jdbcMinorVersion: 2,
};
export function page<T>(items: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return {
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    totalPages: items.length ? 1 : 0,
    ...overrides,
  };
}
