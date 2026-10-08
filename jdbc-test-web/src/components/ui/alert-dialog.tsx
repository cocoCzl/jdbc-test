import { t } from "i18next";
import { useTranslation } from "react-i18next";
import { AlertDialog } from "radix-ui";
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
  return (
    <AlertDialog.Root open={open} onOpenChange={onOpenChange}>
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="fixed inset-0 z-50 bg-slate-950/35 backdrop-blur-xs" />
        <AlertDialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100%_-_2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-3xl border bg-white p-6 shadow-xl">
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
