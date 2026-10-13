import { Toaster } from "sonner";
import { useTheme } from "@/hooks/use-theme";

export function ThemeToaster() {
  const { resolved } = useTheme();
  return <Toaster theme={resolved} richColors position="bottom-right" />;
}
