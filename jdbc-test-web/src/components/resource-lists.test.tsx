import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { connection, driver, page, probe } from "@/test/fixtures";
import { json, mockApi, problem, renderPage } from "@/test/harness";

const check = `Check driver files: ${driver.name}`;
const testConnection = `Test connection: ${connection.name}`;
describe("Resource list actions", () => {
  it.each(["drivers", "connections"])("hides %s IDs in names while keeping routes and folded copyable detail IDs", async resource => {
    const user = userEvent.setup();
    const clipboard = vi.spyOn(navigator.clipboard, "writeText").mockResolvedValue();
    mockApi();
    const item = resource === "drivers" ? driver : connection;
    const { router } = renderPage(`/${resource}`);
    const link = await screen.findByRole("link", { name: item.name });
    expect(link).toHaveAttribute("href", `/${resource}/${item.id}`);
    expect(screen.getByRole("table")).not.toHaveTextContent(item.id);
    await user.click(link);
    await screen.findByText("Technical information", { selector: "summary" });
    expect(router.state.location.pathname).toBe(`/${resource}/${item.id}`);
    const info = screen.getByText("Technical information", { selector: "summary" }).closest("details")!;
    expect(info).not.toHaveAttribute("open");
    expect(within(info).getByText(item.id)).not.toBeVisible();
    await user.click(within(info).getByText("Technical information"));
    expect(within(info).getByText(item.id)).toBeVisible();
    await user.click(within(info).getByRole("button", { name: "Copy Internal ID" }));
    expect(clipboard).toHaveBeenCalledWith(item.id);
  });

  it("uses labeled driver icons, shows progress and explains other disabled checks", async () => {
    const user = userEvent.setup();
    let release!: () => void;
    const pending = new Promise<void>(resolve => { release = resolve; });
    const api = mockApi(async url => {
      if (url.pathname === "/api/drivers") return json(page([driver, { ...driver, id: "other-driver", name: "Other driver" }]));
      if (url.pathname.endsWith("/validate")) { await pending; return json({ status: "VALIDATED", driverClass: driver.driverClass, majorVersion: 0, minorVersion: 0, jdbcCompliant: false }); }
    });
    renderPage("/drivers");
    let action = await screen.findByRole("button", { name: check });
    expect(action).toHaveTextContent("");
    await user.click(action);
    action = screen.getByRole("button", { name: check });
    expect(action).toBeDisabled(); expect(action).toHaveAttribute("aria-busy", "true");
    const other = screen.getByRole("button", { name: "Check driver files: Other driver" });
    expect(other).toBeDisabled();
    await user.tab();
    const reason = screen.getByRole("group", { name: "Check driver files: Other driver · Checking…" });
    act(() => reason.focus());
    expect(await screen.findByRole("tooltip")).toHaveTextContent("Checking…");
    release();
    await waitFor(() => expect(screen.getByRole("button", { name: check })).toBeEnabled());
    expect(api.mock.calls.filter(([url]) => String(url).endsWith("/validate"))).toHaveLength(1);
  });

  it("retains the connection test dialog and row progress after closing it during a request", async () => {
    const user = userEvent.setup();
    let release!: () => void;
    const pending = new Promise<void>(resolve => { release = resolve; });
    const api = mockApi(async url => {
      if (url.pathname.endsWith("/test")) { await pending; return json(probe); }
    });
    const { router } = renderPage("/connections");
    let rowTest = await screen.findByRole("button", { name: testConnection });
    await user.click(rowTest);
    expect(router.state.location.pathname).toBe("/connections");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Test connection" }));
    await user.keyboard("{Escape}");
    rowTest = screen.getByRole("button", { name: testConnection });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(rowTest).toBeDisabled(); expect(rowTest).toHaveAttribute("aria-busy", "true");
    release();
    await waitFor(() => expect(screen.getByRole("button", { name: testConnection })).toBeEnabled());
    expect(api.mock.calls.filter(([url]) => String(url).endsWith("/test"))).toHaveLength(1);
    expect(screen.getByRole("link", { name: `Start run: ${connection.name}` })).toHaveAttribute("href", `/runs/new?connectionId=${connection.id}`);
  });

  it.each(["drivers", "connections"])("retains %s delete confirmation and reference errors", async resource => {
    const user = userEvent.setup();
    const api = mockApi((_url, options) => options.method === "DELETE" ? problem(resource === "drivers" ? "DRIVER_IN_USE" : "CONNECTION_IN_USE") : undefined);
    const item = resource === "drivers" ? driver : connection;
    const { router } = renderPage(`/${resource}`);
    await user.click(await screen.findByRole("button", { name: `Delete ${item.name}` }));
    expect(router.state.location.pathname).toBe(`/${resource}`);
    expect(api.mock.calls.some(([, options]) => options?.method === "DELETE")).toBe(false);
    await user.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: resource === "drivers" ? "Delete driver" : "Delete connection" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(api.mock.calls.filter(([, options]) => options?.method === "DELETE")).toHaveLength(1);
    expect(await screen.findByRole("link", { name: item.name })).toBeVisible();
    expect(screen.getByText(resource === "drivers" ? /DRIVER_IN_USE/ : /CONNECTION_IN_USE/)).toBeVisible();
  });
});
