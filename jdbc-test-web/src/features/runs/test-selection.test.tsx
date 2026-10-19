import { render, screen, within, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { catalog, connection, driver, run } from "@/test/fixtures";
import { json, mockApi, renderPage } from "@/test/harness";
import { TestSelection } from "./test-selection";
import { reconcileSelection } from "./test-selection-state";

function Fixture() {
  const [selected, setSelected] = useState(new Set(catalog.scenarios.map(item => item.id)));
  return <TestSelection catalog={catalog} selected={selected} onChange={setSelected} />;
}

describe("Exact test scope", () => {
  it("preserves hidden exclusions through search, collapse and module selection", async () => {
    const user = userEvent.setup();
    render(<Fixture />);
    expect(screen.queryByRole("region", { name: "Vendor extension tests" })).not.toBeInTheDocument();
    const connectionModule = screen.getByRole("checkbox", { name: "Connection" });
    const details = connectionModule.closest("div.rounded-xl")!;
    expect(details.querySelector("details")).not.toHaveAttribute("open");
    await user.click(within(details as HTMLElement).getByText("Show / hide cases"));
    await user.click(screen.getByRole("checkbox", { name: /Connection case 0.*connection.case_0/ }));
    expect(connectionModule).toBePartiallyChecked();
    await user.type(screen.getByRole("searchbox"), "statement.case_1");
    expect(screen.queryByRole("checkbox", { name: "Connection" })).not.toBeInTheDocument();
    await user.clear(screen.getByRole("searchbox"));
    expect(screen.getByRole("checkbox", { name: "Connection" })).toBePartiallyChecked();
    expect(screen.getByRole("checkbox", { name: /Connection case 0.*connection.case_0/ })).not.toBeChecked();
    await user.click(screen.getByRole("button", { name: "Restore defaults" }));
    expect(screen.getByRole("checkbox", { name: "Connection" })).toBeChecked();
  });

  it("preserves applicable selections and defaults newly applicable cases on target changes", () => {
    const previous = catalog.scenarios;
    const selected = new Set(["statement.case_0", "old.vendor"]);
    const next = [...previous, { id: "new.vendor", moduleId: "statement", profile: "FULL", displayName: "New extension" }];
    expect([...reconcileSelection(previous, selected, next)]).toEqual(["statement.case_0", "new.vendor"]);
  });

  it("refetch cannot overwrite a partial selection and submits exact IDs with exclusions", async () => {
    const user = userEvent.setup();
    const api = mockApi((url, options) => url.pathname === "/api/runs" && options.method === "POST" ? json(run, 201) : undefined);
    const { client } = renderPage("/runs/new?connectionId=connection-1");
    await user.click(await screen.findByRole("button", { name: "Next: Choose driver" }));
    await user.click(await screen.findByRole("button", { name: "Next: Select tests" }));
    const module = await screen.findByRole("checkbox", { name: "Connection" });
    await user.click(within(module.closest("div.rounded-xl") as HTMLElement).getByText("Show / hide cases"));
    await user.click(screen.getByRole("checkbox", { name: /Connection case 0.*connection.case_0/ }));
    await client.invalidateQueries({ queryKey: ["catalog"] });
    expect(screen.getByRole("checkbox", { name: "Connection" })).toBePartiallyChecked();
    expect(screen.getByText("39 scenarios selected")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Next: Review run" }));
    await user.click(screen.getByRole("checkbox", { name: /I understand/ }));
    await user.click(screen.getByRole("button", { name: "Start test run" }));
    await waitFor(() => expect(api.mock.calls.some(([, options]) => options?.method === "POST")).toBe(true));
    const call = api.mock.calls.find(([, options]) => options?.method === "POST")!;
    const input = JSON.parse(call[1]!.body as string);
    expect(input.includeScenarios).toHaveLength(39);
    expect(input.excludeScenarios).toEqual(["connection.case_0"]);
    expect(api.mock.calls.some(([url]) => String(url).includes(`adapterId=${connection.adapterId}`))).toBe(true);
    expect(client.getQueryCache().find({ queryKey: ["catalog", connection.id, connection.adapterId, driver.id] })).toBeDefined();
  });
});
