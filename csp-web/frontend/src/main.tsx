import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router";
import { ToastProvider } from "./components/ui";
import { I18nProvider } from "./i18n";
import { createAdminRouter } from "./router";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <I18nProvider>
      <ToastProvider>
        <RouterProvider router={createAdminRouter()} />
      </ToastProvider>
    </I18nProvider>
  </StrictMode>,
);
