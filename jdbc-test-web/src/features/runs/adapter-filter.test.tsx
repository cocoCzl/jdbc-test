import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { connection, page, adapters } from "@/test/fixtures";
import { json, mockApi, renderPage } from "@/test/harness";
import { chooseOption } from "@/test/select";

describe("Run adapter filtering", () => {
  it("uses compact rows without internal adapter configuration and keeps URL actions separate from selection", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname === "/api/adapters" ? json([{ ...adapters[0], version: "1.0.0", revision: "official-1",
      fingerprint: "a".repeat(64), matchConditions: { expected_database_product_regex: "^FixtureDB$" } }]) : undefined);
    renderPage("/runs/new");
    const radio = await screen.findByRole("radio", { name: connection.name });
    await user.click(screen.getByRole("button", { name: "View URL" }));
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "Copy sanitized URL" }));
    expect(radio).not.toBeChecked();
    await user.click(screen.getByText(connection.databaseType));
    expect(radio).toBeChecked();
    expect(screen.queryByText("Adapter details")).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain("official-1");
    expect(document.body.textContent).not.toContain("^FixtureDB$");
    expect(document.querySelector(".wizard-current-connection")).toHaveTextContent(connection.name);
    expect(screen.queryByText(/Last test:/)).not.toBeInTheDocument();
    expect(document.querySelector(".run-connection-row time")).toBeNull();
  });

  it("reviews the connection's effective dialect and never substitutes the selected driver's default", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname === "/api/connections" ? json(page([{ ...connection, sqlDialect: "postgresql",
      defaultDriver: { ...connection.defaultDriver!, defaultSqlDialect: "oracle" } }])) : undefined);
    renderPage("/runs/new");
    await user.click(await screen.findByRole("radio", { name: connection.name }));
    await user.click(screen.getByRole("button", { name: "Next: Choose driver" }));
    await user.click(screen.getByRole("button", { name: "Next: Select tests" }));
    await user.click(screen.getByRole("button", { name: "Next: Review run" }));
    const panel = screen.getByRole("heading", { name: "Review before you run" }).closest("section")!;
    expect(within(panel).getByText("SQL dialect: postgresql")).toBeVisible();
    expect(within(panel).queryByText("Adapter details")).not.toBeInTheDocument();
  });
  it("offers add for an empty collection and clear for an empty filter, without empty pagination", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname === "/api/connections" ? json(page([])) : undefined);
    renderPage("/runs/new");
    expect(await screen.findByText("No connections yet")).toBeVisible();
    expect(screen.getByRole("link", { name: "Add connection" })).toHaveAttribute("href", "/connections/new");
    expect(screen.queryByText(/Page 1 of/)).not.toBeInTheDocument();
    await chooseOption(user, screen.getByLabelText("Filter connections"), "vendor");
    expect(await screen.findByText("No matching connections")).toBeVisible();
    expect(screen.queryByRole("link", { name: "Add connection" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Clear filter" }));
    expect(await screen.findByText("No connections yet")).toBeVisible();
    expect(screen.queryByText(/Page 1 of/)).not.toBeInTheDocument();
  });

  it("requests filtering before pagination and resets the page on adapter changes", async () => {
    const user = userEvent.setup();
    const api = mockApi(url => url.pathname === "/api/connections" ? json(page([connection], {
      page: Number(url.searchParams.get("page")), totalElements: 25, totalPages: 3,
    })) : undefined);
    renderPage("/runs/new");
    await user.click(await screen.findByRole("button", { name: "Next page" }));
    await waitFor(() => expect(api.mock.calls.some(([u]) => String(u).includes("page=1"))).toBe(true));
    await chooseOption(user, screen.getByLabelText("Filter connections"), "postgresql");
    await waitFor(() => expect(api.mock.calls.some(([u]) => {
      const url = new URL(String(u), "http://localhost");
      return url.pathname === "/api/connections" && url.searchParams.get("page") === "0"
        && url.searchParams.get("adapterId") === "postgresql";
    })).toBe(true));
    expect(await screen.findByText(/Page 1 of 3/)).toBeVisible();
  });

  it("keeps a deep-linked connection explicit when a filter conflicts", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname === "/api/connections" && url.searchParams.get("adapterId") === "vendor"
      ? json(page([])) : undefined);
    renderPage("/runs/new?connectionId=connection-1");
    expect(await screen.findByRole("radio", { name: connection.name })).toBeChecked();
    await chooseOption(user, screen.getByLabelText("Filter connections"), "vendor");
    expect(await screen.findByText("No matching connections")).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent(connection.name);
    expect(screen.getByRole("button", { name: "Next: Choose driver" })).toBeDisabled();
    expect(screen.queryByRole("link", { name: "Add connection" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Clear filter" }));
    expect(await screen.findByRole("radio", { name: connection.name })).toBeChecked();
    expect(screen.getByRole("button", { name: "Next: Choose driver" })).toBeEnabled();
  });

  it("clears temporary credentials when switching connections after filtering", async () => {
    const user = userEvent.setup();
    const second = { ...connection, id: "second", name: "Second target", hasSavedPassword: false };
    mockApi(url => url.pathname === "/api/connections"
      ? json(page([{ ...connection, hasSavedPassword: false }, second])) : undefined);
    renderPage("/runs/new");
    await user.click(await screen.findByRole("radio", { name: connection.name }));
    await user.click(screen.getByRole("button", { name: "Next: Choose driver" }));
    await user.type(await screen.findByLabelText("Password"), "temporary-only");
    await user.click(screen.getByRole("button", { name: /Connection$/ }));
    await user.click(screen.getByRole("radio", { name: second.name }));
    await user.click(screen.getByRole("button", { name: "Next: Choose driver" }));
    expect(await screen.findByLabelText("Password")).toHaveValue("");
  });
});
