import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { runsKeys } from "@/api/runs";
import { finishedRun, page, run } from "@/test/fixtures";
import { json, mockApi, renderPage } from "@/test/harness";
import { chooseOption } from "@/test/select";
import { outcomePresentation } from "./run-outcome";
import type { RunStatus } from "@/types/api";

describe("Run history lifecycle filters", () => {
  it("has only six user-facing lifecycle options and defaults to an unfiltered server query", async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderPage("/runs");
    const trigger = await screen.findByRole("combobox", { name: "Filter run status" });
    expect(trigger).toHaveTextContent("All");
    await user.click(trigger);
    expect(screen.getAllByRole("option").map(option => option.textContent)).toEqual([
      "All", "Waiting", "Running", "Completed", "Ended abnormally", "Cancelled",
    ]);
    const request = new URL(String(api.mock.calls.find(([url]) => String(url).includes("/api/runs?"))![0]), "http://localhost");
    expect(request.searchParams.has("status")).toBe(false);
    expect(request.searchParams.has("statusGroup")).toBe(false);
  });

  it("uses grouped cache keys, server totals and the same group on refresh", async () => {
    const user = userEvent.setup();
    const api = mockApi(url => {
      if (url.pathname !== "/api/runs") return undefined;
      const filtered = url.searchParams.get("statusGroup") === "ABNORMAL";
      return json(page(filtered ? [{ ...run, id: "interrupted-only-on-server", status: "INTERRUPTED" }] : [finishedRun], {
        page: Number(url.searchParams.get("page")), totalElements: filtered ? 37 : 60, totalPages: filtered ? 2 : 3,
      }));
    });
    const { client } = renderPage("/runs");
    await screen.findByText("60 records · Page 1 of 3");
    await user.click(screen.getByRole("button", { name: "Next page" }));
    await screen.findByText("60 records · Page 2 of 3");
    await chooseOption(user, screen.getByLabelText("Filter run status"), "ABNORMAL");
    await screen.findByText("37 records · Page 1 of 2");
    const rows = within(screen.getByRole("table"));
    expect(rows.getByText("Ended abnormally")).toBeVisible();
    expect(rows.queryByText("Completed")).not.toBeInTheDocument();
    const params = { page: 0, size: 20, sort: "createdAt,desc", statusGroup: "ABNORMAL" as const };
    expect(client.getQueryData(runsKeys.list(params))).toMatchObject({ totalElements: 37 });
    const calls = api.mock.calls.length;
    await client.invalidateQueries({ queryKey: runsKeys.all });
    await waitFor(() => expect(api.mock.calls.length).toBeGreaterThan(calls));
    const refreshed = new URL(String(api.mock.calls.at(-1)![0]), "http://localhost");
    expect(refreshed.searchParams.get("statusGroup")).toBe("ABNORMAL");
    expect(refreshed.searchParams.get("page")).toBe("0");
    expect(refreshed.searchParams.has("status")).toBe(false);
    await chooseOption(user, screen.getByLabelText("Sort runs"), "finishedAt,desc");
    await waitFor(() => expect(String(api.mock.calls.at(-1)![0])).toContain("statusGroup=ABNORMAL"));
    expect(String(api.mock.calls.at(-1)![0])).toContain("sort=finishedAt%2Cdesc");
  });

  it("distinguishes filtered emptiness and clears the filter without a create action", async () => {
    const user = userEvent.setup();
    const api = mockApi(url => url.pathname === "/api/runs" && url.searchParams.has("statusGroup") ? json(page([])) : undefined);
    renderPage("/runs");
    await chooseOption(user, await screen.findByLabelText("Filter run status"), "CANCELLED");
    expect(await screen.findByText("No matching runs")).toBeVisible();
    expect(screen.queryByText("No test runs yet")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Create a test run" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Clear status filter" }));
    expect(await screen.findByText("Completed", { selector: ".status-badge" })).toBeVisible();
    const cleared = new URL(String(api.mock.calls.at(-1)![0]), "http://localhost");
    expect(cleared.searchParams.has("statusGroup")).toBe(false);
  });

  it.each(["QUEUED", "PREPARING", "PROBING", "RUNNING", "GENERATING_REPORT", "SUCCEEDED", "FAILED", "INTERRUPTED", "CANCELLED"] as RunStatus[])(
    "renders %s with only its lifecycle badge in the separate status cell", async status => {
      mockApi(url => url.pathname === "/api/runs" ? json(page([{ ...finishedRun, status, passed: 19, failed: 3, skipped: 1,
        failureReason: ["FAILED", "INTERRUPTED"].includes(status) ? "WORKER_EXITED_UNEXPECTEDLY" : null }])) : undefined);
      renderPage("/runs");
      const table = await screen.findByRole("table");
      expect(within(table).getByText(outcomePresentation({ ...finishedRun, status, failed: 3 }).label, { selector: ".status-badge" })).toBeVisible();
      const statusCell = within(table).getAllByRole("cell")[1];
      expect(statusCell.children).toHaveLength(1);
      expect(statusCell.querySelector(".status-badge")).not.toBeNull();
      expect(within(table).queryByText("Inspect execution details")).not.toBeInTheDocument();
      expect(table.querySelector(".run-result-danger")).toHaveTextContent("Failed3");
    },
  );
});
