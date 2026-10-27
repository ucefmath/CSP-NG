import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router";
import { ToastProvider } from "./components/ui";
import { createAdminRouter } from "./router";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ToastProvider>
      <RouterProvider router={createAdminRouter()} />
    </ToastProvider>
  </StrictMode>,
);
