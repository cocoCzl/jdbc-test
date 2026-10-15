export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
export type DriverStatus = "PENDING_VALIDATION" | "VALIDATED" | "INVALID";
export type RunStatus =
  | "QUEUED"
  | "PREPARING"
  | "PROBING"
  | "RUNNING"
  | "GENERATING_REPORT"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "INTERRUPTED";
export type ResultStatus = "PASSED" | "FAILED" | "SKIPPED" | "ABORTED";
export type ConnectionTestStatus = "SUCCEEDED" | "FAILED";
export interface Artifact {
  id: string;
  fileName: string;
  size: number;
  sha256: string;
}
export interface DriverSummary {
  id: string;
  name: string;
  vendor: string;
  version: string;
  status: DriverStatus;
}
export interface DataSourceProvider {
  className: string;
  urlProperty?: string;
  userProperty?: string;
  passwordProperty?: string;
}
export interface DataSourceProviders {
  dataSource?: DataSourceProvider | null;
  connectionPoolDataSource?: DataSourceProvider | null;
  xaDataSource?: DataSourceProvider | null;
}
export interface Driver extends DriverSummary {
  dataSources?: DataSourceProviders;
  driverClass: string;
  urlPrefix: string | null;
  sourceType: "BUILTIN" | "UPLOAD" | "MAVEN";
  packageFingerprint: string | null;
  createdAt: string;
  updatedAt: string;
  artifacts: Artifact[];
}
export interface DriverInput {
  dataSources?: DataSourceProviders;
  name: string;
  vendor: string;
  version: string;
  driverClass?: string;
  urlPrefix?: string;
}
export interface DriverValidation {
  status: DriverStatus;
  driverClass: string;
  majorVersion: number;
  minorVersion: number;
  jdbcCompliant: boolean;
}
export interface Connection {
  executionDatabase?: string | null;
  executionSchema?: string | null;
  id: string;
  name: string;
  databaseType: string;
  adapterId: string;
  jdbcUrl: string;
  username: string;
  hasSavedPassword: boolean;
  defaultDriver: DriverSummary | null;
  description: string | null;
  lastConnectionTestAt: string | null;
  lastConnectionTestStatus: ConnectionTestStatus | null;
  createdAt: string;
  updatedAt: string;
}
export interface ConnectionInput {
  executionDatabase?: string;
  executionSchema?: string;
  name: string;
  databaseType: string;
  adapterId: string;
  jdbcUrl: string;
  username: string;
  defaultDriverId: string;
  description: string;
  password?: string;
  savePassword?: boolean;
  clearSavedPassword?: boolean;
}
export interface DraftProbe {
  databaseType: string;
  adapterId: string;
  jdbcUrl: string;
  username: string;
  password?: string;
  driverId: string;
}
export interface Probe {
  success: boolean;
  databaseProductName: string | null;
  databaseProductVersion: string | null;
  driverName: string | null;
  driverVersion: string | null;
  jdbcMajorVersion: number | null;
  jdbcMinorVersion: number | null;
}
export interface Adapter {
  id: string;
  name: string;
  status: string;
}
export interface CatalogModule {
  id: string;
  name: string;
  description: string | null;
  scenarioCount: number;
  coreScenarioCount: number;
}
export interface Catalog {
  profiles: { id: string; name: string; scenarioCount: number }[];
  modules: CatalogModule[];
  scenarios: {
    id: string;
    moduleId: string;
    displayName: string;
    profile: string;
  }[];
  totalScenarioCount: number;
}
export interface ExecutionAccounting {
  selected: number; started: number; completed: number; passed: number; failed: number;
  skipped: number; aborted: number; pending: number; running: number; notRun: number; incomplete: number;
  scenarios: { scenarioId: string; moduleId: string; state: string; startedAt: string | null; finishedAt: string | null }[];
}
export interface CleanupEvidence {
  status: "SUCCESS" | "FAILED" | "UNKNOWN" | "NOT_EXECUTED";
  attempted?: number;
  confirmedDeleted?: number;
  retainedObjects?: Record<string, unknown>[];
}
export interface Run {
  cleanupEvidence?: CleanupEvidence;
  accounting?: ExecutionAccounting | null;
  executionScope?: { database?: string | null; schema?: string | null } | null;
  executionIssues?: Record<string, unknown>[];
  id: string;
  status: RunStatus;
  profile: string;
  connection: {
    name: string;
    databaseType: string;
    adapterId: string;
    jdbcUrl: string;
  };
  driver: {
    id: string;
    name: string;
    vendor: string;
    version: string;
    driverClass: string;
    packageFingerprint: string | null;
  };
  selectedModules: string[];
  totalCases: number;
  completedCases: number;
  passed: number;
  failed: number;
  skipped: number;
  currentModule: string | null;
  currentTestCase: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  failureReason: string | null;
}
export interface CreateRun {
  connectionId: string;
  driverId: string;
  profile: "CORE" | "FULL" | "CUSTOM";
  modules: string[];
  destructiveConsent: boolean;
  password?: string;
}
export interface CaseResult {
  id: string;
  scenarioId: string;
  moduleId: string;
  displayName: string;
  status: ResultStatus;
  durationMs: number | null;
  message: string | null;
  stackTrace: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}
export interface RunReport {
  summary: Run;
  failures: CaseResult[];
}
export interface PageParams {
  page?: number;
  size?: number;
  sort?: string;
}
export interface RunParams extends PageParams {
  status?: RunStatus;
}
export interface ResultParams extends PageParams {
  status?: ResultStatus;
  moduleId?: string;
}
