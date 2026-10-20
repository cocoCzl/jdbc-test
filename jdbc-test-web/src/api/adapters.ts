import { request } from "./client";
import type { Adapter } from "@/types/api";
export const adapterKeys = { all: ["adapters"] as const };
export const adaptersApi = {
  import: (file: File, expectedFingerprint?: string) => {
    const body = new FormData();
    body.append("file", file);
    const query = expectedFingerprint ? `?expectedFingerprint=${encodeURIComponent(expectedFingerprint)}` : "";
    return request<Adapter>(`/adapters${query}`, { method: "POST", body });
  },
  delete: (id: string) => request<void>(`/adapters/${encodeURIComponent(id)}`, { method: "DELETE" }),
  list: (signal?: AbortSignal) => request<Adapter[]>("/adapters", { signal }),
};
