import { beforeEach, describe, expect, it, vi } from "vitest";
import { createThemeStore, THEME_STORAGE_KEY } from "./theme-store";

function system(dark = false) {
  const events = new EventTarget();
  const media = {
    matches: dark,
    addEventListener: vi.fn(events.addEventListener.bind(events)),
    removeEventListener: vi.fn(events.removeEventListener.bind(events)),
  };
  vi.stubGlobal("matchMedia", vi.fn(() => media));
  return { media, change(value: boolean) { media.matches = value; events.dispatchEvent(new Event("change")); } };
}
beforeEach(() => { document.documentElement.removeAttribute("data-theme"); });

describe("theme preference and lifecycle", () => {
  it.each([null, "invalid", "white", "DARK"])("defaults %s to system without persisting a resolved color", saved => {
    if (saved !== null) localStorage.setItem(THEME_STORAGE_KEY, saved);
    system(true);
    const store = createThemeStore();
    expect(store.getSnapshot()).toEqual({preference: "system", resolved: "dark"});
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe(saved);
    expect(document.documentElement).toHaveAttribute("data-theme", "dark");
    expect(document.documentElement.style.colorScheme).toBe("dark");
  });
  it("follows changes only in system, persists the choice and cleans up its single listener", () => {
    const os = system();
    const store = createThemeStore();
    const notify = vi.fn();
    const off = store.subscribe(notify);
    const off2 = store.subscribe(() => {});
    expect(os.media.addEventListener).toHaveBeenCalledTimes(1);
    os.change(true);
    expect(store.getSnapshot()).toEqual({preference:"system", resolved:"dark"});
    store.setPreference("light");
    expect(os.media.removeEventListener).toHaveBeenCalledTimes(1);
    os.change(false); os.change(true);
    expect(store.getSnapshot().resolved).toBe("light");
    expect(document.documentElement).toHaveAttribute("data-theme", "white");
    store.setPreference("dark");
    os.change(false);
    expect(store.getSnapshot().resolved).toBe("dark");
    store.setPreference("system");
    expect(store.getSnapshot()).toEqual({preference:"system", resolved:"light"});
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("system");
    expect(os.media.addEventListener).toHaveBeenCalledTimes(2);
    off(); expect(os.media.removeEventListener).toHaveBeenCalledTimes(1);
    off2(); expect(os.media.removeEventListener).toHaveBeenCalledTimes(2);
    const calls = notify.mock.calls.length;
    os.change(true);
    expect(notify).toHaveBeenCalledTimes(calls);
    const cleanup = store.subscribe(notify);
    expect(store.getSnapshot().resolved).toBe("dark");
    cleanup();
  });
  it.each(["light", "dark", "system"] as const)("restores %s on refresh", preference => {
    system(true);
    createThemeStore().setPreference(preference);
    expect(createThemeStore().getSnapshot()).toEqual({preference, resolved: preference === "light" ? "light" : "dark"});
  });
  it("works when reads and writes throw and matchMedia is unavailable", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new DOMException("Denied", "SecurityError"); });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new DOMException("Denied", "SecurityError"); });
    vi.stubGlobal("matchMedia", undefined);
    const store = createThemeStore();
    expect(store.getSnapshot()).toEqual({preference:"system", resolved:"light"});
    const off = store.subscribe(() => {});
    store.setPreference("dark");
    expect(store.getSnapshot()).toEqual({preference:"dark", resolved:"dark"});
    off();
  });
});
