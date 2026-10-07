import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "@/api/client";
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        retry: (count, error) =>
          !(
            error instanceof ApiError &&
            error.status >= 400 &&
            error.status < 500
          ) && count < 1,
      },
      mutations: { retry: false, gcTime: 0 },
    },
  });
}
