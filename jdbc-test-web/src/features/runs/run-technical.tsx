import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { Run } from "@/types/api";
import { CopyButton } from "@/components/common";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { legacyCaseFailureReport } from "./run-outcome";

function TechnicalValue({ value, label }: { value: string; label: string }) {
  return <span className="run-technical-value"><code>{value}</code><CopyButton value={value} label={label} /></span>;
}
function DiagnosticRows({ rows }: { rows: [string, ReactNode][] }) {
  return <dl className="run-diagnostic-rows">{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>;
}
export function RunTechnical({ run }: { run: Run }) {
  const { t } = useTranslation();
  const [rawOpen, setRawOpen] = useState(false);
  const originalConfig = { adapterSnapshot: run.adapterSnapshot, sqlPackage: run.sqlPackage,
    selectionSnapshot: run.selectionSnapshot, executionScope: run.executionScope, sqlDialect: run.sqlDialect };
  return <details className="run-technical">
    <summary>{t("ui.driverCheckDetails")}</summary>
    <div className="run-technical-groups">
      <section>
        <h3>{t("record.diagnostics")}</h3>
        <DiagnosticRows rows={[
          [t("record.originalStatus"), run.status],
          [t("record.errorCode"), run.failureReason ?? "—"],
          [t("record.termination"), run.workerTerminationReason ?? t("record.notRecorded")],
          [t("record.workerFingerprint"), run.workerArtifactSha256 ? <TechnicalValue value={run.workerArtifactSha256} label={t("record.workerFingerprint")} /> : t("record.notRecorded")],
          [t("record.exitCode"), run.workerExitCode ?? t("record.notRecorded")],
          [t("record.reportValidation"), run.workerReportValidated === undefined ? t("record.notRecorded") : t(run.workerReportValidated ? "record.validated" : "record.notValidated")],
        ]} />
        {run.historicalOutcome || legacyCaseFailureReport(run) ? <p className="mt-3 text-xs text-muted-foreground">{t("record.legacyExplanation")} {t("record.legacyExitEvidence")}</p> : null}
        {run.executionIssues?.length ? <details className="mt-3"><summary>{t("ui.executionIssues")}</summary><pre className="run-evidence mt-2">{JSON.stringify(run.executionIssues, null, 2)}</pre></details>
          : <p className="mt-3 text-xs text-muted-foreground">{t("record.noIssues")}</p>}
        {run.workerFinalEvent ? <details className="mt-3"><summary>{t("record.finalEvent")}</summary><pre className="run-evidence mt-2">{JSON.stringify(run.workerFinalEvent, null, 2)}</pre></details> : null}
      </section>
      <section>
        <h3>{t("record.configuration")}</h3>
        <DiagnosticRows rows={[
          [t("ui.runID"), <TechnicalValue value={run.id} label={t("ui.runID")} />],
          [t("ui.driverClass"), run.driver.driverClass],
          [t("connection.sqlDialect"), run.sqlDialect ?? t("record.notRecorded")],
          [t("ui.driverFingerprint"), run.driver.packageFingerprint ? <TechnicalValue value={run.driver.packageFingerprint} label={t("ui.driverFingerprint")} /> : "—"],
          ...(run.adapterSnapshot?.fingerprint ? [[t("record.adapterFingerprint"), <TechnicalValue value={run.adapterSnapshot.fingerprint} label={t("record.adapterFingerprint")} />] as [string, ReactNode]] : []),
        ]} />
        <Button variant="outline" size="sm" className="mt-3" onClick={() => setRawOpen(true)}>{t("record.rawConfiguration")}</Button>
      </section>
    </div>
    <Dialog open={rawOpen} onOpenChange={setRawOpen} title={t("record.rawConfiguration")} description={t("ui.savedWhenTheRunWasCreatedLater")}>
      <pre className="run-evidence">{JSON.stringify(originalConfig, null, 2)}</pre>
      <CopyButton value={JSON.stringify(originalConfig, null, 2)} label={t("record.rawConfiguration")} />
    </Dialog>
  </details>;
}
