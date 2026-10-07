import { request } from "./client";
import type { Adapter } from "@/types/api";
export const adapterKeys = { all: ["adapters"] as const };
export const adaptersApi = {
  list: (signal?: AbortSignal) => request<Adapter[]>("/adapters", { signal }),
};
