import { queryString, request } from "./client";
import type {
  Connection,
  ConnectionInput,
  DraftProbe,
  Page,
  ConnectionParams,
  Probe,
} from "@/types/api";
export const connectionsApi = {
  list: (params: ConnectionParams, signal?: AbortSignal) =>
    request<Page<Connection>>(`/connections${queryString(params)}`, { signal }),
  get: (id: string, signal?: AbortSignal) =>
    request<Connection>(`/connections/${id}`, { signal }),
  create: (input: ConnectionInput) =>
    request<Connection>("/connections", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  update: (id: string, input: ConnectionInput) =>
    request<Connection>(`/connections/${id}`, {
      method: "PATCH",
      body: JSON.stringify(input),
    }),
  testDraft: (input: DraftProbe) =>
    request<Probe>("/connections/test", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  testSaved: (id: string, password?: string) =>
    request<Probe>(`/connections/${id}/test`, {
      method: "POST",
      body: JSON.stringify(password === undefined ? {} : { password }),
    }),
  delete: (id: string) =>
    request<void>(`/connections/${id}`, { method: "DELETE" }),
};
export const connectionsKeys = {
  all: ["connections"] as const,
  list: (params: ConnectionParams) => ["connections", "list", params] as const,
  detail: (id: string) => ["connections", "detail", id] as const,
};
