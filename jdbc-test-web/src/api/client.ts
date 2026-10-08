import i18n from "@/i18n";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly detail?: string,
    public readonly properties: Record<string, unknown> = {},
  ) {
    super(detail ?? code);
    this.name = "ApiError";
  }
}
function object(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return i18n.t("ui.somethingWentWrongPleaseTryAgain");
  const key = `errors.${error.code}`;
  const message = i18n.exists(key) ? i18n.t(key) : i18n.t("errors.unknown", { detail: error.detail ?? "" });
  return `${message} (${error.code})`;
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
