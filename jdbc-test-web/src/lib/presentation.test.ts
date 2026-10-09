import { describe, it, expect } from "vitest";
import i18n from "@/i18n";
import { moduleName, runSelectionName, runTitle } from "./presentation";
const connection = {name:"Long Oracle Connection 快照"};
describe("Stable-ID display titles", () => {
  it.each(["en", "zh-CN"])("keeps Core/Full/multi-module titles concise in %s", async locale => {
    await i18n.changeLanguage(locale);
    for (const profile of ["CORE","FULL","CUSTOM"]) {
      const run = {profile,selectedModules:["connection","statement"],connection};
      expect(runTitle(run)).toBe(`${i18n.t(`runTitle.${profile}`)} · ${connection.name}`);
      expect(runTitle(run)).not.toContain(" + ");
    }
    expect(runSelectionName({profile:"CUSTOM",selectedModules:["connection"]})).toBe(i18n.t("moduleShort.connection"));
    expect(moduleName("future-module", "Backend provided name")).toBe("Backend provided name");
  });
  it("never modifies snapshots or enum/ID data during translation", async () => {
    const run = {profile:"FULL", selectedModules:["connection","statement"],connection}; const raw=JSON.stringify(run);
    for(const locale of ["zh-CN","en"]) {await i18n.changeLanguage(locale); runTitle(run);}
    expect(JSON.stringify(run)).toBe(raw);
  });
});
