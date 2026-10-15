import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { connection, page } from "@/test/fixtures";
import { json, mockApi, renderPage } from "@/test/harness";
import { chooseOption } from "@/test/select";

describe("Run adapter filtering", () => {
  it("requests filtering before pagination and resets the page on adapter changes", async () => {
    const user = userEvent.setup();
    const api = mockApi(url => url.pathname === "/api/connections" ? json(page([connection], {
      page: Number(url.searchParams.get("page")), totalElements: 25, totalPages: 3,
    })) : undefined);
    renderPage("/runs/new");
    await user.click(await screen.findByRole("button", { name: "Next page" }));
    await waitFor(() => expect(api.mock.calls.some(([u]) => String(u).includes("page=1"))).toBe(true));
    await chooseOption(user, screen.getByLabelText("Filter connections by database adapter"), "postgresql");
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
    await chooseOption(user, screen.getByLabelText("Filter connections by database adapter"), "vendor");
    expect(await screen.findByText("No matching connections")).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent(connection.name);
    expect(screen.getByRole("button", { name: "Next: Choose driver" })).toBeDisabled();
    expect(screen.getByRole("link", { name: "Add connection" })).toHaveAttribute("href", "/connections/new");
    await user.click(screen.getByRole("button", { name: "Clear adapter filter" }));
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
    await user.type(await screen.findByLabelText("Password for this run"), "temporary-only");
    await user.click(screen.getByRole("button", { name: /Connection$/ }));
    await user.click(screen.getByRole("radio", { name: second.name }));
    await user.click(screen.getByRole("button", { name: "Next: Choose driver" }));
    expect(await screen.findByLabelText("Password for this run")).toHaveValue("");
  });
});
