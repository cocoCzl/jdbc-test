import { outcomePresentation } from "./run-outcome";
import { t } from "i18next";
import { Link } from "react-router-dom";
import type { Run } from "@/types/api";
import { StatusBadge } from "@/components/common";
import { abortedCount } from "@/lib/outcomes";
import { runTitle } from "@/lib/presentation";
import { isTerminal } from "@/lib/status";
import { formatNumber } from "@/lib/format";

export function RunIdentityCell({ run }: { run: Run }) {
  const name = runTitle(run);
  return <div className="run-identity">
    <Link to={`/runs/${run.id}`} className="run-identity-link rounded-sm" title={name}>
      {name}
    </Link>
  </div>;
}

export function RunStatusCell({ run }: { run: Run }) {
  return <StatusBadge {...outcomePresentation(run)} />;
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
