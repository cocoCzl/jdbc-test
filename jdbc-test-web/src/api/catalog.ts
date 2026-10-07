import { request } from "./client";
import type { Catalog } from "@/types/api";
export const catalogKeys = { all: ["catalog"] as const };
export const catalogApi = {
  get: (signal?: AbortSignal) => request<Catalog>("/test-catalog", { signal }),
};
