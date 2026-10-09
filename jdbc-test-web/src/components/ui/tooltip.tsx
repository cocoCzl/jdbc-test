import { Tooltip as Primitive } from "radix-ui";
import type { ReactNode } from "react";
export function Tooltip({label, children}: {label: string; children: ReactNode}) {
  return <Primitive.Provider delayDuration={250}><Primitive.Root><Primitive.Trigger asChild>{children}</Primitive.Trigger><Primitive.Portal><Primitive.Content sideOffset={8} collisionPadding={8} className="jdbc-tooltip">{label}</Primitive.Content></Primitive.Portal></Primitive.Root></Primitive.Provider>;
}
