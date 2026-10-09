import { chooseOption } from "@/test/select";
import i18n from "@/i18n";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { driver, page } from "@/test/fixtures";
import { json, mockApi, problem, renderPage } from "@/test/harness";
import { validateFiles } from "@/lib/driver-upload";

describe("Driver UI", () => {
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
    await user.type(screen.getByLabelText("Version"), "1");
    await user.upload(
      screen.getByLabelText("JAR files"),
      new File(["jar"], "driver.jar"),
    );
    await user.click(
      screen.getByRole("button", { name: "Upload driver package" }),
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
        ? json({ ...driver, name: "Renamed" })
        : undefined,
    );
    renderPage("/drivers/driver-1");
    await user.click(
      await screen.findByRole("button", { name: "Edit metadata" }),
    );
    await user.clear(screen.getByLabelText("Name"));
    await user.type(screen.getByLabelText("Name"), "Renamed");
    await user.click(screen.getByRole("button", { name: "Save metadata" }));
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
    expect(body).not.toHaveProperty("artifacts");
    expect(body).not.toHaveProperty("storageKey");
  });
  it("renders the paginated list and artifact counts", async () => {
    mockApi();
    renderPage("/drivers");
    expect(
      await screen.findByRole("link", { name: driver.name }),
    ).toBeVisible();
    expect(screen.getByText("Validated")).toBeVisible();
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
    await user.type(await screen.findByLabelText("Name"), "PostgreSQL JDBC");
    await user.type(screen.getByLabelText("Vendor"), "PostgreSQL");
    await user.type(screen.getByLabelText("Version"), "42.7.10");
    await user.upload(screen.getByLabelText("JAR files"), [
      new File(["jar"], "one.jar"),
      new File(["jar"], "two.jar"),
      new File(["jar"], "remove.jar"),
    ]);
    await user.click(screen.getByRole("button", { name: "Remove remove.jar" }));
    expect(screen.getByText("2 artifacts")).toBeVisible();
    await user.click(
      screen.getByRole("button", { name: "Upload driver package" }),
    );
    await waitFor(() =>
      expect(router.state.location.pathname).toBe("/drivers/driver-1"),
    );
    const body = api.mock.calls.find(
      ([, options]) => options?.method === "POST",
    )![1]!.body as FormData;
    expect(body.getAll("files")).toHaveLength(2);
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
      await screen.findByRole("button", { name: "Validate driver" }),
    );
    expect(
      await screen.findByText("Validated in an isolated Worker"),
    ).toBeVisible();
    expect(screen.getByText(/Version 42.7/)).toHaveTextContent(
      "JDBC compliant: No",
    );
    expect(document.body.textContent).not.toMatch(/storageKey|workspacePath/);
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
