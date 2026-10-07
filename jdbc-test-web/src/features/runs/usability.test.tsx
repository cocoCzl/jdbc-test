import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { connection, finishedRun, page } from "@/test/fixtures";
import { json, mockApi, renderPage } from "@/test/harness";
import { redactJdbcUrl } from "@/components/jdbc-url";
import { formatDateTime, plural } from "@/lib/format";

describe("Usability without changing execution semantics", () => {
  it.each(["/", "/runs"])("makes the Run the main link on %s without detail requests", async path => {
    const api = mockApi(); renderPage(path);
    const table = await screen.findByRole("table");
    const links = within(table).getAllByRole("link");
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveTextContent("Run run-1");
    expect(links[0]).toHaveAttribute("href", "/runs/run-1");
    expect(within(table).getByText(connection.name).closest("a")).toBeNull();
    expect(api.mock.calls.some(([url]) => /\/api\/runs\//.test(String(url)))).toBe(false);
    expect(document.querySelector("header a")).toBeNull();
    expect(within(table).getByText("Failed 0")).toHaveClass("text-muted-foreground");
  });
  it("keeps nonzero unexecuted and incomplete cases visible in history", async () => {
    mockApi(url => url.pathname === "/api/runs" ? json(page([{...finishedRun, status: "INTERRUPTED", failed: 1, passed: 1, completedCases: 3, skipped: 0,
      accounting: {selected: 23, started: 4, completed: 3, passed: 1, failed: 1, skipped: 0, aborted: 1, pending: 0, running: 0, notRun: 19, incomplete: 1, scenarios: []}}])) : undefined);
    renderPage("/runs");
    expect(await screen.findByText("Not run 19")).toHaveClass("text-warning");
    expect(screen.getByText("Incomplete 1")).toBeVisible();
    expect(screen.getByText("Aborted 1")).toHaveClass("text-warning");
    expect(screen.getByText("Failed 1")).toHaveClass("text-destructive");
  });
  it("shows scoped statistics on demand and singular labels", async () => {
    const user = userEvent.setup(); mockApi(); renderPage("/");
    const summary = await screen.findByText("About these statistics");
    const details = summary.closest("details")!;
    expect(details).not.toHaveAttribute("open");
    await user.click(summary);
    expect(details).toHaveAttribute("open");
    expect(details).toHaveTextContent("latest 1 loaded run, not all history");
  });
  it("allows full masked URL viewing and copying without selecting or submitting a run", async () => {
    const user = userEvent.setup();
    const url = "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=fixture.invalid)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=fixture_service)))?password=fixture-only";
    const api = mockApi(u => u.pathname === "/api/connections" ? json(page([{...connection, jdbcUrl: url}])) : undefined);
    renderPage("/runs/new");
    await user.click(await screen.findByRole("button", {name: "View / copy URL"}));
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent("SERVICE_NAME=fixture_service");
    expect(dialog).not.toHaveTextContent("fixture-only");
    expect(dialog).toHaveTextContent("[REDACTED]");
    await user.click(within(dialog).getByRole("button", {name: "Copy sanitized JDBC URL"}));
    expect(await navigator.clipboard.readText()).toBe(redactJdbcUrl(url));
    await user.keyboard("{Escape}");
    expect(screen.getByRole("radio", {name: connection.name})).not.toBeChecked();
    await user.click(screen.getByRole("heading", {name: connection.name}));
    expect(screen.getByRole("radio", {name: connection.name})).toBeChecked();
    expect(screen.getByRole("button", {name: "Next: Choose driver"})).toBeEnabled();
    expect(api.mock.calls.some(([, options]) => options?.method === "POST")).toBe(false);
  });
  it("masks common credential forms without parsing or changing the target descriptor", () => {
    for (const url of ["jdbc:mysql://user:fake@fixture.invalid/db?token=fake", "jdbc:oracle:thin:user/fake@fixture.invalid:1521/service", "jdbc:sqlserver://fixture.invalid;password={fake;value};encrypt=true"])
      expect(redactJdbcUrl(url)).not.toContain("fake");
    const descriptor = "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=fixture.invalid)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=test)))";
    expect(redactJdbcUrl(descriptor)).toBe(descriptor);
  });
  it("uses one date style in local time and correct singular/plural labels", () => {
    const date = new Date(2026, 9, 7, 8, 9);
    expect(formatDateTime(date.toISOString())).toBe("2026-10-07 08:09");
    expect(formatDateTime(null)).toBe("—");
    expect(plural(1, "record")).toBe("1 record");
    expect(plural(2, "loaded run")).toBe("2 loaded runs");
  });
});
