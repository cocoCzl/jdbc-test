import { useTranslation } from "react-i18next";
import { Field } from "@/components/common";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import "./run-password.scss";

export type RunPasswordMode = "saved" | "other" | "empty";

export function RunPassword({ hasSavedPassword, mode, password, onModeChange, onPasswordChange }: {
  hasSavedPassword: boolean;
  mode: RunPasswordMode;
  password: string;
  onModeChange: (mode: RunPasswordMode) => void;
  onPasswordChange: (password: string) => void;
}) {
  const { t } = useTranslation();
  return <section className="run-password" aria-labelledby="run-password-title">
    <h3 id="run-password-title" className="text-sm font-semibold">{t("ui.passwordForThisRun")}</h3>
    <RadioGroup className="run-password-options" aria-labelledby="run-password-title" value={mode}
      onValueChange={value => {
        if (value === "saved" || value === "other" || value === "empty") onModeChange(value);
      }}>
      {(["saved", "other", "empty"] as const).map(option => <label key={option} htmlFor={`run-password-${option}`}
        className="run-password-option" data-selected={mode === option} data-disabled={option === "saved" && !hasSavedPassword}>
        <RadioGroupItem id={`run-password-${option}`} value={option} disabled={option === "saved" && !hasSavedPassword}
          aria-describedby={option === "saved" && !hasSavedPassword ? "run-password-unavailable" : undefined} />
        {t(`runPassword.${option}`)}
      </label>)}
    </RadioGroup>
    {!hasSavedPassword ? <p id="run-password-unavailable" className="text-xs text-muted-foreground">{t("runPassword.unavailable")}</p> : null}
    {mode === "other" ? <div className="run-password-input">
      <Field id="run-password" label={t("ui.password")} type="password" autoComplete="new-password" required
        maxLength={2000} value={password} onChange={event => onPasswordChange(event.target.value)}
        hint={t("runPassword.hint")} />
    </div> : null}
  </section>;
}
