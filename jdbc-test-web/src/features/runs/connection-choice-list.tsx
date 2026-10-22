import { t } from "i18next";
import type { Connection } from "@/types/api";
import { JdbcUrl } from "@/components/jdbc-url";
import { StatusBadge } from "@/components/common";
import { connectionPresentation } from "@/lib/status";
import { formatDateTime } from "@/lib/format";
import "./connection-choice-list.scss";

export function ConnectionChoiceList({ items, selectedId, onSelect }: {
  items: Connection[];
  selectedId?: string;
  onSelect: (connection: Connection) => void;
}) {
  return <div role="radiogroup" aria-label={t("ui.chooseAConnection")} className="run-connection-list">
    {items.map(item => <div key={item.id} data-selected={selectedId === item.id} className="run-connection-row"
      onClick={event => {
        if ((event.target as HTMLElement).closest("button, a, input, label")) return;
        onSelect(item);
      }}>
      <input id={`run-connection-${item.id}`} type="radio" name="run-connection" aria-label={item.name}
        className="run-connection-radio accent-primary" checked={selectedId === item.id} onChange={() => onSelect(item)} />
      <label htmlFor={`run-connection-${item.id}`} className="run-connection-name" title={item.name}>
        <h3>{item.name}</h3>
      </label>
      <span className="run-connection-type">{item.databaseType}</span>
      <div className="run-connection-url"><JdbcUrl value={item.jdbcUrl} compact /></div>
      <div className="run-connection-status">
        {item.lastConnectionTestStatus
          ? <StatusBadge {...connectionPresentation[item.lastConnectionTestStatus]} />
          : <span className="text-xs text-muted-foreground">{t("ui.notTested")}</span>}
        {item.lastConnectionTestStatus && item.lastConnectionTestAt ? <time dateTime={item.lastConnectionTestAt} className="text-xs text-muted-foreground">{formatDateTime(item.lastConnectionTestAt)}</time> : null}
      </div>
    </div>)}
  </div>;
}
