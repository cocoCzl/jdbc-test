import { t } from "i18next";
import { Link } from "react-router-dom";
import type { Run } from "@/types/api";
import { StatusBadge } from "@/components/common";
import { abortedCount } from "@/lib/outcomes";
import { moduleShortName, profileName, runSelectionName } from "@/lib/presentation";
import { isTerminal, runPresentation } from "@/lib/status";
import { formatNumber, shortId } from "@/lib/format";

export function RunIdentityCell({ run }: { run: Run }) {
  const selection = runSelectionName(run);
  const modules = run.profile === "CUSTOM"
    ? run.selectedModules.map(moduleShortName).join(", ")
    : "";
  const detail = run.profile === "CUSTOM"
    ? [profileName(run.profile), run.selectedModules.length > 1 ? modules : ""].filter(Boolean).join(" · ")
    : "";
  return <div className="run-identity">
    <div className="run-identity-top">
      <Link to={`/runs/${run.id}`} className="run-identity-link rounded-sm" title={t("common.openRun", { id: run.id })}>
        {selection}
      </Link>
      <StatusBadge {...runPresentation[run.status]} />
    </div>
    <div className="run-cell-secondary run-identity-meta">
      <span title={run.id}>{t("ui.run")} {shortId(run.id)}</span>
      {detail ? <span className="run-identity-detail" title={detail}> · {detail}</span> : null}
    </div>
  </div>;
}

function ResultMetric({ label, value, tone }: {
  label: string;
  value: number | null | undefined;
  tone?: "success" | "danger" | "warning";
}) {
  return <span className={`run-result-metric ${tone && value != null && value > 0 ? `run-result-${tone}` : ""}`}>
    <span className="run-result-label">{label}</span>
    <span className="run-result-value">{formatNumber(value)}</span>
  </span>;
}

export function RunResultSummary({ run }: { run: Run }) {
  const notRun = run.accounting?.notRun;
  const incomplete = run.accounting?.incomplete;
  return <div className="run-results">
    <ResultMetric label={t("ui.passed")} value={run.passed} tone="success" />
    <ResultMetric label={t("ui.failed")} value={run.failed} tone="danger" />
    <ResultMetric label={t("ui.skipped")} value={run.skipped} tone="warning" />
    <ResultMetric label={t("ui.aborted")} value={abortedCount(run)} tone="warning" />
    {isTerminal(run.status) && notRun != null && notRun > 0
      ? <div className="run-result-extra"><ResultMetric label={t("ui.notRun")} value={notRun} tone="warning" /></div>
      : null}
    {isTerminal(run.status) && incomplete != null && incomplete > 0
      ? <div className="run-result-extra"><ResultMetric label={t("ui.incomplete")} value={incomplete} tone="warning" /></div>
      : null}
  </div>;
}
