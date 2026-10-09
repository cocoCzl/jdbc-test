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
  it("keeps the Custom module, profile, full ID and real detail link", () => {
    const run = { ...finishedRun, selectedModules: ["statement"], profile: "CUSTOM" };
    renderRuns([run]);
    expect(screen.getByRole("link", { name: "Statement" })).toHaveAttribute("href", "/runs/run-1");
    expect(screen.getByText(/Custom/)).toBeVisible();
    expect(screen.getByText(/Run run-1/)).toHaveAttribute("title", "run-1");
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
    const metrics = cells[3].querySelectorAll(".run-result-metric");
    expect(Array.from(metrics, metric => metric.textContent)).toEqual([
      "Passed12", "Failed3", "Skipped4", "Aborted1", "Not run4", "Incomplete1",
    ]);
    expect(within(cells[2]).getByText("20 / 25")).toBeVisible();
  });

  it("leaves missing legacy counts unknown instead of rendering zero", () => {
    const run = { ...finishedRun, completedCases: null, passed: null, failed: null, skipped: null, accounting: null } as unknown as Run;
    renderRuns([run], true);
    const metrics = screen.getAllByRole("cell")[3].querySelectorAll(".run-result-metric");
    expect(Array.from(metrics, metric => metric.textContent)).toEqual([
      "Passed—", "Failed—", "Skipped—", "Aborted—",
    ]);
  });
});
