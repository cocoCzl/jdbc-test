import { request } from "./client";
import type { Catalog } from "@/types/api";
export const catalogKeys = { all: ["catalog"] as const, target: (connectionId: string, adapterId: string, driverId: string) => ["catalog", connectionId, adapterId, driverId] as const };
export const catalogApi = {
  get: (signal?: AbortSignal, adapterId?: string) => request<Catalog>(`/test-catalog${adapterId ? `?adapterId=${encodeURIComponent(adapterId)}` : ""}`, { signal }),
};
