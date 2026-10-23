import { chooseOption } from "@/test/select";
import { act, screen, waitFor } from "@testing-library/react";
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
  await user.type(screen.getByLabelText("Authorized test database"), "test");
  await user.type(screen.getByLabelText("Authorized test schema"), "owned_scope");
}
describe("Connection UI", () => {
  it("shows an existing custom database as compatible without relabeling its saved identity as PostgreSQL", async () => {
    mockApi();
    renderPage("/connections/connection-1");
    expect(await screen.findByLabelText("Database type")).toHaveAttribute("data-value", "compatible");
    expect(screen.getByLabelText("Built-in compatibility dialect")).toHaveAttribute("data-value", "postgresql");
  });
  it("allows incomplete actions, identifies each missing field and focuses the first error", async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderPage("/connections/new");
    const test = await screen.findByRole("button", { name: "Test connection" });
    const save = screen.getByRole("button", { name: "Save connection" });
    expect(test).toBeEnabled(); expect(save).toBeEnabled();
    await user.click(test);
    expect(screen.getByLabelText("Database type")).toHaveFocus();
    expect(screen.getByLabelText("Database type")).toHaveAccessibleDescription("Select a database type.");
    expect(screen.getByLabelText("Driver package")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText("JDBC URL")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText("Name")).not.toHaveAttribute("aria-invalid");
    expect(screen.queryByLabelText("Authorized test database")).not.toBeInTheDocument();
    await user.click(save);
    expect(screen.getByLabelText("Name")).toHaveFocus();
    expect(api.mock.calls.some(([, options]) => options?.method === "POST")).toBe(false);
  });

  it("never infers database identity from a driver's SQL dialect", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname === "/api/drivers" ? json(page([{ ...driver, driverClass: "vendor.CustomDriver", defaultSqlDialect: "postgresql" }])) : undefined);
    renderPage("/connections/new");
    await chooseOption(user, await screen.findByLabelText("Driver package"), driver.id);
    expect(screen.getByLabelText("SQL dialect")).toHaveAttribute("data-value", "postgresql");
    expect(screen.getByLabelText("Database type")).toHaveAttribute("data-value", "");
    await user.click(screen.getByRole("button", { name: "Test connection" }));
    expect(screen.getByLabelText("Database type")).toHaveFocus();
  });

  it("prefills only an exact unique driver-class association and preserves manual choices on refetch and driver changes", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname === "/api/adapters" ? json([
      { id: "postgresql", name: "PostgreSQL", dialect: "postgresql", driverClass: "org.postgresql.Driver" },
      { id: "mysql", name: "MySQL", dialect: "mysql", driverClass: "com.mysql.cj.jdbc.Driver" },
    ]) : url.pathname === "/api/drivers" ? json(page([driver, { ...driver, id: "mysql-driver", driverClass: "com.mysql.cj.jdbc.Driver", defaultSqlDialect: "mysql" }])) : undefined);
    const { client } = renderPage("/connections/new");
    await chooseOption(user, await screen.findByLabelText("Driver package"), driver.id);
    expect(screen.getByLabelText("Database type")).toHaveAttribute("data-value", "postgresql");
    await chooseOption(user, screen.getByLabelText("Database type"), "mysql");
    await chooseOption(user, screen.getByLabelText("SQL dialect"), "mysql");
    await chooseOption(user, screen.getByLabelText("Driver package"), "mysql-driver");
    await chooseOption(user, screen.getByLabelText("Driver package"), driver.id);
    await act(async () => { await client.invalidateQueries(); });
    expect(screen.getByLabelText("Database type")).toHaveAttribute("data-value", "mysql");
    expect(screen.getByLabelText("SQL dialect")).toHaveAttribute("data-value", "mysql");
  });

  it("uses current DOM autofill values even when a password manager emits no input event", async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderPage("/connections/new");
    await chooseOption(user, await screen.findByLabelText("Database type"), "postgresql");
    await chooseOption(user, screen.getByLabelText("Driver package"), driver.id);
    for (const [label, value] of [["JDBC URL", "jdbc:postgresql://fixture.invalid/test"], ["Username", "autofilled-user"], ["Password", "autofilled-fixture-secret"]]) {
      const input = screen.getByLabelText(label) as HTMLInputElement;
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input, value);
    }
    await user.click(screen.getByRole("button", { name: "Test connection" }));
    expect(await screen.findByText("Connected", { exact: true })).toBeVisible();
    const call = api.mock.calls.find(([url]) => String(url).endsWith("/connections/test"))!;
    expect(JSON.parse(call[1]!.body as string)).toMatchObject({ username: "autofilled-user", password: "autofilled-fixture-secret", jdbcUrl: "jdbc:postgresql://fixture.invalid/test" });
    expect(screen.getByLabelText("Password")).toHaveValue("autofilled-fixture-secret");
    expect(localStorage.length + sessionStorage.length).toBe(0);
  });

  it.each(["test", "save"])("disables both actions only during a pending %s request", async action => {
    const user = userEvent.setup();
    let release!: () => void;
    const pending = new Promise<void>(resolve => { release = resolve; });
    const api = mockApi(async (url, options) => {
      if (action === "test" && url.pathname.endsWith("/test")) { await pending; return json(probe); }
      if (action === "save" && options.method === "PATCH") { await pending; return problem("CONNECTION_UNKNOWN", 422); }
    });
    renderPage("/connections/connection-1");
    await user.click(await screen.findByRole("button", { name: action === "test" ? "Test connection" : "Save connection" }));
    expect(screen.getByRole("button", { name: action === "test" ? "Testing connection…" : "Test connection" })).toBeDisabled();
    expect(screen.getByRole("button", { name: action === "save" ? "Saving…" : "Save connection" })).toBeDisabled();
    release();
    await waitFor(() => expect(screen.getByRole("button", { name: "Save connection" })).toBeEnabled());
    expect(api.mock.calls.filter(([, options]) => options?.method === (action === "test" ? "POST" : "PATCH"))).toHaveLength(1);
  });

  it("sends the Kingbase product, official adapter and actual driver/URL independently", async () => {
    const kingbaseDriver = { ...driver, id: "kingbase-driver", name: "Kingbase JDBC", vendor: "Kingbase",
      version: "V008R006B0001", driverClass: "com.kingbase8.Driver",
      urlPrefix: "jdbc:kingbase8://192.0.2.20:4321/stored_driver_metadata", dataSources: {} };
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
    expect(screen.getByLabelText("Database type")).toHaveTextContent("KingbaseES");
    expect(screen.getByLabelText("Database type")).not.toHaveTextContent("Official");
    expect(screen.queryByText(/Official platform integration for the validated combination only/)).not.toBeInTheDocument();
    expect(screen.queryByText("Database 12.1 · Driver V008R006B0001")).not.toBeInTheDocument();
    expect(screen.queryByText("Adapter details")).not.toBeInTheDocument();
    await chooseOption(user, await screen.findByLabelText("Driver package"), kingbaseDriver.id);
    expect(screen.getByLabelText("JDBC URL")).toHaveValue("");
    await user.type(screen.getByLabelText("JDBC URL"), "jdbc:kingbase8://example.invalid:4321/test");
    await user.type(screen.getByLabelText("Username"), "tester");
    await user.type(screen.getByLabelText("Authorized test database"), "test");
    await user.type(screen.getByLabelText("Authorized test schema"), "owned_scope");
    await user.type(screen.getByLabelText("Password", { exact: true }), "fixture");
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
      const input = await screen.findByLabelText("Password");
      if (value === "") await user.click(screen.getByLabelText("This account has no password"));
      else if (value !== undefined) await user.type(input, value);
      await user.click(screen.getByRole("button", { name: "Test connection" }));
      await screen.findByText("Connected", { exact: true });
      const call = api.mock.calls.find(([url]) => String(url).endsWith("/connections/test"))!;
      expect(JSON.parse(call[1]!.body as string)).toMatchObject({ savedConnectionId: connection.id, ...(value === undefined ? {} : { password: value }) });
      expect(input).toHaveValue(value === undefined || value === "" ? "" : value);
    },
  );

  it("requires an explicit built-in compatibility dialect without importing a package", async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderPage("/connections/new");
    await chooseOption(user, await screen.findByLabelText("Database type"), "compatible");
    await chooseOption(user, screen.getByLabelText("Built-in compatibility dialect"), "postgresql");
    expect(screen.getByText(/Choose the dialect your database supports/)).toBeVisible();
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
    await user.click(screen.getByLabelText("This account has no password"));
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
      if (!savePassword) {
        await user.click(screen.getByText("Advanced settings", { exact: true }));
        await user.click(screen.getByLabelText("Save password"));
      }
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
  it("tests an unsaved draft and retains the password only in memory", async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderPage("/connections/new");
    await fillDraft(user);
    const password = screen.getByLabelText("Password", { exact: true });
    expect(password).toHaveAttribute("type", "password");
    await user.type(password, "draft-secret");
    await user.click(screen.getByRole("button", { name: "Test connection" }));
    expect(await screen.findByText("Connected", { exact: true })).toBeVisible();
    expect(password).toHaveValue("draft-secret");
    const call = api.mock.calls.find(
      ([url]) => String(url) === "/api/connections/test",
    )!;
    expect(JSON.parse(call[1]!.body as string)).toMatchObject({
      driverId: driver.id,
      password: "draft-secret",
    });
  });
  it("does not echo saved credentials and tests edited settings with saved credential", async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderPage("/connections/connection-1");
    const password = await screen.findByLabelText(
      "Password",
    );
    expect(password).toHaveValue("");
    expect(screen.getByText("Password saved")).toBeVisible();
    await user.clear(screen.getByLabelText("JDBC URL"));
    await user.type(screen.getByLabelText("JDBC URL"), "jdbc:postgresql://edited/test");
    await user.click(screen.getByRole("button", { name: "Test connection" }));
    expect(await screen.findByText("Connected", { exact: true })).toBeVisible();
    const call = api.mock.calls.find(([url]) => String(url).endsWith("/connections/test"))!;
    expect(JSON.parse(call[1]!.body as string)).toMatchObject({ savedConnectionId: connection.id, jdbcUrl: "jdbc:postgresql://edited/test" });
    expect(JSON.parse(call[1]!.body as string)).not.toHaveProperty("password");
  });
  it("clears saved credentials without sending a replacement password", async () => {
    const user = userEvent.setup();
    const api = mockApi((_url, options) =>
      options.method === "PATCH"
        ? json({ ...connection, hasSavedPassword: false })
        : undefined,
    );
    renderPage("/connections/connection-1");
    await screen.findByText("Advanced settings", { exact: true });
    await user.click(screen.getByText("Advanced settings", { exact: true }));
    await user.click(screen.getByLabelText("Clear saved password"));
    expect(
      screen.getByLabelText("Password"),
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
      await screen.findByRole("button", { name: "Test connection" }),
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
      await screen.findByRole("button", { name: "Test connection" }),
    );
    expect(await screen.findByText(/PostgreSQL JDBC Driver/)).toBeVisible();
    expect(screen.getByText("4.2")).toBeVisible();
  });

  it.each(["", "new-secret"])("edits a saved connection with omitted or replacement password: %j", async password => {
    const user = userEvent.setup();
    const api = mockApi((_url, options) => options.method === "PATCH" ? json(connection) : undefined);
    renderPage("/connections/connection-1");
    const input = await screen.findByLabelText("Password", { exact: true });
    expect(input).toHaveValue("");
    if (password) await user.type(input, password);
    await user.click(screen.getByRole("button", { name: "Save connection" }));
    await waitFor(() => expect(api.mock.calls.some(([, options]) => options?.method === "PATCH")).toBe(true));
    const body = JSON.parse(api.mock.calls.find(([, options]) => options?.method === "PATCH")![1]!.body as string);
    expect(body.savePassword).toBe(true);
    if (password) expect(body.password).toBe(password);
    else expect(body).not.toHaveProperty("password");
  });

  it("prefills the driver dialect only for new connections and requires a matching resource override", async () => {
    const user = userEvent.setup();
    const api = mockApi((url, options) => url.pathname === "/api/drivers" ? json(page([{ ...driver, defaultSqlDialect: "mysql" }]))
      : url.pathname === "/api/connections" && options.method === "POST" ? json(connection, 201) : undefined);
    renderPage("/connections/new");
    await fillDraft(user);
    expect(screen.getByLabelText("SQL dialect")).toHaveAttribute("data-value", "mysql");
    expect(screen.getByRole("alert")).toHaveTextContent("no matching resources");
    await user.type(screen.getByLabelText("Password", { exact: true }), "fixture-secret");
    await user.click(screen.getByRole("button", { name: "Save connection" }));
    expect(api.mock.calls.some(([, options]) => options?.method === "POST")).toBe(false);
    await chooseOption(user, screen.getByLabelText("SQL dialect"), "postgresql");
    await user.click(screen.getByRole("button", { name: "Save connection" }));
    await waitFor(() => expect(api.mock.calls.some(([, options]) => options?.method === "POST")).toBe(true));
    const body = JSON.parse(api.mock.calls.find(([, options]) => options?.method === "POST")![1]!.body as string);
    expect(body).toMatchObject({ sqlDialect: "postgresql", adapterId: "postgresql", savePassword: true, password: "fixture-secret" });
  });

  it("keeps an existing connection dialect when its driver default changes", async () => {
    mockApi(url => url.pathname === "/api/connections/connection-1" ? json({ ...connection, sqlDialect: "postgresql", defaultDriver: { ...driver, defaultSqlDialect: "oracle" } }) : undefined);
    renderPage("/connections/connection-1");
    expect(await screen.findByLabelText("SQL dialect")).toHaveAttribute("data-value", "postgresql");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("drops a previous driver's prefill on driver change, while preserving an explicit connection override", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname === "/api/drivers" ? json(page([
      { ...driver, defaultSqlDialect: "mysql" }, { ...driver, id: "without-default", name: "Other driver" },
    ])) : undefined);
    renderPage("/connections/new");
    await fillDraft(user);
    expect(screen.getByLabelText("SQL dialect")).toHaveAttribute("data-value", "mysql");
    await chooseOption(user, screen.getByLabelText("Driver package"), "without-default");
    expect(screen.getByLabelText("SQL dialect")).toHaveAttribute("data-value", "postgresql");
    await chooseOption(user, screen.getByLabelText("SQL dialect"), "oracle");
    await chooseOption(user, screen.getByLabelText("Driver package"), driver.id);
    expect(screen.getByLabelText("SQL dialect")).toHaveAttribute("data-value", "oracle");
  });

  it("keeps legacy connections without a saved password editable, but requires a credential to probe", async () => {
    const user = userEvent.setup();
    const api = mockApi((url, options) => url.pathname === "/api/connections/connection-1" ? json({ ...connection, hasSavedPassword: false })
      : options.method === "PATCH" ? json({ ...connection, hasSavedPassword: false }) : undefined);
    renderPage("/connections/connection-1");
    await user.click(await screen.findByRole("button", { name: "Test connection" }));
    expect(screen.getByLabelText("Password", { exact: true })).toHaveAttribute("aria-invalid", "true");
    expect(api.mock.calls.some(([url]) => String(url).endsWith("/test"))).toBe(false);
    await user.click(screen.getByRole("button", { name: "Save connection" }));
    await waitFor(() => expect(api.mock.calls.some(([, options]) => options?.method === "PATCH")).toBe(true));
    const body = JSON.parse(api.mock.calls.find(([, options]) => options?.method === "PATCH")![1]!.body as string);
    expect(body).not.toHaveProperty("password");
  });

  it.each([
    ["mysql", false, true], ["postgresql", true, true], ["oracle", true, false], ["dm", true, false], ["kingbase", true, true],
  ])("renders %s scope requirements while allowing scope-free probes", async (id, schema, database) => {
    const user = userEvent.setup();
    const api = mockApi(url => url.pathname === "/api/adapters" ? json([{ id, name: id, status: "OFFICIAL",
      dialect: id === "kingbase" ? "postgresql" : id, namespaceSelection: id === "mysql" ? "catalog" : "schema", requiresDatabase: id !== "oracle" && id !== "dm" }]) : undefined);
    renderPage("/connections/new");
    await chooseOption(user, await screen.findByLabelText("Database type"), String(id));
    await chooseOption(user, screen.getByLabelText("Driver package"), driver.id);
    await user.type(screen.getByLabelText("JDBC URL"), "jdbc:fixture:scope");
    await user.type(screen.getByLabelText("Password", { exact: true }), "fixture-secret");
    expect(!!screen.queryByLabelText("Authorized test schema")).toBe(schema);
    expect(!!screen.queryByLabelText("Authorized test database")).toBe(database);
    if (schema) expect(screen.getByLabelText("Authorized test schema")).toBeRequired();
    if (database) expect(screen.getByLabelText("Authorized test database")).toHaveAttribute("aria-required", "true");
    await user.click(screen.getByRole("button", { name: "Test connection" }));
    expect(await screen.findByText("Connected", { exact: true })).toBeVisible();
    expect(api.mock.calls.some(([url]) => String(url).endsWith("/connections/test"))).toBe(true);
    await user.click(screen.getByRole("button", { name: "Save connection" }));
    expect(screen.getAllByRole("alert").length).toBeGreaterThan(0);
  });

  it("shows unknown causes honestly and keeps SQL state, vendor code and stage in collapsed redacted details", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname.endsWith("/test") ? problem("CONNECTION_UNKNOWN", 422, {
      stage: "ESTABLISHING_CONNECTION", diagnostics: [{ sqlState: "ZZ999", vendorCode: 71, message: "Unknown [REDACTED]", exceptionType: "java.sql.SQLException" }],
    }) : undefined);
    renderPage("/connections/connection-1");
    await user.click(await screen.findByRole("button", { name: "Test connection" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("cause is unknown");
    const summary = screen.getByText("Redacted error details");
    expect(summary.closest("details")).not.toHaveAttribute("open");
    await user.click(summary);
    expect(screen.getByText("ZZ999")).toBeVisible();
    expect(screen.getByText("71")).toBeVisible();
    expect(screen.getByText(/Establishing connection/)).toBeVisible();
    expect(document.body.textContent).not.toContain("password is wrong");
  });
});
