import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { AlertDialog } from "radix-ui";
import { useRef } from "react";
import { Button } from "./button";
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  label,
  pending,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  label: string;
  pending: boolean;
  onConfirm: () => void;
}) {
  useTranslation();
  const returnFocus = useRef<HTMLElement | null>(null);
  return (
    <AlertDialog.Root open={open} onOpenChange={onOpenChange}>
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="jdbc-overlay" />
        <AlertDialog.Content className="jdbc-dialog jdbc-confirm"
          onOpenAutoFocus={() => {
            returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
          }}
          onCloseAutoFocus={event => {
            if (returnFocus.current?.isConnected) {
              event.preventDefault();
              returnFocus.current.focus({preventScroll: true});
            }
          }}>
          <AlertDialog.Title className="text-xl font-semibold">
            {title}
          </AlertDialog.Title>
          <AlertDialog.Description className="mt-3 text-sm leading-relaxed text-muted-foreground">
            {description}
          </AlertDialog.Description>
          <div className="mt-6 flex justify-end gap-2">
            <AlertDialog.Cancel asChild>
              <Button variant="outline" disabled={pending}>
                {t("ui.keepIt")}</Button>
            </AlertDialog.Cancel>
            <Button
              variant="destructive"
              disabled={pending}
              onClick={onConfirm}
            >
              {pending ? t("ui.pleaseWait") : label}
            </Button>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  );
}
