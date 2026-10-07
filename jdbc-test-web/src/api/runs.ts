import { queryString, request } from "./client";
import type {
  CaseResult,
  CreateRun,
  Page,
  ResultParams,
  Run,
  RunParams,
  RunReport,
} from "@/types/api";
export const runsApi = {
  list: (params: RunParams, signal?: AbortSignal) =>
    request<Page<Run>>(`/runs${queryString(params)}`, { signal }),
  get: (id: string, signal?: AbortSignal) =>
    request<Run>(`/runs/${id}`, { signal }),
  create: (input: CreateRun) =>
    request<Run>("/runs", { method: "POST", body: JSON.stringify(input) }),
  cancel: (id: string) =>
    request<void>(`/runs/${id}/cancel`, { method: "POST" }),
  results: (id: string, params: ResultParams, signal?: AbortSignal) =>
    request<Page<CaseResult>>(`/runs/${id}/results${queryString(params)}`, {
      signal,
    }),
  report: (id: string, signal?: AbortSignal) =>
    request<RunReport>(`/runs/${id}/report`, { signal }),
  rawReport: (id: string, signal?: AbortSignal) =>
    request<unknown>(`/runs/${id}/report.json`, { signal }),
};
export const runsKeys = {
  all: ["runs"] as const,
  list: (params: RunParams) => ["runs", "list", params] as const,
  detail: (id: string) => ["runs", "detail", id] as const,
  results: (id: string) => ["runs", id, "results"] as const,
  report: (id: string) => ["runs", id, "report"] as const,
};
