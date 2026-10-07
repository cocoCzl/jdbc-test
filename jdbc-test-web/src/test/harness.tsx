import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { Toaster } from "sonner";
import { vi } from "vitest";
import { routes } from "@/app/router";
import {
  adapters,
  catalog,
  connection,
  driver,
  finishedRun,
  page,
  probe,
} from "./fixtures";

export type Handler = (
  url: URL,
  options: RequestInit,
) => Response | undefined | Promise<Response | undefined>;
export function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
export function problem(
  code: string,
  status = 409,
  properties: Record<string, unknown> = {},
) {
  return json(
    { code, status, detail: "Sanitized server detail", properties },
    status,
  );
}
export function mockApi(handler?: Handler) {
  return vi
    .spyOn(globalThis, "fetch")
    .mockImplementation(async (input, options = {}) => {
      const url = new URL(String(input), "http://localhost");
      const custom = await handler?.(url, options);
      if (custom) return custom;
      if (url.pathname === "/api/adapters") return json(adapters);
      if (url.pathname === "/api/test-catalog") return json(catalog);
      if (url.pathname === "/api/drivers") return json(page([driver]));
      if (url.pathname === "/api/drivers/driver-1") return json(driver);
      if (url.pathname === "/api/connections") return json(page([connection]));
      if (url.pathname === "/api/connections/connection-1")
        return json(connection);
      if (url.pathname.endsWith("/test")) return json(probe);
      if (url.pathname === "/api/runs") return json(page([finishedRun]));
      if (url.pathname === "/api/runs/run-1") return json(finishedRun);
      if (url.pathname.endsWith("/results")) return json(page([]));
      if (url.pathname.endsWith("/report"))
        return json({ summary: finishedRun, failures: [] });
      throw new Error(
        `Unhandled test request: ${options.method ?? "GET"} ${url.pathname}`,
      );
    });
}
export function renderPage(path: string) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false, gcTime: 0 },
    },
  });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
      <Toaster />
    </QueryClientProvider>,
  );
  return { ...view, client, router };
}
