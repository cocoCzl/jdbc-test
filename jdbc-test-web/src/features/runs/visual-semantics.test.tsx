import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { connection, finishedRun } from "@/test/fixtures";
import { json, mockApi, renderPage } from "@/test/harness";
import type { ExecutionAccounting, RunStatus } from "@/types/api";

const accounting: ExecutionAccounting = {selected: 12, started: 7, completed: 6, passed: 2, failed: 1, skipped: 2, aborted: 1, pending: 4, running: 2, notRun: 4, incomplete: 2, scenarios: []};
describe("Compact UI preserves execution semantics", () => {
  it.each(["FAILED", "CANCELLED", "INTERRUPTED", "RUNNING"] as RunStatus[])("keeps distinct counters for %s", async status => {
    const record = {...finishedRun, status, totalCases: 12, completedCases: 6, passed: 2, failed: 1, skipped: 2, accounting};
    mockApi(url => url.pathname === "/api/runs/run-1" ? json(record) : undefined);
    renderPage("/runs/run-1");
    const counters = await screen.findByLabelText("Execution counters");
    expect(counters.querySelector('[data-stat="Started"]')).toHaveTextContent("7");
    expect(counters.querySelector('[data-stat="Aborted"]')).toHaveTextContent("1");
    expect(counters.querySelector(`[data-stat="${status === "RUNNING" ? "Pending" : "Not run"}"]`)).toHaveTextContent("4");
    expect(counters.querySelector(`[data-stat="${status === "RUNNING" ? "Running" : "Incomplete"}"]`)).toHaveTextContent("2");
    expect(screen.getByRole("progressbar")).toHaveAttribute("value", "6");
    expect(screen.getByText(/50% complete/)).toBeVisible();
    expect(screen.getByRole("region", {name: "Run outcome summary"}).querySelectorAll('[data-metric]')).toHaveLength(4);
    expect(screen.getByRole("heading", {level: 1})).toHaveTextContent(connection.name);
  });
  it("leaves missing historical evidence unknown", async () => {
    mockApi(); renderPage("/runs/run-1");
    const counters = await screen.findByLabelText("Execution counters");
    for (const row of counters.querySelectorAll('dd')) expect(row).toHaveTextContent("—");
    expect(screen.getByText(/Legacy run:/)).toBeVisible();
  });
  it("does not present all skipped as compatibility success", async () => {
    const record = {...finishedRun, status: "FAILED", passed: 0, skipped: 23, accounting: {...accounting, selected: 23, started: 0, completed: 23, passed: 0, failed: 0, skipped: 23, aborted: 0, notRun: 0, incomplete: 0}};
    mockApi(url => url.pathname === "/api/runs/run-1" ? json(record) : undefined);
    renderPage("/runs/run-1");
    expect(await screen.findByText(/skipped cases do not establish compatibility/)).toBeVisible();
    expect(screen.getByRole("region", {name: "Run outcome summary"}).querySelector('[data-metric="Passed"]')).toHaveTextContent("0");
  });
  it("has a single driver creation action and no decorative logo suffix", async () => {
    mockApi(); renderPage("/drivers");
    await screen.findByText("PostgreSQL JDBC");
    expect(screen.getAllByRole("link", {name: "Add driver"})).toHaveLength(1);
    expect(screen.getByRole("link", {name: "jdbc-test"})).toBeVisible();
    expect(screen.queryByText("Built for confidence")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", {name: "Next page"})).not.toBeInTheDocument();
  });
  it("uses keyboard-operable radio selection and explains disabled Continue without submitting", async () => {
    const user = userEvent.setup(); const api = mockApi(); renderPage("/runs/new");
    const radio = await screen.findByRole("radio", {name: connection.name});
    expect(screen.getByText("Select a connection to continue.")).toBeVisible();
    expect(document.querySelector('header a[href="/runs/new"]')).toBeNull();
    radio.focus(); await user.keyboard(" "); expect(radio).toBeChecked();
    expect(screen.getByText("Selected")).toBeVisible();
    await user.click(screen.getByRole("button", {name: /^Next:/}));
    const driverGroup = await screen.findByRole("group", {name: "Driver package"});
    expect(within(driverGroup).getByRole("radio")).toBeChecked();
    await user.click(screen.getByRole("button", {name: /^Next:/}));
    await user.click(screen.getByRole("button", {name: /^Next:/}));
    expect(screen.getByRole("checkbox", {name: /I understand/})).not.toBeChecked();
    expect(screen.getByRole("button", {name: "Start test run"})).toBeDisabled();
    expect(api.mock.calls.some(([,opts])=>opts?.method === "POST")).toBe(false);
  });
});
