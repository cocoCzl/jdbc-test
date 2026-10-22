import { CheckCircle2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { CopyButton } from "@/components/common";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import type { Driver, DriverValidation } from "@/types/api";

export function DriverCheckResult({ driver, result }: { driver: Driver; result: DriverValidation }) {
  const { t } = useTranslation();
  const artifact = result.loadedArtifact;
  const missing = t("ui.driverMetadataNotProvided");
  const fileName = driver.artifacts.find(item => item.sha256 === artifact?.sha256)?.fileName
    ?? artifact?.fileName ?? missing;
  // Manifest versions are independent of both the registered label and Driver API values.
  const fileVersion = artifact?.implementationVersion ?? artifact?.bundleVersion ?? missing;

  return (
    <Card as="section" aria-labelledby="driver-check-title" className="border-success/20">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 gap-3">
          <CheckCircle2 aria-hidden="true" className="mt-0.5 size-6 shrink-0 text-success" />
          <div>
            <h2 id="driver-check-title" className="text-lg font-semibold">
              {t("ui.driverCheckPassed")}
            </h2>
            <p className="mt-1 text-sm text-muted-foreground">{t("ui.driverCheckSummary")}</p>
          </div>
        </div>
        <Button asChild variant="outline" size="sm">
          <Link to="/connections/new">{t("ui.driverCheckNext")}</Link>
        </Button>
      </div>

      <ul aria-label={t("ui.driverCheckScope")} className="mt-5 grid gap-2 text-sm sm:grid-cols-3">
        {[
          [t("ui.driverCheckLoad"), t("ui.driverCheckDone"), true],
          [t("ui.driverCheckConnection"), t("ui.driverCheckNotRun"), false],
          [t("ui.driverCheckCompatibility"), t("ui.driverCheckNotRun"), false],
        ].map(([label, value, passed]) => (
          <li key={String(label)} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border px-3 py-2">
            <span>{label}</span>
            <span className={passed ? "font-medium text-success" : "text-muted-foreground"}>{value}</span>
          </li>
        ))}
      </ul>

      <dl className="mt-5 grid gap-4 text-sm sm:grid-cols-2">
        {[
          [t("ui.driverCheckFile"), fileName],
          [t("ui.driverCheckFileVersion"), fileVersion],
        ].map(([label, value]) => (
          <div key={label} className="min-w-0">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className="mt-1 break-all font-medium">{value}</dd>
          </div>
        ))}
      </dl>

      {result.warnings?.length ? (
        <div role="alert" className="mt-4 rounded-lg border border-warning/30 p-3 text-sm">
          <h3 className="font-medium text-warning">{t("ui.driverValidatedWithWarnings")}</h3>
          <ul className="mt-2 list-disc space-y-2 pl-5">
            {result.warnings.map(warning => (
              <li key={warning}>{t(`driverWarning.${warning}`, { defaultValue: t("ui.driverDependencyWarning") })}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <details className="mt-5 border-t pt-4">
        <summary className="cursor-pointer text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-4">
          {t("ui.driverCheckDetails")}
        </summary>
        <dl className="mt-4 grid gap-5 text-sm sm:grid-cols-2">
          <div className="min-w-0">
            <dt className="text-muted-foreground">{t("ui.driverClass")}</dt>
            <dd className="mt-1 break-all font-mono text-xs">{result.driverClass}</dd>
          </div>
          <div className="min-w-0">
            <dt className="text-muted-foreground">{t("ui.driverCheckHash")}</dt>
            <dd className="mt-1 flex items-start gap-2">
              <code className="min-w-0 break-all text-xs">{artifact?.sha256 ?? missing}</code>
              {artifact?.sha256 ? <CopyButton value={artifact.sha256} label={`${fileName} SHA-256`} /> : null}
            </dd>
          </div>
          <div>
            <dt className="text-muted-foreground">{t("ui.driverReportedVersion")}</dt>
            <dd className="mt-1 font-mono">{result.majorVersion}.{result.minorVersion}</dd>
            <dd className="mt-2 text-xs text-muted-foreground">{t("ui.driverApiVersionSource")}</dd>
            {result.majorVersion === 0 && result.minorVersion === 0 ? (
              <dd className="mt-2 text-xs text-muted-foreground">{t("ui.driverZeroVersionHint")}</dd>
            ) : null}
          </div>
          <div>
            <dt className="text-muted-foreground">{t("ui.driverReportedCompliance")}</dt>
            <dd className="mt-1 font-mono">{String(result.jdbcCompliant)}</dd>
            <dd className="mt-2 text-xs text-muted-foreground">{t("ui.driverComplianceSource")}</dd>
          </div>
          {[
            ["Implementation-Version", artifact?.implementationVersion],
            ["Bundle-Version", artifact?.bundleVersion],
          ].map(([label, value]) => (
            <div key={label} className="min-w-0">
              <dt className="text-muted-foreground">{label}</dt>
              <dd className="mt-1 break-all">{value ?? missing}</dd>
            </div>
          ))}
        </dl>
        <p className="mt-4 text-xs text-muted-foreground">{t("ui.driverCheckDetailsHint")}</p>
      </details>
    </Card>
  );
}
