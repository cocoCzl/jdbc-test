import { useSyncExternalStore } from "react";
import { createThemeStore } from "@/lib/theme-store";

export const themeStore = createThemeStore();
export function useTheme() {
  return useSyncExternalStore(themeStore.subscribe, themeStore.getSnapshot);
}
