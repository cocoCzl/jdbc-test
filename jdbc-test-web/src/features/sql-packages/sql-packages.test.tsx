import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect } from "vitest";
import { json, mockApi, renderPage } from "@/test/harness";
import { chooseOption } from "@/test/select";
import { finishedRun, connection, page, adapters } from "@/test/fixtures";
import type { SqlPackage } from "@/types/api";

const bundle: SqlPackage = {
  fingerprint: "c".repeat(64), origin: "LOCAL",
  definition: { id: "sql-fixture", version: "1", description: "TEST FIXTURE ONLY",
    cases: [{ id: "basic.query", name: "Basic query", tags: ["query"],
      appliesTo: { products: ["PostgreSQL"], dialects: ["postgresql"], minMajor: 12, maxMajor: 18, modeDescription: "Default mode" },
      timeoutSeconds: 10, prerequisites: [], variants: { postgresql: {} } }] },
};

describe("SQL package workflow", () => {
  it("imports then freezes package, selected cases and adapter when creating a SQL task", async () => {
    let imported = false;
    let created: Record<string, unknown> | undefined;
    mockApi((url, options) => {
      if (url.pathname === "/api/sql-packages") {
        if (options.method === "POST") { imported = true; expect(options.body).toBeInstanceOf(FormData); return json(bundle); }
        return json(imported ? [bundle] : []);
      }
      if (url.pathname === "/api/adapters") return json(adapters.map(item => ({ ...item, fingerprint: "b".repeat(64) })));
      if (url.pathname === "/api/runs/run-1") return json({ ...finishedRun, sqlPackage: { manifest: "fixture", fingerprint: bundle.fingerprint } });
      if (url.pathname === "/api/connections") return json(page([{ ...connection, executionDatabase: "owned_db", executionSchema: "owned_schema" }]));
      if (url.pathname === "/api/runs" && options.method === "POST") { created = JSON.parse(String(options.body)); return json({ ...finishedRun, sqlPackage: { manifest: "fixture", fingerprint: bundle.fingerprint } }, 201); }
      return undefined;
    });
    renderPage("/sql-packages");
    const user = userEvent.setup();
    await user.upload(screen.getByLabelText(/SQL package JSON/), new File(["fixture"], "sql-package.json", { type: "application/json" }));
    await user.click(screen.getByRole("button", { name: "Validate and import SQL package" }));
    expect(await screen.findByText(/Definition validated and saved/)).toBeVisible();
    await chooseOption(user, screen.getByLabelText("Test package revision"), bundle.fingerprint);
    expect(await screen.findByRole("checkbox", { name: /basic.query/ })).toBeVisible();
    await user.click(screen.getByRole("checkbox", { name: /basic.query/ }));
    await chooseOption(user, screen.getByLabelText("Target connection"), connection.id);
    await user.click(screen.getByRole("checkbox", { name: /I authorize these reviewed SQL/ }));
    await user.click(screen.getByRole("button", { name: "Start SQL test run" }));
    expect(await screen.findByText("SQL feature package frozen for this task")).toBeVisible();
    expect(created).toMatchObject({ sqlPackageFingerprint: bundle.fingerprint, profile: "CUSTOM", modules: ["sql_feature"], includeScenarios: ["basic.query"], destructiveConsent: true });
    expect(created?.expectedAdapterFingerprint).toBeTruthy();
  });

  it("shows server field validation errors without treating upload as execution success", async () => {
    mockApi(url => url.pathname === "/api/sql-packages" ? json({ status: 422, code: "SQL_PACKAGE_INVALID", detail: "cases[].expect: unsupported FLOAT" }, 422) : undefined);
    renderPage("/sql-packages");
    expect(await screen.findAllByText(/cases\[\].expect: unsupported FLOAT/)).not.toHaveLength(0);
    expect(screen.queryByText(/Definition validated and saved/)).not.toBeInTheDocument();
  });
});
