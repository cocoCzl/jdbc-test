// CPA Keeper UI adaptation (MIT, upstream 2c09d65). See public/third-party-notices.txt.
// Adapted from CPA Keeper web/src/components/ui/Card.tsx (MIT, 2c09d65).
import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/utils";
export function Card({as: Comp = "div", title, subtitle, extra, className, children, ...props}: Omit<HTMLAttributes<HTMLElement>, "title"> & {
  as?: "div" | "section" | "aside" | "form"; title?: ReactNode; subtitle?: ReactNode; extra?: ReactNode;
}) {
  return <Comp className={cn("panel", className)} {...props}>
    {(title || subtitle || extra) && <div className="mb-5 flex flex-wrap items-start justify-between gap-3"><div className="min-w-0">{title && <h2 className="text-lg font-semibold">{title}</h2>}{subtitle && <p className="mt-1 text-xs text-muted-foreground">{subtitle}</p>}</div>{extra}</div>}
    {children}
  </Comp>;
}
