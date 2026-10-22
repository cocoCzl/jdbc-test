import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { connection, driver, page } from "@/test/fixtures";
import { json, mockApi, renderPage } from "@/test/harness";
import { redactJdbcUrl } from "@/lib/jdbc-url";

describe("resource list presentation", () => {
  it("keeps driver controls, server count and pagination in one panel", async () => {
    const user = userEvent.setup();
    const api = mockApi(url => url.pathname === "/api/drivers"
      ? json(page([driver], { totalElements: 21, totalPages: 2, page: Number(url.searchParams.get("page")) }))
      : undefined);
    renderPage("/drivers");
    const table = await screen.findByRole("table");
    const panel = table.closest("section")!;
    expect(within(panel).getByRole("heading", { name: "Driver packages" })).toBeVisible();
    expect(panel.querySelector(".run-history-panel-header p")).toHaveTextContent("21 records");
    expect(within(panel).getByRole("combobox", { name: "Sort drivers" })).toBeVisible();
    expect(within(panel).getByRole("button", { name: "Next page" })).toBeVisible();
    expect(within(panel).getByRole("link", { name: driver.name })).toHaveAttribute("href", "/drivers/driver-1");
    expect(table).not.toHaveTextContent(driver.id);
    await user.click(within(panel).getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(api.mock.calls.some(([url]) => String(url).includes("page=1"))).toBe(true));
  });

  it("keeps connection URL actions beside the summary and copies the full masked value", async () => {
    const user = userEvent.setup();
    const jdbcUrl = "jdbc:postgresql://example.invalid:5432/a/long/reporting/database?password=fixture-secret&ssl=true";
    mockApi(url => url.pathname === "/api/connections"
      ? json(page([{ ...connection, jdbcUrl, lastConnectionTestStatus: "SUCCEEDED", lastConnectionTestAt: connection.createdAt }]))
      : undefined);
    renderPage("/connections");
    const table = await screen.findByRole("table");
    const panel = table.closest("section")!;
    expect(within(panel).getByRole("heading", { name: "Connections" })).toBeVisible();
    expect(panel.querySelector(".run-history-panel-header p")).toHaveTextContent("1 record");
    expect(within(panel).getByRole("combobox", { name: "Sort connections" })).toBeVisible();
    expect(within(panel).getByRole("link", { name: connection.name })).toHaveAttribute("href", "/connections/connection-1");
    const urlCell = within(table).getByRole("cell", { name: /View URL/ });
    expect(urlCell.querySelector(".resource-url-line > div > p")).toHaveAttribute("title", redactJdbcUrl(jdbcUrl));
    expect(within(urlCell).getByRole("button", { name: "View URL" })).toBeVisible();
    await user.click(within(urlCell).getByRole("button", { name: "Copy" }));
    expect(await navigator.clipboard.readText()).toBe(redactJdbcUrl(jdbcUrl));
    expect(await navigator.clipboard.readText()).not.toContain("fixture-secret");
    expect(within(table).getByText("Last test succeeded")).toBeVisible();
  });
});
