import { queryString, request } from "./client";
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
  upload: (metadata: DriverInput, files: File[]) => {
    const body = new FormData();
    body.append(
      "metadata",
      new Blob([JSON.stringify(metadata)], { type: "application/json" }),
    );
    files.forEach((file) => body.append("files", file));
    return request<Driver>("/drivers", { method: "POST", body });
  },
  update: (id: string, input: DriverInput) =>
    request<Driver>(`/drivers/${id}`, {
      method: "PATCH",
      body: JSON.stringify(input),
    }),
  validate: (id: string) =>
    request<DriverValidation>(`/drivers/${id}/validate`, { method: "POST" }),
  delete: (id: string) => request<void>(`/drivers/${id}`, { method: "DELETE" }),
};
export const driversKeys = {
  all: ["drivers"] as const,
  presets: ["drivers", "presets"] as const,
  list: (params: PageParams) => ["drivers", "list", params] as const,
  detail: (id: string) => ["drivers", "detail", id] as const,
};
