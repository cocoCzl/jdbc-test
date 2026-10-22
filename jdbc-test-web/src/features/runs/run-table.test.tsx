import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { DataTable } from "@/components/data-table";
import { finishedRun } from "@/test/fixtures";
import type { Run } from "@/types/api";
import { getRunColumns } from "./run-columns";

function renderRuns(runs: Run[], compact = false) {
  return render(<MemoryRouter><DataTable rows={runs} columns={getRunColumns({ compact })} empty={null} /></MemoryRouter>);
}

describe("shared run table cells", () => {
  it("has nine separate ordered columns, a name tooltip and an accessible eye action", () => {
    const name = "A long saved task name that must not replace or share the status column";
    renderRuns([{ ...finishedRun, name }]);
    expect(screen.getAllByRole("columnheader").map(cell => cell.textContent)).toEqual([
      "Task name", "Status", "Connection", "Driver", "Progress", "Results", "Duration", "Started", "Actions",
    ]);
    const cells = screen.getAllByRole("cell");
    expect(within(cells[0]).getByRole("link", { name })).toHaveAttribute("title", name);
    expect(cells[0].querySelector(".status-badge")).toBeNull();
    expect(cells[1]).toHaveTextContent("Completed");
    const action = within(cells[8]).getByRole("link", { name: "View details" });
    expect(action).toHaveClass("resource-action");
    expect(action.querySelector(".lucide-eye")).not.toBeNull();
    expect(action).toHaveAttribute("href", `/runs/${finishedRun.id}`);
  });
  it("identifies creation time for tasks that have not started", () => {
    renderRuns([{ ...finishedRun, status: "QUEUED", startedAt: null }]);
    expect(screen.getAllByRole("cell")[7]).toHaveTextContent("Created");
  });

  it("uses a legacy display name and explicit details link without exposing the ID", () => {
    const run = { ...finishedRun, selectedModules: ["statement"], profile: "CUSTOM" };
    renderRuns([run]);
    expect(screen.getByRole("link", { name: /Statement/ })).toHaveAttribute("href", "/runs/run-1");
    expect(screen.queryByText(/Run run-1/)).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "View details" })).toHaveAttribute("href", "/runs/run-1");
    expect(screen.queryByRole("columnheader", { name: "Profile" })).not.toBeInTheDocument();
  });

  it("shows each outcome, including nonzero not-run and incomplete counts", () => {
    const run: Run = {
      ...finishedRun,
      status: "FAILED",
      completedCases: 20,
      totalCases: 25,
      passed: 12,
      failed: 3,
      skipped: 4,
      accounting: {
        selected: 25, started: 21, completed: 20, passed: 12, failed: 3,
        skipped: 4, aborted: 1, pending: 4, running: 1, notRun: 4, incomplete: 1,
        scenarios: [],
      },
    };
    renderRuns([run], true);
    const cells = screen.getAllByRole("cell");
    const metrics = cells[4].querySelectorAll(".run-result-metric");
    expect(Array.from(metrics, metric => metric.textContent)).toEqual([
      "Passed12", "Failed3", "Skipped4", "Aborted1", "Not run4", "Incomplete1",
    ]);
    expect(within(cells[3]).getByText("20 / 25")).toBeVisible();
  });

  it("leaves missing legacy counts unknown instead of rendering zero", () => {
    const run = { ...finishedRun, completedCases: null, passed: null, failed: null, skipped: null, accounting: null } as unknown as Run;
    renderRuns([run], true);
    const metrics = screen.getAllByRole("cell")[4].querySelectorAll(".run-result-metric");
    expect(Array.from(metrics, metric => metric.textContent)).toEqual([
      "Passed—", "Failed—", "Skipped—", "Aborted—",
    ]);
  });
});
