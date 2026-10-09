// CPA Keeper UI adaptation (MIT, upstream 2c09d65). See public/third-party-notices.txt.
import type { ComponentProps } from "react";
import { Slot } from "radix-ui";
import { cn } from "@/lib/utils";
// CPA Button variants adapted to existing jdbc-test consumers and Radix asChild links.
type Variant = "default" | "destructive" | "outline" | "secondary" | "ghost" | "link";
type Size = "default" | "xs" | "sm" | "lg" | "icon" | "icon-xs" | "icon-sm" | "icon-lg";
export function Button({ className, variant = "default", size = "default", asChild = false, ...props }: ComponentProps<"button"> & { variant?: Variant; size?: Size; asChild?: boolean }) {
  const Comp = asChild ? Slot.Root : "button";
  return <Comp data-slot="button" data-variant={variant} data-size={size} className={cn("jdbc-button", className)} {...props} />;
}
