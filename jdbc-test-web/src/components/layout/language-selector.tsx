import { Check } from "lucide-react";
import { DropdownMenu } from "radix-ui";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";

const languages = [{ value: "zh-CN", label: "简体中文" }, { value: "en", label: "English" }];
export function LanguageSelector() {
  const { t, i18n } = useTranslation();
  const language = i18n.resolvedLanguage === "zh-CN" ? "zh-CN" : "en";
  return <DropdownMenu.Root>
    <DropdownMenu.Trigger asChild>
      <Button variant="outline" size="sm" className="jdbc-language" aria-label={t("language.select")}>
        <span>{language === "en" ? "EN" : "中"}</span>
      </Button>
    </DropdownMenu.Trigger>
    <DropdownMenu.Portal>
      <DropdownMenu.Content align="end" sideOffset={8} aria-label={t("language.menu")}
        className="jdbc-menu min-w-44">
        <DropdownMenu.RadioGroup value={language} onValueChange={value => { void i18n.changeLanguage(value); }}>
          {languages.map(item => <DropdownMenu.RadioItem key={item.value} value={item.value}
            className="jdbc-menu-item">
            {item.label}
            <DropdownMenu.ItemIndicator><Check aria-hidden="true" className="size-4" /></DropdownMenu.ItemIndicator>
          </DropdownMenu.RadioItem>)}
        </DropdownMenu.RadioGroup>
      </DropdownMenu.Content>
    </DropdownMenu.Portal>
  </DropdownMenu.Root>;
}
