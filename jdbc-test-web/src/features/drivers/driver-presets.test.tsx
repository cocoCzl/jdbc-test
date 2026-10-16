import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { driver, driverPresets } from "@/test/fixtures";
import { json, mockApi, problem, renderPage } from "@/test/harness";
import { chooseOption } from "@/test/select";

const field = (name: string) => screen.getByLabelText(name);
const vendor = () => field("Vendor");
const driverClass = () => field("Driver class (optional)");
const prefix = () => field("JDBC URL prefix (optional)");
type User = ReturnType<typeof userEvent.setup>;
async function select(user: User, code: string) {
  await waitFor(() => expect(field("Driver preset")).toBeEnabled());
  await chooseOption(user, field("Driver preset"), code);
}
async function upload(user: User) {
  await user.type(field("Name"), "Private package");
  await user.type(field("Version"), "private-version");
  await user.upload(field("JAR files"), new File(["jar"], "driver.jar"));
  await user.click(screen.getByRole("button", { name: "Upload driver package" }));
}
async function metadata(api: ReturnType<typeof mockApi>) {
  const request = api.mock.calls.find(([, options]) => options?.method === "POST")!;
  const body = request[1]!.body as FormData;
  const text = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(body.get("metadata") as Blob);
  });
  return JSON.parse(text);
}

describe("New driver presets", () => {
  it("starts empty and switches only untouched suggestions without changing unrelated fields", async () => {
    mockApi();
    const user = userEvent.setup();
    renderPage("/drivers/new");
    expect(vendor()).toHaveValue("");
    expect(driverClass()).toHaveValue("");
    expect(prefix()).toHaveValue("");
    expect(field("Driver preset")).toHaveTextContent("Select a driver preset");
    await user.type(field("Name"), "My package");
    await user.upload(field("JAR files"), new File(["jar"], "kept.jar"));
    await user.click(screen.getByText("Standard DataSource implementations (optional)"));
    await user.type(field("dataSource — Implementation class"), "private.Source");
    await select(user, "postgresql");
    expect(vendor()).toHaveValue("PostgreSQL");
    expect(driverClass()).toHaveValue("org.postgresql.Driver");
    expect(prefix()).toHaveValue("jdbc:postgresql:");
    await select(user, "mysql");
    expect(vendor()).toHaveValue("MySQL");
    expect(driverClass()).toHaveValue("com.mysql.cj.jdbc.Driver");
    expect(prefix()).toHaveValue("jdbc:mysql:");
    await select(user, "custom");
    expect(vendor()).toHaveValue("");
    expect(driverClass()).toHaveValue("");
    expect(prefix()).toHaveValue("");
    expect(field("Name")).toHaveValue("My package");
    expect(field("Version")).toHaveValue("");
    expect(screen.getByText("kept.jar")).toBeVisible();
    expect(field("dataSource — Implementation class")).toHaveValue("private.Source");
  });

  it("preserves explicit edits even when they equal a default, and restores preset ownership explicitly", async () => {
    mockApi();
    const user = userEvent.setup();
    renderPage("/drivers/new");
    // Typing a known default is still manual ownership, before the first preset selection.
    await user.type(vendor(), "PostgreSQL");
    await select(user, "postgresql");
    await user.clear(driverClass());
    await user.type(driverClass(), "org.postgresql.Driver");
    await user.clear(prefix());
    await user.type(prefix(), "jdbc:private:");
    await select(user, "mysql");
    expect(vendor()).toHaveValue("PostgreSQL");
    expect(driverClass()).toHaveValue("org.postgresql.Driver");
    expect(prefix()).toHaveValue("jdbc:private:");
    await select(user, "custom");
    expect(vendor()).toHaveValue("PostgreSQL");
    expect(driverClass()).toHaveValue("org.postgresql.Driver");
    expect(prefix()).toHaveValue("jdbc:private:");
    await select(user, "sqlserver");
    await user.click(screen.getByRole("button", { name: "Restore current preset defaults" }));
    expect(vendor()).toHaveValue("Microsoft");
    expect(driverClass()).toHaveValue("com.microsoft.sqlserver.jdbc.SQLServerDriver");
    expect(prefix()).toHaveValue("jdbc:sqlserver:");
    await select(user, "oracle");
    expect(vendor()).toHaveValue("Oracle");
    expect(driverClass()).toHaveValue("oracle.jdbc.OracleDriver");
    expect(prefix()).toHaveValue("jdbc:oracle:");
  });

  it("keeps a cleared class empty, submits discovery, then protects a server candidate selection", async () => {
    const api = mockApi((_url, options) => options.method === "POST"
      ? problem("DRIVER_CLASS_AMBIGUOUS", 422, { candidates: ["a.Driver", "b.Driver"] })
      : undefined);
    const user = userEvent.setup();
    renderPage("/drivers/new");
    await select(user, "postgresql");
    await user.clear(driverClass());
    await select(user, "mysql");
    expect(driverClass()).toHaveValue("");
    await upload(user);
    expect(await screen.findByRole("alert")).toHaveTextContent("DRIVER_CLASS_AMBIGUOUS");
    expect(await metadata(api)).not.toHaveProperty("driverClass");
    await chooseOption(user, field("Classes returned by the server"), "b.Driver");
    await select(user, "oracle");
    expect(driverClass()).toHaveValue("b.Driver");
    expect(prefix()).toHaveValue("jdbc:oracle:");
    await select(user, "custom");
    expect(driverClass()).toHaveValue("b.Driver");
    expect(prefix()).toHaveValue("");
  });

  it("submits the final actual values without a preset identifier or silent error fallback", async () => {
    const api = mockApi((_url, options) => options.method === "POST"
      ? problem("DRIVER_VALIDATION_FAILED", 422) : undefined);
    const user = userEvent.setup();
    renderPage("/drivers/new");
    await select(user, "postgresql");
    await user.clear(vendor());
    await user.type(vendor(), "Private issuer");
    await user.clear(driverClass());
    await user.type(driverClass(), "private.Driver");
    await user.clear(prefix());
    await user.type(prefix(), "jdbc:private:");
    await upload(user);
    expect(await screen.findByRole("alert")).toHaveTextContent("DRIVER_VALIDATION_FAILED");
    expect(await metadata(api)).toEqual({
      name: "Private package", version: "private-version", vendor: "Private issuer",
      driverClass: "private.Driver", urlPrefix: "jdbc:private:", dataSources: {},
    });
    expect(driverClass()).toHaveValue("private.Driver");
  });

  it("allows manual upload after a preset API failure", async () => {
    const api = mockApi((url, options) => url.pathname === "/api/drivers/presets"
      ? problem("HTTP_ERROR", 503)
      : options.method === "POST" ? json(driver, 201) : undefined);
    const user = userEvent.setup();
    const { router } = renderPage("/drivers/new");
    expect(await screen.findByText(/Driver presets could not be loaded/)).toBeVisible();
    await user.type(vendor(), "Private issuer");
    await upload(user);
    await waitFor(() => expect(router.state.location.pathname).toBe("/drivers/driver-1"));
    expect((await metadata(api)).vendor).toBe("Private issuer");
    expect(await metadata(api)).not.toHaveProperty("driverClass");
  });

  it("does not apply a late preset response to fields typed during loading", async () => {
    let resolve!: (response: Response) => void;
    mockApi((url) => url.pathname === "/api/drivers/presets"
      ? new Promise<Response>((done) => { resolve = done; }) : undefined);
    const user = userEvent.setup();
    renderPage("/drivers/new");
    await user.type(driverClass(), "private.Driver");
    resolve(json(driverPresets));
    await select(user, "postgresql");
    expect(driverClass()).toHaveValue("private.Driver");
    expect(vendor()).toHaveValue("PostgreSQL");
  });

  it("keeps saved metadata and does not fetch presets in the editor", async () => {
    const saved = { ...driver, vendor: "Historical issuer", driverClass: "legacy.Driver", urlPrefix: "jdbc:legacy:" };
    const api = mockApi((url) => url.pathname === "/api/drivers/driver-1" ? json(saved) : undefined);
    const user = userEvent.setup();
    renderPage("/drivers/driver-1");
    await user.click(await screen.findByRole("button", { name: "Edit metadata" }));
    expect(vendor()).toHaveValue("Historical issuer");
    expect(field("Driver class")).toHaveValue("legacy.Driver");
    expect(field("URL prefix")).toHaveValue("jdbc:legacy:");
    expect(screen.queryByLabelText("Driver preset")).not.toBeInTheDocument();
    expect(api.mock.calls.some(([url]) => String(url).includes("/presets"))).toBe(false);
  });
});
