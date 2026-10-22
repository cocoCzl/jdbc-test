import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { finishedRun } from "@/test/fixtures";
import { json, mockApi, renderPage } from "@/test/harness";
import i18n from "@/i18n";
import type { CaseResult } from "@/types/api";

describe("Contract outcome evidence", () => {
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
