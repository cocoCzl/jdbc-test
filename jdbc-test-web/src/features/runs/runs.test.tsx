import { chooseOption } from "@/test/select";
import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import {
  catalog,
  connection,
  finishedRun,
  page,
  result,
  run,
} from "@/test/fixtures";
import { json, mockApi, problem, renderPage } from "@/test/harness";
import { TestEventSource } from "@/test/test-event-source";
import { runPresentation } from "@/lib/status";
import type { RunStatus } from "@/types/api";

async function chooseConnectionAndDriver(
  user: ReturnType<typeof userEvent.setup>,
) {
  await user.click(
    await screen.findByRole("radio", { name: connection.name }),
  );
  await user.click(screen.getByRole("button", { name: /^Next:/ }));
  await screen.findByRole("group", { name: "Driver package" });
  await user.click(screen.getByRole("button", { name: /^Next:/ }));
}
describe("New Run wizard", () => {
  it("requires explicit intent before submitting empty credentials", async () => {
    const user = userEvent.setup();
    const api = mockApi((url, options) => {
      if (url.pathname === "/api/connections") return json(page([{ ...connection, hasSavedPassword: false }]));
      if (url.pathname === "/api/runs" && options.method === "POST") return json(run, 201);
    });
    const { router } = renderPage("/runs/new");
    await user.click(await screen.findByRole("radio", { name: connection.name }));
    await user.click(screen.getByRole("button", { name: /^Next:/ }));
    await screen.findByLabelText("Password for this run");
    expect(screen.getByRole("button", { name: /^Next:/ })).toBeDisabled();
    await user.click(screen.getByLabelText("Use an empty password explicitly"));
    await user.click(screen.getByRole("button", { name: /^Next:/ }));
    await user.click(screen.getByRole("button", { name: /^Next:/ }));
    await user.click(screen.getByRole("checkbox", { name: /I understand/ }));
    await user.click(screen.getByRole("button", { name: "Start test run" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/runs/run-1"));
    const body = JSON.parse(api.mock.calls.find(([, options]) => options?.method === "POST")![1]!.body as string);
    expect(body.password).toBe("");
  });
  it.each([
    ["Core", 136],
    ["Full", 202],
  ])(
    "loads %s and its scenario count from the catalog",
    async (profile, count) => {
      const user = userEvent.setup();
      mockApi();
      renderPage("/runs/new");
      await chooseConnectionAndDriver(user);
      await user.click(
        screen.getByRole("button", {
          name: new RegExp(`${profile} ${count} scenarios`),
        }),
      );
      expect(screen.getByText(`${count} scenarios selected`)).toBeVisible();
    },
  );
  it("uses changed server catalog counts rather than hardcoded profile totals", async () => {
    const user = userEvent.setup();
    mockApi((url) =>
      url.pathname === "/api/test-catalog"
        ? json({
            ...catalog,
            profiles: [{ id: "CORE", name: "Core", scenarioCount: 137 }],
          })
        : undefined,
    );
    renderPage("/runs/new");
    await chooseConnectionAndDriver(user);
    expect(screen.getByText("137 scenarios")).toBeVisible();
  });
  it("selects Custom Statement, requires consent and creates immediately", async () => {
    const user = userEvent.setup();
    const api = mockApi((url, options) =>
      url.pathname === "/api/runs" && options.method === "POST"
        ? json(run, 201)
        : undefined,
    );
    const { router } = renderPage("/runs/new");
    await chooseConnectionAndDriver(user);
    await user.click(screen.getByRole("button", { name: /Custom Choose/ }));
    await user.click(
      screen.getByRole("checkbox", { name: "Statement 23 scenarios" }),
    );
    expect(screen.getByText("23 scenarios selected")).toBeVisible();
    await user.click(screen.getByRole("button", { name: /^Next:/ }));
    expect(
      screen.getByRole("button", { name: "Start test run" }),
    ).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /I understand/ }));
    await user.click(screen.getByRole("button", { name: "Start test run" }));
    await waitFor(() =>
      expect(router.state.location.pathname).toBe("/runs/run-1"),
    );
    const input = JSON.parse(
      api.mock.calls.find(([, options]) => options?.method === "POST")![1]!
        .body as string,
    );
    expect(input).toEqual({
      connectionId: connection.id,
      driverId: connection.defaultDriver!.id,
      profile: "CUSTOM",
      modules: ["statement"],
      destructiveConsent: true,
    });
  });
  it("selects Custom Connection using the catalog count 17", async () => {
    const user = userEvent.setup();
    mockApi();
    renderPage("/runs/new");
    await chooseConnectionAndDriver(user);
    await user.click(screen.getByRole("button", { name: /Custom Choose/ }));
    await user.click(
      screen.getByRole("checkbox", { name: "Connection 17 scenarios" }),
    );
    expect(screen.getByText("17 scenarios selected")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Select all" }));
    expect(screen.getByText("40 scenarios selected")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Clear all" }));
    expect(screen.getByRole("button", { name: /^Next:/ })).toBeDisabled();
  });
  it("keeps temporary passwords out of URL, storage and query/mutation variables", async () => {
    const user = userEvent.setup();
    const api = mockApi((url, options) => {
      if (url.pathname === "/api/connections")
        return json(page([{ ...connection, hasSavedPassword: false }]));
      if (url.pathname === "/api/runs" && options.method === "POST")
        return json(run, 201);
    });
    const { router, client } = renderPage("/runs/new");
    await user.click(
      await screen.findByRole("radio", { name: connection.name }),
    );
    await user.click(screen.getByRole("button", { name: /^Next:/ }));
    const password = await screen.findByLabelText("Password for this run");
    expect(password).toHaveAttribute("type", "password");
    await user.type(password, "one-run-secret");
    await user.click(screen.getByRole("button", { name: /^Next:/ }));
    await user.click(screen.getByRole("button", { name: /^Next:/ }));
    await user.click(screen.getByRole("checkbox", { name: /I understand/ }));
    await user.click(screen.getByRole("button", { name: "Start test run" }));
    await waitFor(() =>
      expect(router.state.location.pathname).toBe("/runs/run-1"),
    );
    expect(
      JSON.parse(
        api.mock.calls.find(([, options]) => options?.method === "POST")![1]!
          .body as string,
      ).password,
    ).toBe("one-run-secret");
    expect(JSON.stringify(router.state.location)).not.toContain(
      "one-run-secret",
    );
    expect(
      JSON.stringify(
        client
          .getQueryCache()
          .getAll()
          .map((query) => query.state.data),
      ),
    ).not.toContain("one-run-secret");
    expect(
      client
        .getMutationCache()
        .getAll()
        .every((mutation) => mutation.state.variables === undefined),
    ).toBe(true);
    expect(localStorage.length + sessionStorage.length).toBe(0);
  });
});
describe("Run detail / results / report", () => {
  it("filters Run history through server query parameters", async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderPage("/runs");
    await chooseOption(user,
      await screen.findByLabelText("Filter run status"),
      "FAILED",
    );
    await waitFor(() =>
      expect(
        api.mock.calls.some(([url]) => String(url).includes("status=FAILED")),
      ).toBe(true),
    );
  });
  it("resets the Run history page when the status filter changes and keeps server totals", async () => {
    const user = userEvent.setup();
    const api = mockApi((url) => {
      if (url.pathname !== "/api/runs") return undefined;
      const filtered = url.searchParams.get("status") === "FAILED";
      return json(
        page(filtered ? [{ ...finishedRun, status: "FAILED" }] : [finishedRun], {
          page: Number(url.searchParams.get("page")),
          size: 20,
          totalElements: filtered ? 1 : 21,
          totalPages: filtered ? 1 : 2,
        }),
      );
    });
    renderPage("/runs");
    await screen.findByRole("heading", { name: "Run history" });
    await screen.findByText("21 records · Page 1 of 2");
    await user.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() =>
      expect(api.mock.calls.some(([url]) => String(url).includes("page=1"))).toBe(true),
    );
    await chooseOption(user, screen.getByLabelText("Filter run status"), "FAILED");
    await waitFor(() =>
      expect(
        api.mock.calls.some(([input]) => {
          const url = new URL(String(input), "http://localhost");
          return url.searchParams.get("status") === "FAILED" && url.searchParams.get("page") === "0";
        }),
      ).toBe(true),
    );
    expect(screen.getByText("1 record · Page 1 of 1")).toBeVisible();
  });
  it.each(["PASSED", "FAILED", "SKIPPED", "ABORTED"] as const)(
    "preserves result status %s",
    async (status) => {
      const user = userEvent.setup();
      mockApi((url) =>
        url.pathname.endsWith("/results")
          ? json(page([{ ...result, status }]))
          : undefined,
      );
      renderPage("/runs/run-1");
      await user.click(await screen.findByRole("tab", { name: "Test cases" }));
      await screen.findByText(result.displayName);
      expect(
        screen.getByText(
          {
            PASSED: "Passed",
            FAILED: "Failed",
            SKIPPED: "Skipped",
            ABORTED: "Aborted",
          }[status],
          { selector: "td span", exact: true },
        ),
      ).toBeVisible();
      if (status === "ABORTED") {
        await user.click(
          screen.getByRole("button", { name: "Inspect failure" }),
        );
        expect(screen.getByRole("dialog")).toHaveTextContent("Aborted");
      }
    },
  );
  it.each(Object.keys(runPresentation) as RunStatus[])(
    "renders %s detail with nullable fields",
    async (status) => {
      mockApi((url) =>
        url.pathname === "/api/runs/run-1"
          ? json({ ...run, status, startedAt: null })
          : undefined,
      );
      renderPage("/runs/run-1");
      expect(await screen.findByText("Environment & driver")).toBeVisible();
      expect(
        screen
          .getAllByText(runPresentation[status].label, { exact: true })
          .some((element) => element.tagName === "SPAN"),
      ).toBe(true);
      expect(document.body.textContent).not.toMatch(
        /workspacePath|encryptedPassword/,
      );
    },
  );
  it("updates progress from SSE and closes after completion", async () => {
    mockApi((url) =>
      url.pathname === "/api/runs/run-1" ? json(run) : undefined,
    );
    renderPage("/runs/run-1");
    await screen.findByText("Environment & driver");
    const source = TestEventSource.instances[0];
    act(() =>
      source.emit("run.progress", {
        runId: run.id,
        completedCases: 5,
        passed: 5,
      }),
    );
    expect(await screen.findByText("5 / 23")).toBeVisible();
    act(() =>
      source.emit("run.finished", {
        runId: run.id,
        status: "SUCCEEDED",
        completedCases: 23,
        passed: 23,
      }),
    );
    expect(source.closed).toBe(true);
  });
  it("confirms running cancel and refreshes the snapshot", async () => {
    const user = userEvent.setup();
    let cancelled = false;
    mockApi((url) => {
      if (url.pathname.endsWith("/cancel")) {
        cancelled = true;
        return new Response(null, { status: 204 });
      }
      if (url.pathname === "/api/runs/run-1")
        return json(
          cancelled
            ? {
                ...run,
                status: "CANCELLED",
                finishedAt: finishedRun.finishedAt,
              }
            : run,
        );
    });
    renderPage("/runs/run-1");
    await user.click(await screen.findByRole("button", { name: "Cancel run" }));
    await user.click(screen.getByRole("button", { name: "Cancel run" }));
    expect(await screen.findByText("Cancelled", { exact: true })).toBeVisible();
    expect(TestEventSource.instances[0].closed).toBe(true);
  });
  it("handles completion/cancel HTTP 409 by refetching, not marking Run failed", async () => {
    const user = userEvent.setup();
    let terminal = false;
    mockApi((url) => {
      if (url.pathname.endsWith("/cancel")) {
        terminal = true;
        return problem("RUN_ALREADY_FINISHED");
      }
      if (url.pathname === "/api/runs/run-1")
        return json(terminal ? finishedRun : run);
    });
    renderPage("/runs/run-1");
    await user.click(await screen.findByRole("button", { name: "Cancel run" }));
    await user.click(screen.getByRole("button", { name: "Cancel run" }));
    expect(await screen.findByText("Run succeeded", { exact: true })).toBeVisible();
  });
  it("requests server pagination/filtering and displays failure text safely", async () => {
    const user = userEvent.setup();
    const api = mockApi((url) =>
      url.pathname.endsWith("/results")
        ? json(
            page([result], {
              page: Number(url.searchParams.get("page")),
              totalPages: 2,
              totalElements: 23,
            }),
          )
        : undefined,
    );
    renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", { name: "Test cases" }));
    await screen.findByText(result.displayName);
    await user.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() =>
      expect(
        api.mock.calls.some(([url]) => String(url).includes("page=1")),
      ).toBe(true),
    );
    await chooseOption(user,
      screen.getByLabelText("Filter result status"),
      "ABORTED",
    );
    await waitFor(() =>
      expect(
        api.mock.calls.some(([url]) => String(url).includes("status=ABORTED")),
      ).toBe(true),
    );
    expect(screen.getByLabelText("Filter result module")).toBeDisabled();
    await chooseOption(user, screen.getByLabelText("Filter result status"), "");
    await chooseOption(user,
      screen.getByLabelText("Filter result module"),
      "statement",
    );
    await waitFor(() =>
      expect(
        api.mock.calls.some(([url]) =>
          String(url).includes("moduleId=statement"),
        ),
      ).toBe(true),
    );
    await user.click(screen.getByRole("button", { name: "Inspect failure" }));
    expect(screen.getByText("<script>unsafe</script>")).toBeVisible();
    expect(document.querySelector("script")).toBeNull();
    expect(screen.getByText("sanitized trace")).toBeVisible();
  });
  it("renders report and fetches raw JSON only on demand", async () => {
    const user = userEvent.setup();
    const api = mockApi((url) =>
      url.pathname.endsWith("/report.json")
        ? json({ summary: { passed: 23 } })
        : undefined,
    );
    renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", { name: "Report" }));
    expect(await screen.findByText("Captured environment")).toBeVisible();
    expect(await screen.findByLabelText("Outcome chart")).toHaveTextContent(
      "23 / 0 / 0 / 0",
    );
    expect(
      api.mock.calls.some(([url]) => String(url).endsWith("/report.json")),
    ).toBe(false);
    await user.click(screen.getByRole("button", { name: "View report JSON" }));
    expect(await screen.findByText(/"passed": 23/)).toBeVisible();
  });
});

describe("Execution evidence accounting", () => {
  it("shows not run and incomplete without filling terminal progress", async () => {
    const interrupted = { ...finishedRun, status: "FAILED", totalCases: 4, completedCases: 1,
      accounting: { selected: 4, started: 2, completed: 1, passed: 1, failed: 0, skipped: 0, aborted: 0, pending: 0, running: 0, notRun: 2, incomplete: 1, scenarios: [] } };
    mockApi((url) => url.pathname === `/api/runs/${finishedRun.id}` ? json(interrupted) : undefined);
    renderPage(`/runs/${finishedRun.id}`);
    expect(await screen.findByText("Not run")).toBeVisible();
    expect(screen.getByText("Incomplete")).toBeVisible();
    expect(screen.getByRole("progressbar")).toHaveAttribute("value", "1");
  });
});
