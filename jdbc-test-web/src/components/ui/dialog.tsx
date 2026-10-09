// CPA Keeper UI adaptation (MIT, upstream 2c09d65). See public/third-party-notices.txt.
import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { Dialog as Primitive } from "radix-ui";
import { X } from "lucide-react";
import { useRef, type ReactNode } from "react";
import { cn } from "@/lib/utils";
import { LanguageSelector } from "@/components/layout/language-selector";
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
  useTranslation();
  const returnFocus = useRef<HTMLElement | null>(null);
  return (
    <Primitive.Root open={open} onOpenChange={onOpenChange}>
      <Primitive.Portal>
        <Primitive.Overlay className="jdbc-overlay" />
        <Primitive.Content
          onOpenAutoFocus={() => {
            returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
          }}
          onCloseAutoFocus={event => {
            if (returnFocus.current?.isConnected) {
              event.preventDefault();
              returnFocus.current.focus({preventScroll: true});
            }
          }}
          className={cn(
            "jdbc-dialog",
            className,
          )}
        >
          <div className="jdbc-dialog-header flex flex-wrap items-center justify-between gap-3 pr-9">
            <Primitive.Title className="min-w-0 flex-1 break-words text-lg font-bold">
              {title}
            </Primitive.Title>
            <LanguageSelector />
          </div>
          <Primitive.Description className="jdbc-dialog-description break-words text-xs text-muted-foreground">
            {description}
          </Primitive.Description>
          <div className="jdbc-dialog-body">{children}</div>
          <Primitive.Close data-size="icon-sm" data-variant="ghost" className="jdbc-button absolute right-4 top-4 text-muted-foreground">
            <X className="size-5" />
            <span className="sr-only">{t("ui.closeDialog")}</span>
          </Primitive.Close>
        </Primitive.Content>
      </Primitive.Portal>
    </Primitive.Root>
  );
}
