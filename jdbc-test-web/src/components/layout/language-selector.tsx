import { Check, ChevronDown, Languages } from "lucide-react";
import { DropdownMenu } from "radix-ui";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";

const languages = [{ value: "zh-CN", label: "简体中文" }, { value: "en", label: "English" }];
export function LanguageSelector() {
  const { t, i18n } = useTranslation();
  const language = i18n.resolvedLanguage === "zh-CN" ? "zh-CN" : "en";
  return <DropdownMenu.Root>
    <DropdownMenu.Trigger asChild>
      <Button variant="outline" size="sm" className="shrink-0 gap-2 bg-white" aria-label={t("language.select")}>
        <Languages aria-hidden="true" className="size-4" />
        <span>{languages.find(item => item.value === language)!.label}</span>
        <ChevronDown aria-hidden="true" className="size-3" />
      </Button>
    </DropdownMenu.Trigger>
    <DropdownMenu.Portal>
      <DropdownMenu.Content align="end" sideOffset={8} aria-label={t("language.menu")}
        className="z-50 min-w-44 rounded-xl border bg-white p-1.5 text-sm shadow-lg">
        <DropdownMenu.RadioGroup value={language} onValueChange={value => { void i18n.changeLanguage(value); }}>
          {languages.map(item => <DropdownMenu.RadioItem key={item.value} value={item.value}
            className="relative flex cursor-pointer items-center justify-between gap-6 rounded-lg px-3 py-2.5 outline-none focus:bg-accent data-[state=checked]:font-semibold data-[state=checked]:text-primary">
            {item.label}
            <DropdownMenu.ItemIndicator><Check aria-hidden="true" className="size-4" /></DropdownMenu.ItemIndicator>
          </DropdownMenu.RadioItem>)}
        </DropdownMenu.RadioGroup>
      </DropdownMenu.Content>
    </DropdownMenu.Portal>
  </DropdownMenu.Root>;
}
