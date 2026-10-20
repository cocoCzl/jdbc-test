import { chooseOption } from "@/test/select";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { connection, driver, page, probe } from "@/test/fixtures";
import { json, mockApi, problem, renderPage } from "@/test/harness";

async function fillDraft(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText("Name"), "Vendor workspace");
  await chooseOption(user, screen.getByLabelText("Database type"), "compatible");
  await chooseOption(user, screen.getByLabelText("Built-in compatibility dialect"), "postgresql");
  await chooseOption(user,
    await screen.findByLabelText("Driver package"),
    driver.id,
  );
  await user.type(
    screen.getByLabelText("JDBC URL"),
    "jdbc:postgresql://host/test",
  );
  await user.type(screen.getByLabelText("Username"), "tester");
}
describe("Connection UI", () => {
  it("sends the Kingbase product, official adapter and actual driver/URL independently", async () => {
    const kingbaseDriver = { ...driver, id: "kingbase-driver", name: "Kingbase JDBC", vendor: "Kingbase",
      version: "V008R006B0001", driverClass: "com.kingbase8.Driver", urlPrefix: "jdbc:kingbase8:" };
    const user = userEvent.setup();
    const api = mockApi((url, options) => {
      if (url.pathname === "/api/adapters") return json([{
        id: "kingbase", name: "KingbaseES", status: "OFFICIAL", dialect: "postgresql",
        namespaceSelection: "schema", driverClass: "com.kingbase8.Driver",
        validatedCombinations: [{ databaseVersion: "12.1", driverVersion: "V008R006B0001" }],
        qualificationNoteKey: "adapter.kingbaseQualification",
      }]);
      if (url.pathname === "/api/drivers") return json(page([kingbaseDriver]));
      if (url.pathname === "/api/connections" && options.method === "POST") return json(connection, 201);
    });
    const { router } = renderPage("/connections/new");
    await user.type(await screen.findByLabelText("Name"), "Kingbase trial");
    await chooseOption(user, screen.getByLabelText("Database type"), "kingbase");
    expect(screen.getByLabelText("Database type")).toHaveAttribute("data-value", "kingbase");
    expect(screen.getByText(/Official platform integration for the validated combination only/)).toBeVisible();
    expect(screen.getByLabelText("Database type")).toHaveTextContent("KingbaseES · Official");
    expect(screen.getByText("Database 12.1 · Driver V008R006B0001")).toBeVisible();
    await chooseOption(user, await screen.findByLabelText("Driver package"), kingbaseDriver.id);
    await user.type(screen.getByLabelText("JDBC URL"), "jdbc:kingbase8://example.invalid:4321/test");
    await user.type(screen.getByLabelText("Username"), "tester");
    await user.click(screen.getByRole("button", { name: "Save connection" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/connections/connection-1"));
    const body = JSON.parse(api.mock.calls.find(([, options]) => options?.method === "POST")![1]!.body as string);
    expect(body).toMatchObject({ databaseType: "KingbaseES", adapterId: "kingbase",
      defaultDriverId: "kingbase-driver", jdbcUrl: "jdbc:kingbase8://example.invalid:4321/test" });
  });
  it.each([undefined, "", "   ", "ordinary-password"])(
    "sends the selected saved-probe credential unchanged: %j",
    async (value) => {
      const user = userEvent.setup();
      const api = mockApi();
      renderPage("/connections/connection-1");
      const input = await screen.findByLabelText("Password (leave blank to keep saved credential)");
      if (value === "") await user.click(screen.getByLabelText("Use an empty password explicitly"));
      else if (value !== undefined) await user.type(input, value);
      await user.click(screen.getByRole("button", { name: "Test saved connection" }));
      await screen.findByText("Connected", { exact: true });
      const call = api.mock.calls.find(([url]) => String(url).endsWith("/connection-1/test"))!;
      expect(JSON.parse(call[1]!.body as string)).toEqual(value === undefined ? {} : { password: value });
      expect(input).toHaveValue("");
    },
  );

  it("requires an explicit built-in compatibility dialect without importing a package", async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderPage("/connections/new");
    await chooseOption(user, await screen.findByLabelText("Database type"), "compatible");
    await chooseOption(user, screen.getByLabelText("Built-in compatibility dialect"), "postgresql");
    expect(screen.getByText(/Product identity is observed independently/)).toBeVisible();
    expect(api.mock.calls.some(([, options]) => options?.method === "POST")).toBe(false);
    expect(screen.queryByRole("link", {name: "Database adapters"})).not.toBeInTheDocument();
    expect(screen.queryByRole("link", {name: "SQL test packages"})).not.toBeInTheDocument();
  });
  it("saves an explicitly selected empty password", async () => {
    const user = userEvent.setup();
    const api = mockApi((url, options) => url.pathname === "/api/connections" && options.method === "POST"
      ? json(connection, 201) : undefined);
    const { router } = renderPage("/connections/new");
    await fillDraft(user);
    await user.click(screen.getByLabelText("Use an empty password explicitly"));
    await user.click(screen.getByLabelText("Save password"));
    await user.click(screen.getByRole("button", { name: "Save connection" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/connections/connection-1"));
    const body = JSON.parse(api.mock.calls.find(([, options]) => options?.method === "POST")![1]!.body as string);
    expect(body.password).toBe("");
    expect(body.savePassword).toBe(true);
  });

  it("renders list metadata without expecting a password", async () => {
    mockApi();
    renderPage("/connections");
    expect(
      await screen.findByRole("link", { name: connection.name }),
    ).toBeVisible();
    expect(document.body.textContent).not.toContain("encryptedPassword");
  });
  it("renders the connection empty state", async () => {
    mockApi((url) =>
      url.pathname === "/api/connections" ? json(page([])) : undefined,
    );
    renderPage("/connections");
    expect(
      await screen.findByText("No database connections yet"),
    ).toBeVisible();
  });
  it("loads adapters dynamically and only offers validated drivers", async () => {
    const user = userEvent.setup();
    mockApi((url) =>
      url.pathname === "/api/drivers"
        ? json(
            page([
              driver,
              {
                ...driver,
                id: "invalid",
                name: "Invalid package",
                status: "INVALID",
              },
            ]),
          )
        : undefined,
    );
    renderPage("/connections/new");
    await user.click(await screen.findByLabelText("Database type"));
    expect(
      await screen.findByRole("option", { name: /VendorDB/ }),
    ).toBeVisible();
    await user.keyboard("{Escape}");
    await user.click(await screen.findByLabelText("Driver package"));
    await screen.findByRole("option", { name: /PostgreSQL JDBC/ });
    expect(
      screen.queryByRole("option", { name: /Invalid package/ }),
    ).not.toBeInTheDocument();
  });
  it.each([true, false])(
    "creates a connection independently of Test with savePassword=%s",
    async (savePassword) => {
      const user = userEvent.setup();
      const api = mockApi((url, options) =>
        url.pathname === "/api/connections" && options.method === "POST"
          ? json(connection, 201)
          : undefined,
      );
      const { router, client } = renderPage("/connections/new");
      await fillDraft(user);
      await user.type(
        screen.getByLabelText("Password", { exact: true }),
        "private-password",
      );
      if (savePassword)
        await user.click(screen.getByLabelText("Save password"));
      await user.click(screen.getByRole("button", { name: "Save connection" }));
      await waitFor(() =>
        expect(router.state.location.pathname).toBe(
          "/connections/connection-1",
        ),
      );
      const input = JSON.parse(
        api.mock.calls.find(([, options]) => options?.method === "POST")![1]!
          .body as string,
      );
      expect(input).toMatchObject({
        databaseType: "compatible",
        adapterId: "postgresql",
        savePassword,
      });
      expect(input.password).toBe(
        savePassword ? "private-password" : undefined,
      );
      expect(
        JSON.stringify(
          client
            .getQueryCache()
            .getAll()
            .map((query) => query.state.data),
        ),
      ).not.toContain("private-password");
      expect(
        JSON.stringify(
          client
            .getMutationCache()
            .getAll()
            .map((mutation) => mutation.state.variables),
        ),
      ).not.toContain("private-password");
      expect(localStorage.length + sessionStorage.length).toBe(0);
    },
  );
  it("tests an unsaved draft and clears the in-memory password", async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderPage("/connections/new");
    await fillDraft(user);
    const password = screen.getByLabelText("Password", { exact: true });
    expect(password).toHaveAttribute("type", "password");
    await user.type(password, "draft-secret");
    await user.click(screen.getByRole("button", { name: "Test connection" }));
    expect(await screen.findByText("Connected", { exact: true })).toBeVisible();
    expect(password).toHaveValue("");
    const call = api.mock.calls.find(
      ([url]) => String(url) === "/api/connections/test",
    )!;
    expect(JSON.parse(call[1]!.body as string)).toMatchObject({
      driverId: driver.id,
      password: "draft-secret",
    });
  });
  it("does not echo saved credentials into the edit form and tests persisted settings", async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderPage("/connections/connection-1");
    const password = await screen.findByLabelText(
      "Password (leave blank to keep saved credential)",
    );
    expect(password).toHaveValue("");
    expect(screen.getByText("Saved password available")).toBeVisible();
    await user.click(
      screen.getByRole("button", { name: "Test saved connection" }),
    );
    expect(await screen.findByText("Connected", { exact: true })).toBeVisible();
    expect(
      api.mock.calls.find(([url]) =>
        String(url).endsWith("/connection-1/test"),
      )![1]!.body,
    ).toBe("{}");
  });
  it("clears saved credentials without sending a replacement password", async () => {
    const user = userEvent.setup();
    const api = mockApi((_url, options) =>
      options.method === "PATCH"
        ? json({ ...connection, hasSavedPassword: false })
        : undefined,
    );
    renderPage("/connections/connection-1");
    await user.click(await screen.findByLabelText("Clear saved password"));
    expect(
      screen.getByLabelText("Password (leave blank to keep saved credential)"),
    ).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Save connection" }));
    await waitFor(() =>
      expect(
        api.mock.calls.some(([, options]) => options?.method === "PATCH"),
      ).toBe(true),
    );
    const input = JSON.parse(
      api.mock.calls.find(([, options]) => options?.method === "PATCH")![1]!
        .body as string,
    );
    expect(input).toMatchObject({
      clearSavedPassword: true,
      savePassword: false,
    });
    expect(input).not.toHaveProperty("password");
  });
  it("shows sanitized test failure, not raw exceptions", async () => {
    const user = userEvent.setup();
    mockApi((url) =>
      url.pathname.endsWith("/test")
        ? problem("CONNECTION_TIMEOUT", 422)
        : undefined,
    );
    renderPage("/connections/connection-1");
    await user.click(
      await screen.findByRole("button", { name: "Test saved connection" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "CONNECTION_TIMEOUT",
    );
  });
  it("reports deletion conflict without offering cascade deletion", async () => {
    const user = userEvent.setup();
    mockApi((_url, options) =>
      options.method === "DELETE" ? problem("CONNECTION_IN_USE") : undefined,
    );
    renderPage("/connections/connection-1");
    await user.click(
      await screen.findByRole("button", { name: "Delete connection" }),
    );
    await user.click(screen.getByRole("button", { name: "Delete connection" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "CONNECTION_IN_USE",
    );
  });
  it("displays successful probe driver identity", async () => {
    const user = userEvent.setup();
    mockApi((url) =>
      url.pathname.endsWith("/test") ? json(probe) : undefined,
    );
    renderPage("/connections/connection-1");
    await user.click(
      await screen.findByRole("button", { name: "Test saved connection" }),
    );
    expect(await screen.findByText(/PostgreSQL JDBC Driver/)).toBeVisible();
    expect(screen.getByText("4.2")).toBeVisible();
  });
});
