import { describe, expect, it, vi } from "vitest";
import { ApiError, queryString, request } from "./client";
import { driversApi } from "./drivers";
import { runsApi } from "./runs";
import { driver, run } from "@/test/fixtures";
import { json, problem } from "@/test/harness";

describe("API client", () => {
  it("parses a resource directly and passes AbortSignal", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(json(driver));
    const signal = new AbortController().signal;
    expect(await driversApi.get(driver.id, signal)).toEqual(driver);
    expect(fetch).toHaveBeenCalledWith(
      "/api/drivers/driver-1",
      expect.objectContaining({ signal }),
    );
  });
  it("handles HTTP 204 without attempting JSON", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 204 }),
    );
    expect(await driversApi.delete(driver.id)).toBeUndefined();
  });
  it.each([
    ["DRIVER_IN_USE", 409],
    ["DRIVER_NOT_FOUND", 404],
    ["INVALID_MODULE", 422],
  ])("maps %s HTTP %s", async (code, status) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(problem(code, status));
    await expect(request("/resource")).rejects.toMatchObject({ status, code });
  });
  it("retains backend driver class candidates", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      problem("DRIVER_CLASS_AMBIGUOUS", 422, {
        candidates: ["a.Driver", "b.Driver"],
      }),
    );
    await expect(request("/drivers")).rejects.toMatchObject({
      properties: { candidates: ["a.Driver", "b.Driver"] },
    });
  });
  it("maps network failures without echoing request secrets", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(
      new Error("password=private"),
    );
    await expect(request("/connections")).rejects.toEqual(
      new ApiError(0, "NETWORK_ERROR"),
    );
  });
  it("preserves AbortError", async () => {
    const failure = new DOMException("Cancelled", "AbortError");
    vi.spyOn(globalThis, "fetch").mockRejectedValue(failure);
    await expect(request("/runs")).rejects.toBe(failure);
  });
  it("rejects malformed server JSON", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("not-json"));
    await expect(request("/runs")).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
    });
  });
  it("sends multipart metadata and repeated JAR parts without forcing Content-Type", async () => {
    const fetch = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(json(driver, 201));
    await driversApi.upload(
      { name: "Package", vendor: "Vendor", version: "1" },
      [new File(["one"], "one.jar"), new File(["two"], "two.jar")],
    );
    const options = fetch.mock.calls[0][1]!;
    const body = options.body as FormData;
    expect(body.getAll("files")).toHaveLength(2);
    expect(body.get("metadata")).toBeInstanceOf(Blob);
    expect(options.headers).not.toHaveProperty("Content-Type");
  });
  it("places temporary Run credentials only in the POST body, never URL or storage", async () => {
    const fetch = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(json(run, 201));
    await runsApi.create({
      connectionId: "connection-1",
      driverId: "driver-1",
      profile: "CUSTOM",
      modules: ["statement"],
      destructiveConsent: true,
      password: "private",
    });
    expect(fetch.mock.calls[0][0]).toBe("/api/runs");
    expect(JSON.parse(fetch.mock.calls[0][1]!.body as string)).toHaveProperty(
      "password",
      "private",
    );
    expect(localStorage.length + sessionStorage.length).toBe(0);
  });
  it("encodes query parameters and omits empty filters", () => {
    expect(
      queryString({ status: "", page: 0, sort: "name,asc", absent: undefined }),
    ).toBe("?page=0&sort=name%2Casc");
  });
});
