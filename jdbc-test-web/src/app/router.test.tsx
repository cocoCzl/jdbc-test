import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { page } from "@/test/fixtures";
import { json, mockApi, renderPage } from "@/test/harness";

describe("App routing / Dashboard", () => {
  it("shows genuine paginated totals and explicitly scopes recent outcomes", async () => {
    mockApi((url) =>
      url.pathname === "/api/runs"
        ? json(page([], { totalElements: 126 }))
        : undefined,
    );
    renderPage("/");
    expect(await screen.findByText("126")).toBeVisible();
    expect(screen.getByText("No test runs yet")).toBeVisible();
    expect(screen.getByText(/not global statistics/)).toBeVisible();
  });
  it("navigates from sidebar to resource pages with an active route", async () => {
    const user = userEvent.setup();
    mockApi();
    const { router } = renderPage("/");
    await user.click(screen.getByRole("link", { name: "Drivers" }));
    expect(await screen.findByText("PostgreSQL JDBC")).toBeVisible();
    expect(router.state.location.pathname).toBe("/drivers");
    await waitFor(() =>
      expect(screen.getByRole("link", { name: "Drivers" })).toHaveAttribute(
        "aria-current",
        "page",
      ),
    );
  });
  it("handles unknown routes without a placeholder feature", async () => {
    mockApi();
    renderPage("/missing");
    expect(await screen.findByText("Page not found")).toBeVisible();
    expect(
      screen.queryByRole("link", { name: "Settings" }),
    ).not.toBeInTheDocument();
  });
});
