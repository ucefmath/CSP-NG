import { render, screen } from "@testing-library/react";
import { describe, expect, test, vi } from "vitest";
import { createMemoryRouter, RouterProvider } from "react-router";
import { ADMIN_ROUTES, createAdminMemoryRouter } from "../router";
import { ToastProvider } from "../components/ui";
import { setSessionUser } from "../api";

// Les pages fetchent au montage : mock global minimal.
function mockFetch() {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      const json = (body: unknown) => ({
        ok: true,
        status: 200,
        headers: new Headers({ "Content-Type": "application/json" }),
        json: async () => body,
        text: async () => JSON.stringify(body),
        clone: () => ({ json: async () => body }),
      });
      if (String(url).includes("/api/admin/config")) {
        return json({ root: { proxyName: "test", version: 1 }, profiles: [], connectors: [], proxies: [], profileListeners: [], cache: {}, clusterUdp: {}, loadedAtMs: 0 });
      }
      if (String(url).includes("/api/admin/users")) return json([]);
      if (String(url).includes("/cache/status")) {
        return json({ generatedAtMs: 0, ready: true, local: null, caches: [], nodes: [], udp: null });
      }
      if (String(url).includes("/cacheex/stats")) return json({ generatedAtMs: 0, pairs: [] });
      if (String(url).includes("/cw-cycle")) {
        return json({ enabled: false, caids: [], maxList: 500, keepTimeSec: 15, onBad: "drop", dropOld: true, usePeerCycleMetadata: false, version: 1 });
      }
      if (String(url).includes("/dcw/stats")) return json({});
      if (String(url).includes("/dcw/policy")) {
        return json({ enabled: true, adaptiveRoutingEnabled: false, autoObserveEnabled: false, zeroCountingEnabled: false, defaultChecksumMode: "inherit", repairMode: "off", allowZeroHalf: false, rulesCount: 0, mappingsCount: 0, version: 1, updatedAtMs: 0 });
      }
      if (String(url).includes("/dcw/rules")) return json([]);
      if (String(url).includes("/dcw/mappings")) return json([]);
      if (String(url).includes("/dcw/anomalies")) return json([]);
      if (String(url).includes("/dcw/candidates")) return json([]);
      if (String(url).includes("/config/relay/")) return json([]);
      if (String(url).includes("/relay/status")) return json({});
      if (String(url).includes("/catalog/capabilities/")) return json([]);
      if (String(url).includes("/config/proxies")) return json([]);
      return json({});
    }),
  );
}

describe("routes admin", () => {
  test("ADMIN_ROUTES couvre les 12 routes legacy + nouveaux écrans", () => {
    for (const r of ["overview", "general", "cache", "cluster", "profiles", "listeners", "catalog", "connectors", "proxys", "relais", "dcw", "users"]) {
      expect(ADMIN_ROUTES).toContain(r);
    }
    expect(ADMIN_ROUTES).toContain("cache-etat");
    expect(ADMIN_ROUTES).toContain("cacheex");
    expect(ADMIN_ROUTES).toContain("cw-cycle");
    expect(ADMIN_ROUTES).toContain("logs");
  });

  test("chaque route rend un titre h1 (parité complète)", async () => {
    mockFetch();
    setSessionUser("tester");
    // Réutilise le routeur réel via import dynamique du module main ? Non : reconstruit minimal.
    const { OverviewPage } = await import("../pages/Overview");
    const { GeneralPage } = await import("../pages/General");
    const { CachePage } = await import("../pages/Cache");
    const { CacheEtatPage } = await import("../pages/CacheEtat");
    const { CacheExPage } = await import("../pages/CacheEx");
    const { ClusterPage } = await import("../pages/Cluster");
    const { ProfilesPage } = await import("../pages/Profiles");
    const { ListenersPage } = await import("../pages/Listeners");
    const { CatalogPage } = await import("../pages/Catalog");
    const { ConnectorsPage } = await import("../pages/Connectors");
    const { ProxysPage } = await import("../pages/Proxys");
    const { RelaisPage } = await import("../pages/Relais");
    const { DcwPage } = await import("../pages/Dcw");
    const { CwCyclePage } = await import("../pages/CwCycle");
    const { UsersPage } = await import("../pages/Users");
    const { LogsPage } = await import("../pages/Logs");
    const pages: Array<[string, React.ComponentType]> = [
      ["overview", OverviewPage],
      ["logs", LogsPage],
      ["general", GeneralPage],
      ["cache", CachePage],
      ["cache-etat", CacheEtatPage],
      ["cacheex", CacheExPage],
      ["cluster", ClusterPage],
      ["profiles", ProfilesPage],
      ["listeners", ListenersPage],
      ["catalog", CatalogPage],
      ["connectors", ConnectorsPage],
      ["proxys", ProxysPage],
      ["relais", RelaisPage],
      ["dcw", DcwPage],
      ["cw-cycle", CwCyclePage],
      ["users", UsersPage],
    ];
    for (const [name, C] of pages) {
      const router = createMemoryRouter([{ path: "/", Component: C }]);
      const { unmount } = render(
        <ToastProvider>
          <RouterProvider router={router} />
        </ToastProvider>,
      );
      const h1 = await screen.findByRole("heading", { level: 1 }, { timeout: 3000 });
      expect(h1.textContent, `route ${name}`).toBeTruthy();
      unmount();
      document.body.innerHTML = "";
    }
    vi.unstubAllGlobals();
  });

  test("le routeur réel résout chaque route /admin/* vers une page", async () => {
    mockFetch();
    setSessionUser("tester");
    for (const name of ADMIN_ROUTES) {
      const router = createAdminMemoryRouter(`/${name}`);
      const { unmount } = render(
        <ToastProvider>
          <RouterProvider router={router} />
        </ToastProvider>,
      );
      const h1 = await screen.findByRole("heading", { level: 1 }, { timeout: 3000 });
      expect(h1.textContent, `route ${name}`).toBeTruthy();
      unmount();
    }
    vi.unstubAllGlobals();
  });

  test("navigation regroupée : liens présents dans le shell", async () => {
    mockFetch();
    setSessionUser("tester");
    const { Shell } = await import("../components/ui");
    const { OverviewPage } = await import("../pages/Overview");
    const router = createMemoryRouter(
      [
        {
          path: "/",
          Component: Shell,
          children: [{ index: true, Component: OverviewPage }],
        },
      ],
      { initialEntries: ["/"] },
    );
    render(
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>,
    );
    expect(await screen.findByRole("navigation", { name: "Navigation principale" })).toBeTruthy();
    for (const label of ["Vue d'ensemble", "État des caches", "Stats CacheEx", "Cycle CW", "Utilisateurs"]) {
      expect(screen.getAllByRole("link", { name: label }).length).toBeGreaterThanOrEqual(1);
    }
    expect(screen.getByRole("link", { name: "Aller au contenu" })).toBeTruthy();
    vi.unstubAllGlobals();
  });

  test("DcwPage rend un mapping global CAID:Provider (sourceSid=0, targetSid=0)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        const json = (body: unknown) => ({
          ok: true,
          status: 200,
          headers: new Headers({ "Content-Type": "application/json" }),
          json: async () => body,
          text: async () => JSON.stringify(body),
          clone: () => ({ json: async () => body }),
        });
        if (String(url).includes("/dcw/policy")) {
          return json({ enabled: true, version: 1 });
        }
        if (String(url).includes("/dcw/rules")) return json([]);
        if (String(url).includes("/dcw/mappings")) {
          return json([
            {
              id: 42,
              profile: "telesat",
              caid: 0x1884,
              provider: 0,
              targetCaid: 0x1813,
              targetProvider: 0x68,
              sourceSid: 0,
              targetSid: 0,
              approved: true,
              enabled: true,
              expiresAtMs: 0,
              version: 1,
            },
          ]);
        }
        return json({});
      }),
    );
    setSessionUser("tester");
    const { userEvent } = await import("@testing-library/user-event");
    const user = userEvent.setup();
    const { DcwPage } = await import("../pages/Dcw");
    const router = createMemoryRouter([{ path: "/", Component: DcwPage }]);
    render(
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>,
    );

    const mapTab = await screen.findByRole("tab", { name: "Mappings" });
    await user.click(mapTab);

    expect(await screen.findByRole("heading", { level: 2, name: /Mappings de service \(CAID:Provider & SIDs\)/ })).toBeTruthy();
    expect(await screen.findByText("0x1884:0x000000 → 0x1813:0x000068")).toBeTruthy();
    expect(await screen.findByText("* (tous les SIDs)")).toBeTruthy();

    vi.unstubAllGlobals();
  });
});
