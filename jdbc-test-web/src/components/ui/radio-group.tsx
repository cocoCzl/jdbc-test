import type { ComponentProps } from "react";
import { RadioGroup as Primitive } from "radix-ui";
import { cn } from "@/lib/utils";
import "./radio-group.scss";

export const RadioGroup = Primitive.Root;

export function RadioGroupItem({ className, ...props }: ComponentProps<typeof Primitive.Item>) {
  return <Primitive.Item {...props} className={cn("jdbc-radio", className)}>
    <Primitive.Indicator className="jdbc-radio-indicator" />
  </Primitive.Item>;
}
