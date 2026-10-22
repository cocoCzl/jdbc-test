import { chooseOption } from "@/test/select";
import i18n from "@/i18n";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { driver, page } from "@/test/fixtures";
import { json, mockApi, problem, renderPage } from "@/test/harness";
import { validateFiles } from "@/lib/driver-upload";

describe("Driver UI", () => {
  it("keeps the editor open when a stale backend ignores the selected default dialect", async () => {
    const user = userEvent.setup();
    mockApi((_url, options) => options.method === "PATCH" ? json(driver) : undefined);
    renderPage("/drivers/driver-1");
    await user.click(await screen.findByRole("button", { name: "Edit information" }));
    await chooseOption(user, screen.getByLabelText("Default SQL dialect (optional)"), "postgresql");
    await user.click(screen.getByRole("button", { name: "Save information" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("DRIVER_DIALECT_UPDATE_FAILED");
    expect(screen.getByLabelText("Default SQL dialect (optional)")).toHaveTextContent("PostgreSQL");
    expect(screen.getByRole("button", { name: "Save information" })).toBeVisible();
  });

  it("shows backend-provided ambiguous class candidates for correction", async () => {
    const user = userEvent.setup();
    mockApi((_url, options) =>
      options.method === "POST"
        ? problem("DRIVER_CLASS_AMBIGUOUS", 422, {
            candidates: ["a.Driver", "b.Driver"],
          })
        : undefined,
    );
    renderPage("/drivers/new");
    await user.type(await screen.findByLabelText("Name"), "Package");
    await user.type(screen.getByLabelText("Vendor"), "Vendor");
    await user.type(screen.getByLabelText("Registered version"), "1");
    await user.upload(
      screen.getByLabelText("JAR files"),
      new File(["jar"], "driver.jar"),
    );
    await user.click(
      screen.getByRole("button", { name: "Confirm upload" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "DRIVER_CLASS_AMBIGUOUS",
    );
    await chooseOption(user,
      screen.getByLabelText("Classes returned by the server"),
      "b.Driver",
    );
    expect(screen.getByLabelText("Driver class (optional)")).toHaveValue(
      "b.Driver",
    );
  });
  it("edits metadata without replacing immutable binaries", async () => {
    const user = userEvent.setup();
    const api = mockApi((_url, options) =>
      options.method === "PATCH"
        ? json({ ...driver, name: "Renamed", defaultSqlDialect: "oracle" })
        : undefined,
    );
    renderPage("/drivers/driver-1");
    await user.click(
      await screen.findByRole("button", { name: "Edit information" }),
    );
    await user.clear(screen.getByLabelText("Name"));
    await user.type(screen.getByLabelText("Name"), "Renamed");
    await chooseOption(user, screen.getByLabelText("Default SQL dialect (optional)"), "oracle");
    await user.click(screen.getByText("Advanced interface configuration", { selector: "form summary" }));
    await user.type(screen.getByLabelText("dataSource — Implementation class"), "custom.jdbc.Source");
    await user.clear(screen.getByLabelText("URL property name"));
    await user.type(screen.getByLabelText("URL property name"), "jdbcUrl");
    await user.click(screen.getByRole("button", { name: "Save information" }));
    await waitFor(() =>
      expect(
        api.mock.calls.some(([, options]) => options?.method === "PATCH"),
      ).toBe(true),
    );
    const body = JSON.parse(
      api.mock.calls.find(([, options]) => options?.method === "PATCH")![1]!
        .body as string,
    );
    expect(body.name).toBe("Renamed");
    expect(body.defaultSqlDialect).toBe("oracle");
    expect(body.dataSources.dataSource).toEqual({ className: "custom.jdbc.Source", urlProperty: "jdbcUrl" });
    expect(body).not.toHaveProperty("artifacts");
    expect(body).not.toHaveProperty("storageKey");
  });
  it.each(["en", "zh-CN"])("preserves a full stored URL and existing advanced providers when editing in %s", async language => {
    await i18n.changeLanguage(language);
    const user = userEvent.setup();
    const saved = {
      ...driver,
      urlPrefix: "jdbc:kingbase8://192.0.2.20:4321/existing_database",
      dataSources: {
        dataSource: { className: "custom.jdbc.Source", urlProperty: "jdbcUrl", userProperty: "login", passwordProperty: "secret" },
        connectionPoolDataSource: { className: "custom.jdbc.Pool" },
        xaDataSource: { className: "custom.jdbc.XA", urlProperty: "connectionUrl" },
      },
    };
    const api = mockApi((url, options) => {
      if (url.pathname === "/api/drivers/driver-1") return json(saved);
      if (options.method === "PATCH") return json(saved);
    });
    renderPage("/drivers/driver-1");
    const summary = await screen.findByText(i18n.t("ui.driverAdvancedInterfaces"), { selector: "summary" });
    expect(summary.closest("details")).not.toHaveAttribute("open");
    expect(screen.getByText("custom.jdbc.Source")).not.toBeVisible();
    await user.click(summary);
    for (const provider of Object.values(saved.dataSources)) {
      expect(screen.getByText(provider.className)).toBeVisible();
    }
    expect(screen.getByText(saved.urlPrefix)).toBeVisible();
    expect(screen.queryByText(i18n.t("driverPreset.urlPrefixHint"))).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: i18n.t("ui.editMetadata") }));
    const url = screen.getByLabelText(i18n.t("ui.jdbcURLPrefix"));
    expect(url).toHaveValue(saved.urlPrefix);
    expect(url).not.toBeRequired();
    const editorSummary = screen.getByText(i18n.t("ui.driverAdvancedInterfaces"), { selector: "form summary" });
    expect(editorSummary.closest("details")).not.toHaveAttribute("open");
    await user.click(editorSummary);
    expect(screen.getByLabelText(`dataSource — ${i18n.t("ui.implementationClass")}`)).toHaveValue("custom.jdbc.Source");
    const property = within(editorSummary.closest("details")!).getAllByLabelText(i18n.t("ui.urlProperty"))[0];
    await user.clear(property);
    await user.type(property, "updatedUrl");
    await user.click(screen.getByRole("button", { name: i18n.t("ui.saveMetadata") }));
    await waitFor(() => expect(api.mock.calls.some(([, options]) => options?.method === "PATCH")).toBe(true));
    const body = JSON.parse(api.mock.calls.find(([, options]) => options?.method === "PATCH")![1]!.body as string);
    expect(body.urlPrefix).toBe(saved.urlPrefix);
    expect(body.dataSources).toEqual({
      ...saved.dataSources,
      dataSource: { ...saved.dataSources.dataSource, urlProperty: "updatedUrl" },
    });
  });
  it.each(["en", "zh-CN"])("shows optional unconfigured interfaces only after expanding in %s", async language => {
    await i18n.changeLanguage(language);
    const user = userEvent.setup();
    mockApi();
    renderPage("/drivers/driver-1");
    const summary = await screen.findByText(i18n.t("ui.driverAdvancedInterfaces"), { selector: "summary" });
    expect(summary.closest("details")).not.toHaveAttribute("open");
    expect(screen.getByText("DataSource", { exact: true })).not.toBeVisible();
    await user.click(summary);
    expect(screen.getAllByText(i18n.t("ui.driverInterfaceNotConfigured"))).toHaveLength(3);
    expect(screen.queryByText(i18n.t("ui.driverAdvancedInterfacesHint"))).not.toBeInTheDocument();
  });
  it("renders the paginated list and artifact counts", async () => {
    mockApi();
    renderPage("/drivers");
    expect(
      await screen.findByRole("link", { name: driver.name }),
    ).toBeVisible();
    expect(screen.getAllByText("Load check passed").length).toBeGreaterThan(0);
  });
  it("shows an actionable empty state", async () => {
    mockApi((url) =>
      url.pathname === "/api/drivers" ? json(page([])) : undefined,
    );
    renderPage("/drivers");
    expect(await screen.findByText("No JDBC drivers yet")).toBeVisible();
  });
  it("shows resource errors instead of a blank page", async () => {
    mockApi((url) =>
      url.pathname === "/api/drivers/driver-1"
        ? problem("DRIVER_NOT_FOUND", 404)
        : undefined,
    );
    renderPage("/drivers/driver-1");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "DRIVER_NOT_FOUND",
    );
  });
  it.each([
    [[], "Choose at least"],
    [[new File(["x"], "bad.txt")], "Only .jar"],
    [
      Array.from({ length: 21 }, () => new File(["x"], "file.jar")),
      "at most 20",
    ],
  ])("checks upload constraints %#", (files, message) => {
    expect(i18n.t(validateFiles(files) ?? "")).toContain(message);
  });
  it("checks single and total size without allocating large buffers", () => {
    const sized = (size: number) => {
      const file = new File(["x"], "file.jar");
      Object.defineProperty(file, "size", { value: size });
      return file;
    };
    expect(i18n.t(validateFiles([sized(101 * 1024 ** 2)]) ?? "")).toContain("100 MB");
    expect(
      i18n.t(validateFiles([
        sized(90 * 1024 ** 2),
        sized(90 * 1024 ** 2),
        sized(90 * 1024 ** 2),
      ]) ?? ""),
    ).toContain("250 MB");
  });
  it("uploads multiple JARs, removes a file and navigates to detail", async () => {
    const user = userEvent.setup();
    const api = mockApi((url, options) =>
      url.pathname === "/api/drivers" && options.method === "POST"
        ? json(driver, 201)
        : undefined,
    );
    const { router } = renderPage("/drivers/new");
    await user.click(screen.getByText("Standard DataSource implementations (optional)"));
    await user.type(screen.getByLabelText("xaDataSource — Implementation class"), "custom.jdbc.XA");
    await user.type(await screen.findByLabelText("Name"), "PostgreSQL JDBC");
    await user.type(screen.getByLabelText("Vendor"), "PostgreSQL");
    await user.type(screen.getByLabelText("Registered version"), "42.7.10");
    await user.upload(screen.getByLabelText("JAR files"), [
      new File(["jar"], "one.jar"),
      new File(["jar"], "two.jar"),
      new File(["jar"], "remove.jar"),
    ]);
    await user.click(screen.getByRole("button", { name: "Remove remove.jar" }));
    expect(screen.getByText("2 artifacts")).toBeVisible();
    await user.click(
      screen.getByRole("button", { name: "Confirm upload" }),
    );
    await waitFor(() =>
      expect(router.state.location.pathname).toBe("/drivers/driver-1"),
    );
    const body = api.mock.calls.find(
      ([, options]) => options?.method === "POST",
    )![1]!.body as FormData;
    expect(body.getAll("files")).toHaveLength(2);
    const metadataText = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(reader.error);
      reader.readAsText(body.get("metadata") as Blob);
    });
    const metadata = JSON.parse(metadataText);
    expect(metadata.dataSources.xaDataSource.className).toBe("custom.jdbc.XA");
  });
  it("validates a driver and presents the returned probe metadata", async () => {
    const user = userEvent.setup();
    mockApi((url) =>
      url.pathname.endsWith("/validate")
        ? json({
            status: "VALIDATED",
            driverClass: driver.driverClass,
            majorVersion: 42,
            minorVersion: 7,
            jdbcCompliant: false,
          })
        : undefined,
    );
    renderPage("/drivers/driver-1");
    await user.click(
      await screen.findByRole("button", { name: "Check driver files" }),
    );
    expect(
      await screen.findByRole("heading", { name: "Load check passed" }),
    ).toBeVisible();
    const result = within(screen.getByRole("region", { name: "Load check passed" }));
    expect(result.getByText("Technical details").closest("details")).not.toHaveAttribute("open");
    expect(result.getByText("Version declared in file").nextElementSibling).toHaveTextContent("Not provided");
    expect(result.getByText("42.7")).not.toBeVisible();
    expect(result.getAllByText("Not tested")).toHaveLength(2);
    expect(result.getByRole("link", { name: "Configure connection" })).toHaveAttribute("href", "/connections/new");
    await user.click(result.getByText("Technical details"));
    expect(result.getByText("42.7")).toBeVisible();
    expect(result.getByText("false", { exact: true })).toBeVisible();
    expect(document.body.textContent).not.toMatch(/storageKey|workspacePath/);
  });
  it.each(["en", "zh-CN"])("separates registered, API and defining-JAR versions in %s", async language => {
    await i18n.changeLanguage(language);
    const user = userEvent.setup();
    mockApi(url => {
      if (url.pathname === "/api/drivers/driver-1") return json({ ...driver, version: "9.0.0" });
      if (url.pathname.endsWith("/validate")) return json({
        status: "VALIDATED", driverClass: driver.driverClass,
        majorVersion: 0, minorVersion: 0, jdbcCompliant: false,
        loadedArtifact: {
          fileName: "actual-driver.jar", sha256: "a".repeat(64),
          implementationVersion: null, bundleVersion: "0.0.0.V009R002C016",
        },
      });
      return undefined;
    });
    renderPage("/drivers/driver-1");
    await user.click(await screen.findByRole("button", { name: i18n.t("ui.validateDriver") }));
    const result = within(await screen.findByRole("region", { name: i18n.t("ui.driverCheckPassed") }));
    expect(result.getByText("0.0", { exact: true })).not.toBeVisible();
    expect(result.getByText(i18n.t("ui.driverZeroVersionHint"))).not.toBeVisible();
    expect(result.queryByText(i18n.t("ui.registeredDriverVersion"))).not.toBeInTheDocument();
    expect(screen.getByText(/9\.0\.0/)).toBeVisible();
    expect(result.getByText(i18n.t("ui.driverCheckFileVersion")).nextElementSibling).toHaveTextContent("0.0.0.V009R002C016");
    expect(result.getByText("actual-driver.jar")).toBeVisible();
    await user.click(result.getByText(i18n.t("ui.driverCheckDetails")));
    expect(result.getByText("0.0", { exact: true })).toBeVisible();
    expect(result.getByText(i18n.t("ui.driverZeroVersionHint"))).toBeVisible();
    expect(result.getByText("Implementation-Version").nextElementSibling).toHaveTextContent(i18n.t("ui.driverMetadataNotProvided"));
    expect(result.getByText("Bundle-Version").nextElementSibling).toHaveTextContent("0.0.0.V009R002C016");
    expect(result.getByText(i18n.t("ui.driverComplianceSource"))).toBeVisible();
    await i18n.changeLanguage("en");
  });
  it.each([
    {},
    { majorVersion: 1, minorVersion: 0 },
    { majorVersion: "0", minorVersion: 0, jdbcCompliant: false },
    { majorVersion: 0, minorVersion: null, jdbcCompliant: false },
    { majorVersion: 0, minorVersion: 0, jdbcCompliant: "false" },
  ])("rejects invalid metadata rather than displaying invented zeros or No: %j", async fields => {
    const user = userEvent.setup();
    mockApi(url => url.pathname.endsWith("/validate") ? json({
      status: "VALIDATED", driverClass: driver.driverClass, ...fields,
    }) : undefined);
    renderPage("/drivers/driver-1");
    await user.click(await screen.findByRole("button", { name: "Check driver files" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("INVALID_RESPONSE");
    expect(screen.queryByRole("region", { name: "Load check passed" })).not.toBeInTheDocument();
    expect(screen.queryByText("API major/minor version")).not.toBeInTheDocument();
    expect(screen.queryByText("JDBC compliance declaration (API value)")).not.toBeInTheDocument();
  });
  it.each([
    ["/drivers", `Check driver files: ${driver.name}`],
    ["/drivers/driver-1", "Check driver files"],
  ])("refreshes the persisted invalid status after failed validation on %s", async (route, label) => {
    const user = userEvent.setup();
    let invalid = false;
    mockApi((url) => {
      if (url.pathname.endsWith("/validate")) {
        invalid = true;
        return problem("DRIVER_VALIDATION_FAILED", 422);
      }
      const current = { ...driver, status: invalid ? "INVALID" : "VALIDATED" };
      if (url.pathname === "/api/drivers") return json(page([current]));
      if (url.pathname === "/api/drivers/driver-1") return json(current);
      return undefined;
    });
    renderPage(route);
    await user.click(await screen.findByRole("button", { name: label }));
    expect(await screen.findByText("Load check failed", { exact: true })).toBeVisible();
  });
  it("shows companion dependency warnings without claiming complete compatibility", async () => {
    const user = userEvent.setup();
    mockApi((url) => url.pathname.endsWith("/validate") ? json({
      status: "VALIDATED", driverClass: "oracle.jdbc.OracleDriver",
      majorVersion: 21, minorVersion: 13, jdbcCompliant: true,
      warnings: ["ORACLE_I18N_MISSING", "ORACLE_XML_MISSING"],
    }) : undefined);
    renderPage("/drivers/driver-1");
    await user.click(await screen.findByRole("button", { name: "Check driver files" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toBeVisible();
    expect(screen.getByText("Technical details").closest("details")).not.toHaveAttribute("open");
    expect(alert).toHaveTextContent("orai18n.jar");
    expect(alert).toHaveTextContent("xdb.jar");
    expect(alert).toHaveTextContent("xmlparserv2.jar");
    expect(alert).toHaveTextContent("new package");
  });
  it("keeps a referenced package when deletion returns DRIVER_IN_USE", async () => {
    const user = userEvent.setup();
    mockApi((_url, options) =>
      options.method === "DELETE" ? problem("DRIVER_IN_USE") : undefined,
    );
    const { router } = renderPage("/drivers/driver-1");
    await user.click(
      await screen.findByRole("button", { name: "Delete driver package" }),
    );
    await user.click(screen.getByRole("button", { name: "Delete driver" }));
    expect(await screen.findByText(/DRIVER_IN_USE/)).toBeVisible();
    expect(router.state.location.pathname).toBe("/drivers/driver-1");
  });
});
