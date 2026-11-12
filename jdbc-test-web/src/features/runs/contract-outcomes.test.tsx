import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { finishedRun } from "@/test/fixtures";
import { json, mockApi, renderPage } from "@/test/harness";
import i18n from "@/i18n";
import type { CaseResult } from "@/types/api";

describe("Contract outcome evidence", () => {
  it.each(["en", "zh-CN"])("keeps unverified applicability failed and distinct from SUITE in %s", async locale => {
    await i18n.changeLanguage(locale);
    const failure: CaseResult = {
      id: "applicability", scenarioId: "datasource.get_parent_logger", moduleId: "data-source",
      displayName: "Logger applicability fixture", status: "FAILED", durationMs: 1,
      message: "Contract applicability unverified: JDBC 4.1 baseline",
      stackTrace: "Original assertion: null Logger", startedAt: null, finishedAt: null,
      executionEvidence: { conclusion: "NOT_EVALUATED", phase: "EXECUTION", contractInvoked: true },
    };
    const summary = { ...finishedRun, passed: 0, failed: 1, completedCases: 1,
      contractSummary: { NOT_EVALUATED: 1 } };
    const original = { scenario_results: { "datasource.get_parent_logger": failure } };
    mockApi(url => url.pathname.endsWith("/report.json") ? json(original)
      : url.pathname.endsWith("/report") ? json({ summary, failures: [failure] }) : undefined);
    const user = userEvent.setup();
    renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", { name: locale === "en" ? "Report" : "报告" }));
    await user.click(await screen.findByRole("button", { name: i18n.t("record.viewDetails") }));
    const detail = screen.getByRole("dialog");
    expect(within(detail).getByText(i18n.t("record.case.FAILED"))).toBeVisible();
    expect(within(detail).getByText(i18n.t("selection.NOT_EVALUATED"))).toBeVisible();
    expect(within(detail).queryByText(i18n.t("selection.suiteRequirementNote"))).not.toBeInTheDocument();
    expect(within(detail).queryByText(i18n.t("selection.unsupportedNote"))).not.toBeInTheDocument();
    expect(within(detail).getByText(failure.message!)).toBeVisible();
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: i18n.t("report.viewJSON") }));
    expect(JSON.parse((await screen.findByText(/Original assertion: null Logger/)).textContent!)).toEqual(original);
  });

  it.each(["en", "zh-CN"])("presents all five DM suite failures as a subset of eight not evaluated in %s", async locale => {
    await i18n.changeLanguage(locale);
    const ids = ["jdbc_type.array_type_metadata", "jdbc_type.ref_type_metadata",
      "jdbc_type.struct_type_metadata", "jdbc_type.row_id_type_metadata", "statement.result_sequence"];
    const failures: CaseResult[] = ids.map(id => ({
      id, scenarioId: id, moduleId: id.startsWith("jdbc_type") ? "jdbc-type" : "statement",
      displayName: id, status: "FAILED", durationMs: 1,
      message: "Suite fixture requirement unmet", stackTrace: null, startedAt: null, finishedAt: null,
      executionEvidence: { conclusion: "NOT_EVALUATED", requirementScope: "SUITE", phase: "EXECUTION" },
    }));
    const summary = { ...finishedRun, totalCases: 270, completedCases: 270, passed: 254, failed: 16,
      contractSummary: { CONFORMS: 254, CONTRACT_VIOLATION: 8, NOT_EVALUATED: 8, SUITE_REQUIREMENT_UNMET: 5 } };
    const original = { summary: { total: 270, passed: 254, failed: 16 },
      scenario_results: Object.fromEntries(failures.map(row => [row.scenarioId, row.executionEvidence])) };
    mockApi(url => url.pathname.endsWith("/report.json") ? json(original)
      : url.pathname.endsWith("/report") ? json({ summary, failures }) : undefined);
    const user = userEvent.setup();
    renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", { name: locale === "en" ? "Report" : "报告" }));
    for (const [key, count] of [["NOT_EVALUATED", "8"], ["SUITE_REQUIREMENT_UNMET", "5"]]) {
      const label = await screen.findByText(i18n.t(`selection.${key}`));
      expect(within(label.parentElement!).getByText(count)).toBeVisible();
    }
    for (let index = 0; index < ids.length; index++) {
      await user.click(screen.getAllByRole("button", { name: i18n.t("record.viewDetails") })[index]);
      expect(await screen.findByText(i18n.t("selection.suiteRequirementNote"))).toBeVisible();
      await user.keyboard("{Escape}");
    }
    await user.click(screen.getByRole("button", { name: i18n.t("report.viewJSON") }));
    expect(await screen.findByRole("button", { name: i18n.t("ui.downloadJSON") })).toBeVisible();
    expect(JSON.parse(document.querySelector("pre")!.textContent!)).toEqual(original);
  });

  it.each(["en", "zh-CN"])("keeps suite requirements and unsupported APIs distinct in %s", async locale => {
    await i18n.changeLanguage(locale);
    const suite: CaseResult = {
      id: "suite", scenarioId: "fixture.suite", moduleId: "statement", displayName: "Suite fixture",
      status: "FAILED", durationMs: 2,
      message: null, stackTrace: null, startedAt: null, finishedAt: null,
      executionEvidence: { conclusion: "NOT_EVALUATED", requirementScope: "SUITE", phase: "EXECUTION" },
    };
    const unsupported: CaseResult = {
      ...suite, id: "unsupported", scenarioId: "fixture.unsupported", displayName: "Unsupported fixture",
      status: "ABORTED", executionEvidence: { conclusion: "UNSUPPORTED_ALLOWED", contractConforms: true },
    };
    const summary = { ...finishedRun, passed: 0, failed: 1, aborted: 1, completedCases: 2,
      contractSummary: { NOT_EVALUATED: 1, SUITE_REQUIREMENT_UNMET: 1, UNSUPPORTED_ALLOWED: 1 } };
    const original = { scenario_results: { "fixture.suite": suite.executionEvidence,
      "fixture.unsupported": unsupported.executionEvidence } };
    mockApi(url => url.pathname.endsWith("/report.json") ? json(original)
      : url.pathname.endsWith("/report") ? json({ summary, failures: [suite, unsupported] }) : undefined);
    const user = userEvent.setup();
    renderPage("/runs/run-1");
    await user.click(await screen.findByRole("tab", { name: locale === "en" ? "Report" : "报告" }));
    expect(await screen.findByText(i18n.t("selection.SUITE_REQUIREMENT_UNMET"))).toBeVisible();
    await user.click(screen.getAllByRole("button", { name: i18n.t("record.viewDetails") })[0]);
    expect(await screen.findByText(i18n.t("selection.suiteRequirementNote"))).toBeVisible();
    await user.keyboard("{Escape}");
    await user.click(screen.getAllByRole("button", { name: i18n.t("record.viewDetails") })[1]);
    expect(await screen.findByText(i18n.t("selection.unsupportedNote"))).toBeVisible();
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: i18n.t("report.viewJSON") }));
    expect(await screen.findByRole("button", { name: i18n.t("ui.downloadJSON") })).toBeVisible();
    expect(JSON.parse(document.querySelector("pre")!.textContent!)).toEqual(original);
  });
});
