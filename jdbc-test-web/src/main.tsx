import "./i18n";
import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router-dom";
import { Toaster } from "sonner";
import { createQueryClient } from "./app/query-client";
import { router } from "./app/router";
import "@fontsource/ibm-plex-mono/latin-400.css";
import "./index.css";
import "./styles/foundation.scss";
document.documentElement.dataset.theme = "white";
const queryClient = createQueryClient();
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <Toaster richColors position="bottom-right" />
    </QueryClientProvider>
  </React.StrictMode>,
);
