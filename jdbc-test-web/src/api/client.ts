const messages: Record<string, string> = {
  DRIVER_NOT_FOUND: "This driver package no longer exists.",
  DRIVER_IN_USE:
    "This driver is referenced by a connection or historical run and cannot be deleted.",
  DRIVER_CLASS_AMBIGUOUS:
    "More than one driver class was found. Enter a driver class and upload again.",
  DRIVER_VALIDATION_FAILED:
    "The driver could not be validated. Check the class and package dependencies.",
  CONNECTION_NOT_FOUND: "This connection no longer exists.",
  CONNECTION_IN_USE:
    "This connection has historical test runs and cannot be deleted.",
  CONNECTION_PASSWORD_REQUIRED:
    "Provide a password for this connection or run.",
  CONNECTION_TIMEOUT: "The connection test timed out.",
  CONNECTION_FAILED: "The connection test failed.",
  DESTRUCTIVE_CONSENT_REQUIRED:
    "Confirm that you understand these tests may modify database objects.",
  INVALID_MODULE: "A selected module is unavailable. Refresh the test catalog.",
  INVALID_SCENARIO: "A selected scenario is unavailable.",
  RUN_TIMEOUT: "The run exceeded its time limit.",
  WORKER_PROTOCOL_ERROR: "The worker returned an invalid event.",
  RUN_ALREADY_FINISHED:
    "This run has already finished. Its latest state has been refreshed.",
  REPORT_INCONSISTENT:
    "The worker report does not agree with the persisted results.",
  RUN_NOT_FOUND: "This test run no longer exists.",
  RUN_NOT_FINISHED: "The report is available when the run finishes.",
  REPORT_NOT_FOUND: "No raw report was produced for this run.",
  NETWORK_ERROR:
    "Cannot reach the server. Check the backend and your connection.",
  INTERNAL_ERROR: "The server could not complete the request.",
};

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    detail?: string,
    public readonly properties: Record<string, unknown> = {},
  ) {
    super(messages[code] ?? detail ?? "The request could not be completed.");
    this.name = "ApiError";
  }
}
function object(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
export function errorMessage(error: unknown): string {
  return error instanceof ApiError
    ? `${error.message} (${error.code})`
    : "Something went wrong. Please try again.";
}
export function queryString(params: object): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params))
    if (value !== undefined && value !== "") search.set(key, String(value));
  return search.size ? `?${search}` : "";
}
export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(
      `${import.meta.env.VITE_API_BASE_URL ?? "/api"}${path}`,
      {
        ...options,
        headers: {
          Accept: "application/json",
          ...(options.body && !(options.body instanceof FormData)
            ? { "Content-Type": "application/json" }
            : {}),
          ...options.headers,
        },
      },
    );
  } catch (error) {
    if (object(error) && error.name === "AbortError") throw error;
    throw new ApiError(0, "NETWORK_ERROR");
  }
  if (response.status === 204) return undefined as T;
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new ApiError(
      response.status,
      response.ok ? "INVALID_RESPONSE" : "HTTP_ERROR",
      "The server returned an unreadable response.",
    );
  }
  if (!response.ok) {
    const problem = object(body) ? body : {};
    throw new ApiError(
      response.status,
      typeof problem.code === "string" ? problem.code : "HTTP_ERROR",
      typeof problem.detail === "string" ? problem.detail : undefined,
      object(problem.properties) ? problem.properties : {},
    );
  }
  return body as T;
}
