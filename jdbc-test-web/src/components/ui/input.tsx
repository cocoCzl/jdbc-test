// CPA Keeper UI adaptation (MIT, upstream 2c09d65). See public/third-party-notices.txt.
import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";
export function Input({className, ...props}: ComponentProps<"input">) {
  return <input data-slot="input" className={cn("jdbc-input", className)} {...props} />;
}
export function Textarea({className, ...props}: ComponentProps<"textarea">) {
  return <textarea className={cn("jdbc-input jdbc-textarea", className)} {...props} />;
}
