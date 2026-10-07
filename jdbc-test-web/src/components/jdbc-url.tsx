import { useState } from "react";
import { CopyButton } from "./common";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";

// Display-only masking, following the server's credential-key convention.
// This does not parse a JDBC URL, validate it, or change the saved connection.
export function redactJdbcUrl(value: string) {
  return value
    .replace(/((?:password|passwd|pwd|secret|token|access_token|user(?:name)?)\s*[:=]\s*)(?:\{[^}]*\}|"[^"]*"|'[^']*'|[^&;,#\s)]*)/gi, "$1[REDACTED]")
    .replace(/(\/\/)[^/@\s]+@/g, "$1[REDACTED]@")
    .replace(/(jdbc:oracle:[^:]+:)[^@\s]+\/@/gi, "$1[REDACTED]@")
    .replace(/(jdbc:oracle:[^:]+:)[^/@\s]+\/[^@\s]+@/gi, "$1[REDACTED]@");
}

export function JdbcUrl({ value }: { value: string }) {
  const [open, setOpen] = useState(false);
  const safe = redactJdbcUrl(value);
  return <div className="min-w-0">
    <p className="break-all font-mono text-xs leading-relaxed">{safe}</p>
    <Button type="button" variant="ghost" size="sm" className="mt-1" onClick={() => setOpen(true)}>
      View / copy URL
    </Button>
    <Dialog open={open} onOpenChange={setOpen} title="JDBC URL"
      description="Full target address. Recognized credentials are masked in both the display and copied text.">
      <p className="break-all rounded-lg bg-muted p-3 font-mono text-sm leading-relaxed">{safe}</p>
      <div className="mt-3 flex items-center gap-2 text-sm"><CopyButton value={safe} label="sanitized JDBC URL" />Copy sanitized URL</div>
    </Dialog>
  </div>;
}
