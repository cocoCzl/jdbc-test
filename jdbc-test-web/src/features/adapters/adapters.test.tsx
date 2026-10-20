import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { json, mockApi, renderPage } from "@/test/harness";
import { adapters } from "@/test/fixtures";
import { chooseOption } from "@/test/select";
import type { Adapter } from "@/types/api";

const imported: Adapter = { id: "private-db", name: "Private database", version: "1", revision: "r1", status: "LOCAL", origin: "IMPORTED", dialect: "postgresql", fingerprint: "a".repeat(64), validatedCombinations: [], matchConditions: { expected_database_product_regex: "PrivateDB" } };

describe("Web adapter import", () => {
  it("imports separately from drivers and opens a preselected connection form", async () => {
    const user = userEvent.setup();
    let packages = [...adapters];
    let upload: FormData | undefined;
    mockApi((url, options) => {
      if (url.pathname === "/api/adapters" && options.method === "POST") {
        upload = options.body as FormData;
        packages = [...adapters, imported];
        return json(imported);
      }
      if (url.pathname === "/api/adapters") return json(packages);
    });
    renderPage("/adapters");
    await user.upload(await screen.findByLabelText("Adapter ZIP (maximum 8 MiB unpacked)"), new File(["fixture"], "adapter.zip", { type: "application/zip" }));
    expect(screen.getByRole("button", { name: "Validate and import" })).toBeEnabled();
    expect((screen.getByLabelText("Adapter ZIP (maximum 8 MiB unpacked)") as HTMLInputElement).files).toHaveLength(1);
    const form = screen.getByRole("button", { name: "Validate and import" }).closest("form")!;
    // jsdom does not reconcile native file-input validity after userEvent.upload.
    fireEvent.submit(form);
    await screen.findByText("Format and resources validated. Source remains local; product compatibility has not been verified.");
    expect(upload?.get("file")).toBeInstanceOf(File);
    await waitFor(() => expect(screen.getAllByText("PrivateDB").length).toBeGreaterThan(0));
    const links = screen.getAllByRole("link", { name: "Add a connection" });
    const privateLink = links.find(link => link.getAttribute("href")?.includes("private-db"))!;
    await user.click(privateLink);
    expect(await screen.findByLabelText("Adapter")).toHaveTextContent("Private database");
    expect(screen.getByLabelText("Database type")).toHaveValue("private-db");
  });

  it("requires an explicit update target and displays field validation errors", async () => {
    const user = userEvent.setup();
    let submitted: URL | undefined;
    mockApi((url, options) => {
      if (url.pathname === "/api/adapters" && options.method === "POST") {
        submitted = url;
        return json({ code: "ADAPTER_PACKAGE_INVALID", detail: "ADAPTER_RESOURCE_MISSING: ddl/private/connection_ddl.sql" }, 422);
      }
      if (url.pathname === "/api/adapters") return json([imported]);
    });
    renderPage("/adapters");
    await chooseOption(user, await screen.findByLabelText("Import or explicit update"), imported.id);
    await user.upload(screen.getByLabelText("Adapter ZIP (maximum 8 MiB unpacked)"), new File(["fixture"], "update.zip", { type: "application/zip" }));
    expect(screen.getByRole("button", { name: "Validate and import" })).toBeEnabled();
    expect((screen.getByLabelText("Adapter ZIP (maximum 8 MiB unpacked)") as HTMLInputElement).files).toHaveLength(1);
    const form = screen.getByRole("button", { name: "Validate and import" }).closest("form")!;
    // jsdom does not reconcile native file-input validity after userEvent.upload.
    fireEvent.submit(form);
    await screen.findByText("ADAPTER_RESOURCE_MISSING: ddl/private/connection_ddl.sql");
    expect(submitted?.searchParams.get("expectedFingerprint")).toBe(imported.fingerprint);
  });

});
