import { useState } from "react";
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { moduleName } from "@/lib/presentation";
import type { Catalog } from "@/types/api";

export function TestSelection({ catalog, selected, onChange }: {
  catalog: Catalog; selected: Set<string>; onChange: (value: Set<string>) => void;
}) {
  useTranslation();
  const [search, setSearch] = useState("");
  const needle = search.trim().toLocaleLowerCase();
  function toggle(ids: string[], checked: boolean) {
    const next = new Set(selected);
    ids.forEach(id => { if (checked) next.add(id); else next.delete(id); });
    onChange(next);
  }
  return <div className="space-y-4">
    <p className="text-sm text-muted-foreground">{t("selection.scopeHint")}</p>
    <label className="block text-sm">{t("selection.search")}
      <input type="search" value={search} onChange={event => setSearch(event.target.value)}
        className="mt-2 w-full rounded-md border bg-background p-2" />
    </label>
    <div className="flex flex-wrap items-center gap-2">
      <Button variant="outline" size="sm" onClick={() => onChange(new Set(catalog.scenarios.map(item => item.id)))}>{t("selection.all")}</Button>
      <Button variant="outline" size="sm" onClick={() => onChange(new Set())}>{t("selection.clear")}</Button>
      <Button variant="ghost" size="sm" onClick={() => onChange(new Set(catalog.scenarios.map(item => item.id)))}>{t("selection.restore")}</Button>
      <span className="text-xs text-muted-foreground">{t("selection.batchHint")}</span>
    </div>
    {(["STANDARD_JDBC", "VENDOR_EXTENSION"] as const).map(category => {
      const rows = catalog.scenarios.filter(item => (item.category ?? "STANDARD_JDBC") === category);
      if (!rows.length) return null;
      return <section key={category} className="space-y-3" aria-label={t(`selection.${category}`)}>
        <h3 className="font-semibold">{t(`selection.${category}`)} <span className="text-muted-foreground">({rows.length})</span></h3>
        {catalog.modules.map(module => {
          const all = rows.filter(item => item.moduleId === module.id);
          const visible = all.filter(item => `${item.id} ${item.displayName} ${module.name}`.toLocaleLowerCase().includes(needle));
          if (!visible.length) return null;
          const count = all.filter(item => selected.has(item.id)).length;
          const label = moduleName(module.id, module.name);
          return <div key={module.id} className="rounded-xl border p-3">
            <div className="flex items-center gap-3">
              <input type="checkbox" aria-label={label} aria-checked={count > 0 && count < all.length ? "mixed" : count === all.length}
                ref={element => { if (element) element.indeterminate = count > 0 && count < all.length; }}
                className="size-4 shrink-0 accent-primary" checked={count === all.length}
                onChange={event => toggle(all.map(item => item.id), event.target.checked)} />
              <span className="font-medium">{label}</span>
              <span className="ml-auto whitespace-nowrap text-sm text-muted-foreground">{t("selection.moduleCount", { selected: count, total: all.length })}</span>
            </div>
            <p className="my-2 text-xs text-muted-foreground">{category === "STANDARD_JDBC"
              ? t("selection.modulePurpose", { module: label }) : module.description}</p>
            <details>
              <summary className="cursor-pointer text-sm text-primary">{t("selection.expand")}</summary>
              <div className="mt-3 max-h-96 space-y-2 overflow-auto">
                {visible.map(item => <label key={item.id} className="flex cursor-pointer items-start gap-3 rounded-lg p-2 hover:bg-accent">
                  <input type="checkbox" aria-label={`${item.displayName} (${item.id})`} className="mt-1 size-4 shrink-0 accent-primary" checked={selected.has(item.id)}
                    onChange={event => toggle([item.id], event.target.checked)} />
                  <span className="min-w-0 text-sm"><span className="block">{item.displayName}</span>
                    <code className="break-all text-xs text-muted-foreground">{item.id}</code>
                    <span className="block text-xs text-muted-foreground">{t("selection.jdbcVersion", { version: item.jdbcVersion ?? "—" })}{item.executionPrerequisites?.length ? ` · ${item.executionPrerequisites.join(", ")}` : ""}</span>
                  </span>
                </label>)}
              </div>
            </details>
          </div>;
        })}
      </section>;
    })}
  </div>;
}
