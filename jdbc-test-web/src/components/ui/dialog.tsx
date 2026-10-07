import { Dialog as Primitive } from "radix-ui";
import { X } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
export function Dialog({
  open,
  onOpenChange,
  title,
  description,
  children,
  className,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <Primitive.Root open={open} onOpenChange={onOpenChange}>
      <Primitive.Portal>
        <Primitive.Overlay className="fixed inset-0 z-50 bg-slate-950/35 backdrop-blur-xs" />
        <Primitive.Content
          className={cn(
            "fixed left-1/2 top-1/2 z-50 max-h-[85vh] w-[calc(100%_-_2rem)] max-w-2xl -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-3xl border bg-white p-6 shadow-xl",
            className,
          )}
        >
          <Primitive.Title className="break-words pr-8 text-xl font-semibold">
            {title}
          </Primitive.Title>
          <Primitive.Description className="mt-2 break-words text-sm text-muted-foreground">
            {description}
          </Primitive.Description>
          <div className="mt-6">{children}</div>
          <Primitive.Close className="absolute right-5 top-5 rounded-lg p-1 text-muted-foreground hover:bg-muted">
            <X className="size-5" />
            <span className="sr-only">Close dialog</span>
          </Primitive.Close>
        </Primitive.Content>
      </Primitive.Portal>
    </Primitive.Root>
  );
}
