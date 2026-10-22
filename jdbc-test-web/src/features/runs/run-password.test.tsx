import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { connection, page, run } from "@/test/fixtures";
import { json, mockApi, problem, renderPage } from "@/test/harness";

async function next(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: /^Next:/ }));
}
async function openDriver(user: ReturnType<typeof userEvent.setup>, name = connection.name) {
  await user.click(await screen.findByRole("radio", { name }));
  await next(user);
  return screen.findByRole("radiogroup", { name: "Password for this run" });
}
async function review(user: ReturnType<typeof userEvent.setup>) {
  await next(user);
  await next(user);
  await user.click(screen.getByRole("checkbox", { name: /I understand/ }));
}

describe("Run password modes", () => {
  it.each(["saved", "other", "empty"] as const)("submits the exact %s credential intent without hidden old values", async mode => {
    const user = userEvent.setup();
    const api = mockApi((url, options) => url.pathname === "/api/runs" && options.method === "POST" ? json(run, 201) : undefined);
    const { router } = renderPage("/runs/new");
    await openDriver(user);
    expect(screen.getByRole("radio", { name: "Use saved password" })).toBeChecked();
    expect(screen.queryByLabelText("Password", { exact: true })).not.toBeInTheDocument();
    await user.click(screen.getByRole("radio", { name: "Enter another password" }));
    await user.type(screen.getByLabelText("Password", { exact: true }), "hidden-old-value");
    await user.click(screen.getByRole("radio", { name: "Use empty password" }));
    expect(screen.queryByLabelText("Password", { exact: true })).not.toBeInTheDocument();
    if (mode === "saved") await user.click(screen.getByRole("radio", { name: "Use saved password" }));
    if (mode === "other") {
      await user.click(screen.getByRole("radio", { name: "Enter another password" }));
      expect(screen.getByLabelText("Password", { exact: true })).toHaveValue("");
      expect(screen.getByRole("button", { name: /^Next:/ })).toBeDisabled();
      await user.type(screen.getByLabelText("Password", { exact: true }), "current-run-value");
      await next(user);
      await user.click(screen.getByRole("button", { name: "Back" }));
      expect(screen.getByRole("radio", { name: "Enter another password" })).toBeChecked();
      expect(screen.getByLabelText("Password", { exact: true })).toHaveValue("current-run-value");
    }
    await review(user);
    const modeLabel = { saved: "Use saved password", other: "Enter another password", empty: "Use empty password" }[mode];
    expect(screen.getByText(`Password for this run: ${modeLabel}`)).toBeVisible();
    expect(document.body.textContent).not.toMatch(/hidden-old-value|current-run-value/);
    await user.click(screen.getByRole("button", { name: "Start test run" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/runs/run-1"));
    const writes = api.mock.calls.filter(([, options]) => options?.method === "POST");
    expect(writes).toHaveLength(1);
    const body = JSON.parse(writes[0][1]!.body as string);
    if (mode === "saved") expect(body).not.toHaveProperty("password");
    else expect(body.password).toBe(mode === "empty" ? "" : "current-run-value");
    expect(JSON.stringify(localStorage) + JSON.stringify(sessionStorage)).not.toMatch(/hidden-old-value|current-run-value/);
  });

  it("defaults an asynchronously loaded connection without a saved password to other, never empty", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname === `/api/connections/${connection.id}` ? json({ ...connection, hasSavedPassword: false }) : undefined);
    renderPage(`/runs/new?connectionId=${connection.id}`);
    await screen.findByRole("radio", { name: connection.name });
    await next(user);
    const saved = screen.getByRole("radio", { name: "Use saved password" });
    expect(saved).toBeDisabled();
    expect(saved).toHaveAccessibleDescription("This connection has no saved password.");
    expect(screen.getByRole("radio", { name: "Enter another password" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Use empty password" })).not.toBeChecked();
    expect(screen.getByLabelText("Password", { exact: true })).toBeRequired();
    await user.click(screen.getByText("Use saved password", { exact: true }));
    expect(screen.getByRole("radio", { name: "Enter another password" })).toBeChecked();
    expect(screen.getByRole("button", { name: /^Next:/ })).toBeDisabled();
  });

  it("clears transient credentials and reapplies availability when switching connections", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname === "/api/connections" ? json(page([connection, { ...connection, id: "no-saved", name: "No saved password", hasSavedPassword: false }])) : undefined);
    renderPage("/runs/new");
    await openDriver(user);
    await user.click(screen.getByRole("radio", { name: "Enter another password" }));
    await user.type(screen.getByLabelText("Password", { exact: true }), "previous-connection-secret");
    await user.click(screen.getByRole("button", { name: "Back" }));
    await openDriver(user, "No saved password");
    expect(screen.getByRole("radio", { name: "Use saved password" })).toBeDisabled();
    expect(screen.getByRole("radio", { name: "Enter another password" })).toBeChecked();
    expect(screen.getByLabelText("Password", { exact: true })).toHaveValue("");
    await user.click(screen.getByRole("radio", { name: "Use empty password" }));
    await user.click(screen.getByRole("button", { name: "Back" }));
    await openDriver(user);
    expect(screen.getByRole("radio", { name: "Use saved password" })).toBeChecked();
    await user.click(screen.getByRole("radio", { name: "Enter another password" }));
    expect(screen.getByLabelText("Password", { exact: true })).toHaveValue("");
  });

  it("clears the submitted secret after failure without silently switching to saved credentials", async () => {
    const user = userEvent.setup();
    mockApi((url, options) => url.pathname === "/api/runs" && options.method === "POST" ? problem("INVALID_SELECTION", 422) : undefined);
    renderPage("/runs/new");
    await openDriver(user);
    await user.click(screen.getByRole("radio", { name: "Enter another password" }));
    await user.type(screen.getByLabelText("Password", { exact: true }), "failed-secret");
    await review(user);
    await user.click(screen.getByRole("button", { name: "Start test run" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Start test run" })).toBeDisabled());
    await user.click(within(screen.getByRole("group", { name: "Run steps" })).getAllByRole("button")[1]);
    expect(screen.getByRole("radio", { name: "Enter another password" })).toBeChecked();
    expect(screen.getByLabelText("Password", { exact: true })).toHaveValue("");
  });
});
