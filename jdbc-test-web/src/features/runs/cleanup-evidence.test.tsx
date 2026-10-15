import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { CleanupEvidence } from "./cleanup-evidence";

describe("cleanup evidence", () => {
  it("shows historical absence as unknown", () => {
    render(<CleanupEvidence />);
    expect(screen.getByText(/Unknown \/ not recorded/)).toBeVisible();
  });
  it("keeps retained object evidence available", () => {
    render(<CleanupEvidence evidence={{ status: "FAILED", attempted: 1, confirmedDeleted: 0,
      retainedObjects: [{ name: "fixture_owned", sqlState: "42501" }] }} />);
    expect(screen.getByText(/Cleanup failed/)).toBeVisible();
    expect(screen.getByText(/Attempts: 1/)).toBeVisible();
    expect(screen.getByText(/fixture_owned/)).toBeInTheDocument();
  });
});
