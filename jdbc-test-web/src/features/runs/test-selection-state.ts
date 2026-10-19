import { useState } from "react";
import type { Catalog, CatalogScenario, DataSourceProviders } from "@/types/api";

export function reconcileSelection(previous: CatalogScenario[], selected: Set<string>, next: CatalogScenario[]) {
  const known = new Set(previous.map(item => item.id));
  return new Set(next.filter(item => !known.has(item.id) || selected.has(item.id)).map(item => item.id));
}

export function useTestSelection(catalog: Catalog | undefined) {
  const [state, setState] = useState<{ catalog?: Catalog; selected: Set<string>; removed: number }>({ selected: new Set(), removed: 0 });
  if (catalog && state.catalog !== catalog) {
    const available = new Set(catalog.scenarios.map(item => item.id));
    setState({ catalog, selected: reconcileSelection(state.catalog?.scenarios ?? [], state.selected, catalog.scenarios),
      removed: [...state.selected].filter(id => !available.has(id)).length });
  }
  return { selected: state.selected, removed: state.removed,
    setSelected: (selected: Set<string>) => setState(previous => ({ ...previous, selected })) };
}

export function missingPrerequisites(item: CatalogScenario, providers?: DataSourceProviders) {
  const configured: Record<string, boolean> = { data_source: !!providers?.dataSource,
    connection_pool: !!providers?.connectionPoolDataSource, xa_data_source: !!providers?.xaDataSource };
  return (item.executionPrerequisites ?? []).filter(value => value.startsWith("provider:") && !configured[value.slice(9)]);
}
