import "./resource-action.scss";
import { LoaderCircle, type LucideIcon } from "lucide-react";
import { Link } from "react-router-dom";
import { Button } from "./ui/button";
import { Tooltip } from "./ui/tooltip";

/** Shared row controls: disabled triggers remain focusable so their reason is available. */
export function ResourceAction({ label, icon: Icon, href, onClick, disabled, pending, reason, danger }: {
  label: string;
  icon: LucideIcon;
  href?: string;
  onClick?: () => void;
  disabled?: boolean;
  pending?: boolean;
  reason?: string;
  danger?: boolean;
}) {
  const unavailable = disabled || pending;
  const description = unavailable && reason ? `${label} · ${reason}` : label;
  const content = pending ? <LoaderCircle className="animate-spin" aria-hidden="true" /> : <Icon aria-hidden="true" />;
  return <Tooltip label={description}>
    <span className="resource-action-trigger" tabIndex={unavailable ? 0 : undefined}
      role={unavailable ? "group" : undefined} aria-label={unavailable ? description : undefined}
      onClick={event => event.stopPropagation()}>
      <Button type="button" size="icon" variant="ghost" className="resource-action"
        data-danger={danger || undefined} aria-label={label} aria-busy={pending || undefined}
        disabled={unavailable} asChild={!!href && !unavailable}
        onClick={event => { event.stopPropagation(); if (!unavailable) onClick?.(); }}>
        {href && !unavailable ? <Link to={href}>{content}</Link> : content}
      </Button>
    </span>
  </Tooltip>;
}
