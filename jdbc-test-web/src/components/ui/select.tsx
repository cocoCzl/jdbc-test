// CPA Keeper UI adaptation (MIT, upstream 2c09d65). See public/third-party-notices.txt.
import { Children, isValidElement, type ReactNode, type ComponentProps } from "react";
import { Select as Primitive } from "radix-ui";
import { Check, ChevronDown, ChevronUp } from "lucide-react";
import { cn } from "@/lib/utils";

// Keep option values in the business layer unchanged, including its empty filter value.
const EMPTY = "__jdbc_empty_selection__";
type Option = { value: string; label: ReactNode; disabled?: boolean };
function optionsFrom(children: ReactNode): Option[] {
  return Children.toArray(children).flatMap(child => {
    if (!isValidElement<{ value?: string; children?: ReactNode; disabled?: boolean }>(child)) return [];
    if (child.type === "option") return [{ value: child.props.value ?? String(child.props.children), label: child.props.children, disabled: child.props.disabled }];
    return optionsFrom(child.props.children);
  });
}
type Props = Omit<ComponentProps<"button">, "value" | "onChange" | "children"> & {
  value: string; onValueChange: (value: string) => void; children: ReactNode;
  required?: boolean; name?: string;
};
/** CPA menu surface/option styles with the project's existing Radix interaction engine. */
export function Select({ value, onValueChange, children, className, required, name, disabled, ...props }: Props) {
  const options = optionsFrom(children);
  return <Primitive.Root value={value || (required ? "" : EMPTY)} onValueChange={next => onValueChange(next === EMPTY ? "" : next)} disabled={disabled} required={required} name={name}>
    <Primitive.Trigger aria-required={required || undefined} {...props} data-value={value} className={cn("jdbc-select", className)}>
      <Primitive.Value placeholder={options.find(option => option.value === "")?.label} /><Primitive.Icon><ChevronDown className="size-4" /></Primitive.Icon>
    </Primitive.Trigger>
    <Primitive.Portal>
      <Primitive.Content position="popper" sideOffset={8} collisionPadding={8} className="jdbc-menu jdbc-select-menu">
        <Primitive.ScrollUpButton className="jdbc-menu-scroll"><ChevronUp className="size-4" /></Primitive.ScrollUpButton>
        <Primitive.Viewport>
          {options.map(option => <Primitive.Item key={option.value} data-value={option.value} value={option.value || EMPTY} disabled={option.disabled} className="jdbc-menu-item">
            <Primitive.ItemText>{option.label}</Primitive.ItemText>
            <Primitive.ItemIndicator><Check className="size-4" /></Primitive.ItemIndicator>
          </Primitive.Item>)}
        </Primitive.Viewport>
        <Primitive.ScrollDownButton className="jdbc-menu-scroll"><ChevronDown className="size-4" /></Primitive.ScrollDownButton>
      </Primitive.Content>
    </Primitive.Portal>
  </Primitive.Root>;
}
