export type ThemePreference = "system" | "light" | "dark";
export type ResolvedTheme = "light" | "dark";
export const THEME_STORAGE_KEY = "jdbc-test.theme";
const mediaQuery = "(prefers-color-scheme: dark)";

export function createThemeStore() {
  let preference: ThemePreference = "system";
  try {
    const saved = localStorage.getItem(THEME_STORAGE_KEY);
    if (saved === "system" || saved === "light" || saved === "dark") preference = saved;
  } catch { /* Private/blocked storage: keep the in-memory preference. */ }
  const media = typeof window.matchMedia === "function" ? window.matchMedia(mediaQuery) : null;
  const resolve = (): ResolvedTheme => preference === "system" ? (media?.matches ? "dark" : "light") : preference;
  let snapshot = { preference, resolved: resolve() };
  const listeners = new Set<() => void>();
  let listening = false;
  function apply() {
    const root = document.documentElement;
    // CPA's pure-white variant is our light mode; its default warm-gray is not used.
    root.dataset.theme = snapshot.resolved === "light" ? "white" : "dark";
    root.dataset.themePreference = snapshot.preference;
    root.style.colorScheme = snapshot.resolved;
    document.querySelector('meta[name="theme-color"]')?.setAttribute("content", snapshot.resolved === "dark" ? "#151412" : "#ffffff");
  }
  function update() {
    const resolved = resolve();
    if (snapshot.preference === preference && snapshot.resolved === resolved) return;
    snapshot = { preference, resolved };
    apply();
    listeners.forEach(listener => listener());
  }
  function syncListener() {
    const needed = listeners.size > 0 && preference === "system";
    if (needed === listening || !media) return;
    if (needed) media.addEventListener("change", update);
    else media.removeEventListener("change", update);
    listening = needed;
  }
  apply();
  return {
    getSnapshot: () => snapshot,
    subscribe(listener: () => void) {
      listeners.add(listener);
      syncListener();
      update();
      return () => { listeners.delete(listener); syncListener(); };
    },
    setPreference(next: ThemePreference) {
      preference = next;
      try { localStorage.setItem(THEME_STORAGE_KEY, next); } catch { /* Still apply for this session. */ }
      syncListener();
      update();
    },
  };
}
