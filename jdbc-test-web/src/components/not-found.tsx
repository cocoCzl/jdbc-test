import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { EmptyState } from "./common";

export function NotFound() {
  const { t } = useTranslation();
  return (
              <EmptyState
                title={t("ui.pageNotFound")}
                description={t("ui.thisPageIsNotPartOfYour")}
                action={
                  <Link to="/" className="subtle-link">
                    {t("ui.backToDashboard")}</Link>
                }
              />
            );
}
