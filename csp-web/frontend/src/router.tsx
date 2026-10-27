import { Navigate, createBrowserRouter, createMemoryRouter } from "react-router";
import type { ReactNode } from "react";
import { RequireAuth, Shell } from "./components/ui";
import { LoginPage } from "./pages/Login";
import { OverviewPage } from "./pages/Overview";
import { GeneralPage } from "./pages/General";
import { CachePage } from "./pages/Cache";
import { CacheEtatPage } from "./pages/CacheEtat";
import { CacheExPage } from "./pages/CacheEx";
import { ClusterPage } from "./pages/Cluster";
import { ProfilesPage } from "./pages/Profiles";
import { ListenersPage } from "./pages/Listeners";
import { CatalogPage } from "./pages/Catalog";
import { ConnectorsPage } from "./pages/Connectors";
import { ProxysPage } from "./pages/Proxys";
import { RelaisPage } from "./pages/Relais";
import { DcwPage } from "./pages/Dcw";
import { CwCyclePage } from "./pages/CwCycle";
import { UsersPage } from "./pages/Users";
import { LogsPage } from "./pages/Logs";
import { NotFoundPage } from "./pages/NotFound";

export const ADMIN_ROUTES = [
  "overview",
  "logs",
  "general",
  "cache",
  "cache-etat",
  "cacheex",
  "cluster",
  "profiles",
  "listeners",
  "catalog",
  "connectors",
  "proxys",
  "relais",
  "dcw",
  "cw-cycle",
  "users",
] as const;

function guard(node: ReactNode) {
  return <RequireAuth>{node}</RequireAuth>;
}

export const ADMIN_CHILDREN = [
  { index: true, element: <Navigate to="overview" replace /> },
  { path: "overview", element: guard(<OverviewPage />) },
  { path: "logs", element: guard(<LogsPage />) },
  { path: "general", element: guard(<GeneralPage />) },
  { path: "cache", element: guard(<CachePage />) },
  { path: "cache-etat", element: guard(<CacheEtatPage />) },
  { path: "cacheex", element: guard(<CacheExPage />) },
  { path: "cluster", element: guard(<ClusterPage />) },
  { path: "profiles", element: guard(<ProfilesPage />) },
  { path: "listeners", element: guard(<ListenersPage />) },
  { path: "catalog", element: guard(<CatalogPage />) },
  { path: "connectors", element: guard(<ConnectorsPage />) },
  { path: "proxys", element: guard(<ProxysPage />) },
  { path: "relais", element: guard(<RelaisPage />) },
  { path: "dcw", element: guard(<DcwPage />) },
  { path: "cw-cycle", element: guard(<CwCyclePage />) },
  { path: "users", element: guard(<UsersPage />) },
  { path: "*", Component: NotFoundPage },
];

export function createAdminRouter() {
  return createBrowserRouter(
    [
      { path: "login", Component: LoginPage },
      { path: "/", Component: Shell, children: ADMIN_CHILDREN },
    ],
    { basename: "/admin" },
  );
}

// Variante mémoire pour tests (même basename, mêmes routes).
export function createAdminMemoryRouter(initialPath = "/overview") {
  return createMemoryRouter(
    [
      { path: "login", Component: LoginPage },
      { path: "/", Component: Shell, children: ADMIN_CHILDREN },
    ],
    { basename: "/admin", initialEntries: [`/admin${initialPath}`] },
  );
}
