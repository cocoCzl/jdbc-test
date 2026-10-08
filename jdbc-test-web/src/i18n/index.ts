import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import en from "./en.json";
import zhCN from "./zh-CN.json";

export const localeStorageKey = "jdbc-test.locale";
export type Locale = "en" | "zh-CN";
export const resources = { en: { translation: en }, "zh-CN": { translation: zhCN } };

export function resolveLocale(saved: string | null, languages: readonly string[]): Locale {
  if (saved === "en" || saved === "zh-CN") return saved;
  for (const language of languages) {
    const tag = language.toLowerCase();
    if (tag === "en" || tag.startsWith("en-")) return "en";
    if (["zh", "zh-cn", "zh-sg", "zh-hans"].includes(tag) || tag.startsWith("zh-hans-")) return "zh-CN";
  }
  return "en";
}

export function initialLocale(): Locale {
  let saved: string | null = null;
  try { saved = localStorage.getItem(localeStorageKey); } catch { /* Storage can be blocked. */ }
  return resolveLocale(saved, typeof navigator === "undefined" ? [] : navigator.languages?.length ? navigator.languages : [navigator.language]);
}

function syncLanguage(language: string) {
  const locale = language === "zh-CN" ? "zh-CN" : "en";
  document.documentElement.lang = locale;
  try { localStorage.setItem(localeStorageKey, locale); } catch { /* The preference is optional. */ }
}

i18n.use(initReactI18next).init({
  resources,
  lng: initialLocale(),
  supportedLngs: ["en", "zh-CN"],
  fallbackLng: "en",
  load: "currentOnly",
  keySeparator: false,
  initAsync: false,
  interpolation: { escapeValue: false },
  returnNull: false,
  returnEmptyString: false,
});
document.documentElement.lang = i18n.language;
i18n.on("languageChanged", syncLanguage);
export default i18n;
