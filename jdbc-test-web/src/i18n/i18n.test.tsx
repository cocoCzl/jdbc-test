import { chooseOption } from "@/test/select";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import ts from "typescript";
import i18n, { initialLocale, localeStorageKey, resolveLocale, resources } from ".";
import { errorMessage, ApiError } from "@/api/client";
import { formatDateTime, formatDuration, formatNumber, plural } from "@/lib/format";
import { moduleName } from "@/lib/presentation";
import { runPresentation, resultPresentation } from "@/lib/status";
import { json, mockApi, renderPage } from "@/test/harness";
import { catalog, connection, driver, finishedRun, page, result, run } from "@/test/fixtures";
import { TestEventSource } from "@/test/test-event-source";
import { runsKeys } from "@/api/runs";

async function change(language: "en" | "zh-CN") { await act(async () => { await i18n.changeLanguage(language); }); }
async function chooseChinese(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Language" }));
  const item = await screen.findByRole("menuitemradio", {name: "简体中文"});
  await user.click(item);
}

describe("Bundled translation contract", () => {
  const en = resources.en.translation as Record<string, string>;
  const zh = resources["zh-CN"].translation as Record<string, string>;
  it("has direct Chinese resources and matching interpolation variables for every English key, respecting plural rules", () => {
    for (const [key, value] of Object.entries(en)) {
      const chineseKey = key.replace(/_(one|other)$/, "_other");
      expect(zh[chineseKey], key).toBeTruthy();
      const variables = (text: string) => [...text.matchAll(/{{\s*([^},\s]+)/g)].map(match => match[1]).sort();
      expect(variables(zh[chineseKey]), key).toEqual(variables(value));
    }
    expect(new Intl.PluralRules("zh-CN").resolvedOptions().pluralCategories).toEqual(["other"]);
  });
  it("checks source translation references without allowing fallback to hide missing keys or hardcoded JSX text", () => {
    const source = `${process.cwd()}/src`;
    const staticExceptions = new Set(["jdbc-test", "SHA-256"]);
    function scan(dir: string) {
      for (const entry of readdirSync(dir, {withFileTypes: true})) {
        const path = `${dir}/${entry.name}`;
        if (entry.isDirectory()) { if (!["i18n", "test", "types"].includes(entry.name)) scan(path); continue; }
        if (!/\.tsx?$/.test(entry.name) || entry.name.includes(".test.")) continue;
        const tree = ts.createSourceFile(path, readFileSync(path, "utf8"), ts.ScriptTarget.Latest, true);
        function visit(node: ts.Node) {
          if (ts.isCallExpression(node) && (node.expression.getText(tree) === "t" || node.expression.getText(tree) === "i18n.t") && node.arguments[0] && ts.isStringLiteral(node.arguments[0])) {
            const key = node.arguments[0].text;
            expect(en[key] ?? en[`${key}_other`], `${path}: ${key}`).toBeTruthy();
            expect(zh[key] ?? zh[`${key}_other`], `${path}: ${key}`).toBeTruthy();
          }
          if (ts.isJsxText(node)) {
            const text = node.text.trim();
            if (/[A-Za-z]/.test(text)) expect(staticExceptions.has(text), `${path}: ${text}`).toBe(true);
          }
          ts.forEachChild(node, visit);
        }
        visit(tree);
      }
    }
    scan(source);
  });
  it("uses a safe English fallback while direct-resource checks detect the missing Chinese entry", async () => {
    const key = "ui.workspace";
    const original = i18n.getResource("zh-CN", "translation", key);
    delete (i18n.getResourceBundle("zh-CN", "translation") as Record<string, string>)[key];
    try {
      await change("zh-CN");
      expect(i18n.getResource("zh-CN", "translation", key)).toBeUndefined();
      expect(i18n.t(key)).toBe("Workspace");
    } finally { i18n.addResource("zh-CN", "translation", key, original); }
  });
  it("localizes the server's stable validation, storage and availability errors directly in both languages", () => {
    for (const code of ["VALIDATION_ERROR", "INVALID_SELECTION", "DRIVER_STORAGE_ERROR", "DRIVER_ARTIFACT_INVALID", "DRIVER_ARTIFACT_TOO_LARGE", "DRIVER_INVALID", "WORKER_UNAVAILABLE", "SERVER_SHUTTING_DOWN", "HTTP_ERROR"]) {
      expect(en[`errors.${code}`], code).toBeTruthy();
      expect(zh[`errors.${code}`], code).toMatch(/[\u4e00-\u9fff]/);
    }
  });
});

describe("Locale selection and formatting", () => {
  it.each([
    ["zh-CN", ["en-US"], "zh-CN"], ["en", ["zh-CN"], "en"],
    [null, ["zh-CN"], "zh-CN"], [null, ["zh-Hans-SG"], "zh-CN"],
    [null, ["fr-FR", "en-GB"], "en"], ["invalid", ["zh-CN"], "zh-CN"],
    [null, ["ja", "zh-TW"], "en"], [null, [], "en"],
  ])("resolves %s / %j to %s", (saved, languages, expected) => expect(resolveLocale(saved, languages)).toBe(expected));
  it("survives denied storage reads/writes and still changes document language", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("denied"); });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("denied"); });
    vi.spyOn(navigator, "languages", "get").mockReturnValue(["zh-CN"]);
    expect(initialLocale()).toBe("zh-CN");
    await change("zh-CN");
    expect(document.documentElement.lang).toBe("zh-CN");
  });
  it("persists only the language preference and localizes numbers, durations, plurals and local-time dates", async () => {
    const date = new Date(2026, 9, 7, 8, 9);
    expect(plural(1, "record")).toBe("1 record");
    expect(plural(2, "record")).toBe("2 records");
    const english = formatDateTime(date.toISOString());
    await change("zh-CN");
    expect(plural(1, "record")).toBe("1 条记录");
    expect(plural(2, "record")).toBe("2 条记录");
    expect(formatNumber(1234)).toBe(new Intl.NumberFormat("zh-CN").format(1234));
    expect(formatDuration(62000)).toBe("1 分 2 秒");
    expect(formatDateTime(date.toISOString())).not.toBe(english);
    expect(formatDateTime(date.toISOString())).toBe(new Intl.DateTimeFormat("zh-CN", {year:"numeric",month:"2-digit",day:"2-digit",hour:"2-digit",minute:"2-digit",hourCycle:"h23"}).format(date));
    expect(localStorage.getItem(localeStorageKey)).toBe("zh-CN");
    expect(localStorage.length).toBe(1);
    expect(initialLocale()).toBe("zh-CN");
  });
  it("localizes stable errors and keeps unknown sanitized details and technical identifiers intact", async () => {
    const known = new ApiError(422, "EXECUTION_SCOPE_REQUIRED");
    const unknown = new ApiError(422, "VENDOR_UNKNOWN", "SQLState: 08001; safe vendor detail");
    await change("zh-CN");
    expect(errorMessage(known)).toContain("明确授权");
    expect(errorMessage(unknown)).toContain("SQLState: 08001; safe vendor detail");
    expect(errorMessage(new Error("secret"))).not.toContain("secret");
    expect(moduleName("connection")).toBe("连接（Connection）");
    expect(moduleName("vendor-module", "Vendor custom label")).toBe("Vendor custom label");
    expect(runPresentation.SUCCEEDED.label).toBe("已完成");
    expect(runPresentation.FAILED.label).toBe("运行失败");
    expect(resultPresentation.FAILED.label).toBe("失败");
  });
});

describe("Switching language preserves the workspace", () => {
  it("has a keyboard-operable checked language menu and retains route, filters, sort and pagination without mutations", async () => {
    const user = userEvent.setup();
    const fetch = mockApi(url => url.pathname === "/api/runs" ? json(page([finishedRun], {totalPages: 3, totalElements: 45, page: Number(url.searchParams.get("page") ?? 0)})) : undefined);
    const view = renderPage("/runs");
    await screen.findByRole("heading", {name: "Run records"});
    await chooseOption(user, screen.getByLabelText("Filter run status"), "COMPLETED");
    await chooseOption(user, screen.getByLabelText("Sort runs"), "finishedAt,desc");
    await user.click(await screen.findByRole("button", {name: "Next page"}));
    await waitFor(() => expect(screen.getByText(/Page 2 of 3/)).toBeVisible());
    const button = screen.getByRole("button", {name: "Language"}); button.focus();
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("menuitemradio", {name: "English"})).toHaveAttribute("aria-checked", "true");
    await user.keyboard("{Home}{Enter}");
    await screen.findByRole("heading", {name: "运行记录"});
    expect(screen.getByRole("columnheader", {name: "连接"})).toBeVisible();
    expect(screen.getByLabelText("筛选运行状态")).toHaveAttribute("data-value", "COMPLETED");
    expect(screen.getByLabelText("运行排序")).toHaveAttribute("data-value", "finishedAt,desc");
    expect(screen.getByText(/第 2 页，共 3 页/)).toBeVisible();
    expect(view.router.state.location.pathname).toBe("/runs");
    expect(fetch.mock.calls.filter(([,options]) => options?.method && options.method !== "GET")).toHaveLength(0);
  });
  it("retains unsaved connection input and translates an existing validation error without probe/save", async () => {
    const user = userEvent.setup(); const fetch = mockApi(); renderPage("/connections/new");
    await user.type(await screen.findByLabelText("Name"), "My untouched name");
    await chooseOption(user, screen.getByLabelText("Database type"), "compatible");
    await chooseOption(user, screen.getByLabelText("Built-in compatibility dialect"), "postgresql");
    await user.type(screen.getByLabelText("JDBC URL"), "jdbc:custom://fixture.invalid/mydb");
    await chooseOption(user, screen.getByLabelText("Driver package"), driver.id);
    await user.type(screen.getByLabelText("Authorized test database"), "test");
    await user.type(screen.getByLabelText("Authorized test schema"), "owned_scope");
    await user.click(screen.getByRole("button", {name: "Save connection"}));
    await chooseChinese(user);
    expect(screen.getByLabelText("名称")).toHaveValue("My untouched name");
    expect(screen.getByLabelText("JDBC URL")).toHaveValue("jdbc:custom://fixture.invalid/mydb");
    expect(screen.getByLabelText("内置兼容方言")).toHaveAttribute("data-value", "postgresql");
    expect(screen.getByRole("alert")).toHaveTextContent("请填写密码。");
    expect(fetch.mock.calls.filter(([,options]) => options?.method === "POST")).toHaveLength(0);
  });
  it("preserves Wizard choices, consent and exact API payload across switches", async () => {
    const user = userEvent.setup();
    const fetch = mockApi((url, options) => url.pathname === "/api/runs" && options.method === "POST" ? json(finishedRun, 201) : undefined);
    renderPage("/runs/new");
    await user.click(await screen.findByRole("radio", {name: connection.name}));
    await user.click(screen.getByRole("button", {name: "Next: Choose driver"}));
    await user.click(screen.getByRole("button", {name: "Next: Select tests"}));
    await user.click(screen.getByRole("checkbox", {name: "Connection"}));
    await chooseChinese(user);
    expect(screen.getByRole("checkbox", {name: /语句（Statement）/})).toBeChecked();
    expect(screen.getByRole("checkbox", {name: /连接（Connection）/})).not.toBeChecked();
    await user.click(screen.getByRole("button", {name: "下一步：核对运行"}));
    await user.click(screen.getByRole("checkbox", {name: /我了解/}));
    await change("en");
    expect(screen.getByRole("checkbox", {name: /I understand/})).toBeChecked();
    await change("zh-CN");
    await user.click(screen.getByRole("button", {name: "开始测试运行"}));
    await waitFor(() => expect(fetch.mock.calls.some(([,opts]) => opts?.method === "POST")).toBe(true));
    const request = fetch.mock.calls.find(([,opts]) => opts?.method === "POST")![1]!;
    expect(JSON.parse(request.body as string)).toMatchObject({connectionId: connection.id, driverId: driver.id, profile: "CUSTOM", modules: [], destructiveConsent: true});
    expect(JSON.parse(request.body as string).includeScenarios).toHaveLength(23);
  });
  it("keeps cached raw business data and a single SSE subscription while live status updates translate", async () => {
    const fetch = mockApi(url => url.pathname === "/api/runs/run-1" ? json(run) : undefined);
    const view = renderPage("/runs/run-1");
    await screen.findAllByText("Running");
    await waitFor(() => expect(TestEventSource.instances).toHaveLength(1));
    const before = structuredClone(view.client.getQueryData(runsKeys.detail(run.id)));
    const source = TestEventSource.instances[0];
    const requests = fetch.mock.calls.length;
    await change("zh-CN");
    expect(screen.getAllByText("运行中")[0]).toBeVisible();
    expect(TestEventSource.instances).toHaveLength(1);
    expect(source.closed).toBe(false);
    expect(fetch.mock.calls).toHaveLength(requests);
    expect(view.client.getQueryData(runsKeys.detail(run.id))).toEqual(before);
    act(() => source.emit("run.status", {runId: run.id, status: "GENERATING_REPORT"}));
    expect(await screen.findByText("正在生成报告")).toBeVisible();
    expect(view.client.getQueryData(runsKeys.detail(run.id))).toHaveProperty("status", "GENERATING_REPORT");
  });
  it("retains opened skipped details, Results filters and original report JSON", async () => {
    const user = userEvent.setup(); const raw = {status: "SUCCEEDED", profile: "FULL", SQLState: "00000", evidence: {capability: "arrays", reason: "capability_disabled"}};
    mockApi(url => url.pathname.endsWith("/results") ? json(page([{...result, status: "SKIPPED", message: "capability arrays=false; skipSource=adapter"}])) : url.pathname.endsWith("/report.json") ? json(raw) : undefined);
    const view = renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", {name: "Test cases"}));
    await chooseOption(user, screen.getByLabelText("Filter result status"), "SKIPPED");
    await user.click(await screen.findByRole("button", {name: "View details"}));
    await change("zh-CN");
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("capability arrays=false; skipSource=adapter")).toBeVisible();
    expect(within(dialog).getByText(/前提不足或历史能力门控/)).toBeVisible();
    await user.click(within(dialog).getByRole("button", {name: "关闭弹窗"}));
    expect(screen.getByLabelText("筛选结果状态")).toHaveAttribute("data-value", "SKIPPED");
    await user.click(screen.getByRole("tab", {name: "报告"}));
    await user.click(await screen.findByRole("button", {name: "查看报告 JSON"}));
    await waitFor(() => expect(screen.getByRole("dialog").querySelector("pre")).toHaveTextContent("capability_disabled"));
    await change("en");
    expect(JSON.parse(screen.getByRole("dialog").querySelector("pre")!.textContent!)).toEqual(raw);
    expect(view.client.getQueryData(["raw-report", run.id])).toEqual(raw);
  });
  it("translates Report failure status without changing the cached enum or original evidence", async () => {
    const user = userEvent.setup();
    mockApi(url => url.pathname.endsWith("/report") ? json({summary: finishedRun, failures: [result]}) : undefined);
    const view = renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", {name: "Report"}));
    expect(await screen.findByText(`${result.scenarioId} · Failed`)).toBeVisible();
    await change("zh-CN");
    expect(screen.getByText(`${result.scenarioId} · 失败`)).toBeVisible();
    expect(view.client.getQueryData(runsKeys.report(run.id))).toEqual({summary: finishedRun, failures: [result]});
  });
  it.each([
    ["/", "Workspace overview", "工作区概览"], ["/drivers", "Driver packages", "驱动包管理"],
    ["/drivers/new", "Add a driver", "添加驱动"], ["/drivers/driver-1", driver.name, driver.name],
    ["/connections", "Connections", "连接管理"], ["/connections/new", "Add a connection", "添加连接"],
    ["/connections/connection-1", connection.name, connection.name], ["/runs/new", "New test run", "新建测试运行"],
    ["/missing", "Page not found", "页面不存在"],
  ])("renders %s in both languages without replacing domain names", async (route, english, chinese) => {
    mockApi(); renderPage(route);
    await screen.findByRole("heading", {name: english});
    await change("zh-CN");
    expect(await screen.findByRole("heading", {name: chinese})).toBeVisible();
    expect(screen.getByRole("button", {name: "界面语言"})).toHaveTextContent("中");
    expect(catalog.modules[0].id).toBe("connection");
    expect(driver.driverClass).toBe("org.postgresql.Driver");
  });
});
