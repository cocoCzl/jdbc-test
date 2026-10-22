import { ApiError, queryString, request } from "./client";
import type {
  Driver,
  DriverInput,
  DriverPreset,
  DriverValidation,
  Page,
  PageParams,
} from "@/types/api";
export const driversApi = {
  presets: (signal?: AbortSignal) =>
    request<DriverPreset[]>("/drivers/presets", { signal }),
  list: (params: PageParams, signal?: AbortSignal) =>
    request<Page<Driver>>(`/drivers${queryString(params)}`, { signal }),
  get: (id: string, signal?: AbortSignal) =>
    request<Driver>(`/drivers/${id}`, { signal }),
  upload: async (metadata: DriverInput, files: File[]) => {
    const body = new FormData();
    body.append(
      "metadata",
      new Blob([JSON.stringify(metadata)], { type: "application/json" }),
    );
    files.forEach((file) => body.append("files", file));
    const driver = await request<Driver>("/drivers", { method: "POST", body });
    if (metadata.defaultSqlDialect && driver.defaultSqlDialect !== metadata.defaultSqlDialect) {
      throw new ApiError(200, "DRIVER_DIALECT_NOT_SAVED", undefined, { driverId: driver.id });
    }
    return driver;
  },
  update: async (id: string, input: DriverInput) => {
    const driver = await request<Driver>(`/drivers/${id}`, {
      method: "PATCH",
      body: JSON.stringify(input),
    });
    if (input.defaultSqlDialect !== undefined
      && (driver.defaultSqlDialect ?? "") !== input.defaultSqlDialect) {
      throw new ApiError(200, "DRIVER_DIALECT_UPDATE_FAILED");
    }
    return driver;
  },
  validate: async (id: string) => {
    const value = await request<DriverValidation>(`/drivers/${id}/validate`, { method: "POST" });
    const version = (part: unknown) => typeof part === "number" && Number.isInteger(part)
      && part >= 0 && part <= 2_147_483_647;
    const text = (part: unknown) => part == null || (typeof part === "string" && part.trim() !== "");
    const artifact = value?.loadedArtifact;
    if (!value || value.status !== "VALIDATED" || typeof value.driverClass !== "string"
      || !value.driverClass.trim() || !version(value.majorVersion) || !version(value.minorVersion)
      || typeof value.jdbcCompliant !== "boolean"
      || (value.warnings !== undefined && (!Array.isArray(value.warnings)
        || value.warnings.some(item => typeof item !== "string")))
      || (artifact != null && (typeof artifact.fileName !== "string" || !artifact.fileName.trim()
        || typeof artifact.sha256 !== "string" || !/^[a-f0-9]{64}$/.test(artifact.sha256)
        || !text(artifact.implementationVersion) || !text(artifact.bundleVersion)))) {
      throw new ApiError(200, "INVALID_RESPONSE", "Invalid driver metadata response");
    }
    return value;
  },
  delete: (id: string) => request<void>(`/drivers/${id}`, { method: "DELETE" }),
};
export const driversKeys = {
  all: ["drivers"] as const,
  presets: ["drivers", "presets"] as const,
  list: (params: PageParams) => ["drivers", "list", params] as const,
  detail: (id: string) => ["drivers", "detail", id] as const,
};
