import { Monitor, Moon, Sun } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Tooltip } from "@/components/ui/tooltip";
import { themeStore, useTheme } from "@/hooks/use-theme";
const next = { system: "light", light: "dark", dark: "system" } as const;
const icons = { system: Monitor, light: Sun, dark: Moon };

export function ThemeToggle() {
  const { t } = useTranslation();
  const { preference } = useTheme();
  const Icon = icons[preference];
  const label = t("theme.toggle", { current: t(`theme.${preference}`), next: t(`theme.${next[preference]}`) });
  return <Tooltip label={label}><Button variant="outline" size="sm" className="jdbc-theme-toggle"
    aria-label={label} onClick={() => themeStore.setPreference(next[preference])}>
    <Icon aria-hidden="true" />
  </Button></Tooltip>;
}
