// Fixtures E2E : mocks API alignés sur les vrais DTO backend
// (AdminConfigResource, AdminUserResource, AdminRelayResource, AdminDcwResource,
// AdminCatalogResource, AdminProxyResource, AdminUserSecretResource,
// contrats api-contracts-ui-refonte.md v2 pour bulk/materialize/cache-status/cacheex/cw-cycle).
import type { Page, Route } from "@playwright/test";

export interface Captured {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: unknown;
}

export function snapshotFixture() {
  return {
    root: { proxyName: "e2e-proxy", version: 3 },
    profiles: [
      {
        name: "hdplus", enabled: true, debug: false, cacheOnly: false,
        caId: 1830, networkId: 0, requireProviderMatch: false, checkEcmLength: false,
        maxCwWaitMs: 5000, congestionLimitMs: null, mismatchedCards: false,
        serviceConflicts: 0, blockCaidMismatch: false, providerFilter: null,
        servicesFile: null, servicesFileFormat: null, version: 2,
      },
      {
        name: "sky", enabled: false, debug: false, cacheOnly: true,
        caId: 1702, networkId: 0, requireProviderMatch: true, checkEcmLength: true,
        maxCwWaitMs: 4000, congestionLimitMs: 1500, mismatchedCards: true,
        serviceConflicts: 1, blockCaidMismatch: true, providerFilter: "000001",
        servicesFile: "services.xml", servicesFileFormat: "oscam", version: 5,
      },
    ],
    connectors: [
      {
        name: "conn-a", profileName: "hdplus", protocol: "cccam", enabled: true, debug: false,
        host: "serveur-a.net", port: 12000, username: "usera", passwordRef: "env:CONN_A_PW",
        desKeyRef: null, connectTimeoutMs: 5000, reconnectDelayMs: 5000, keepAliveSec: null,
        minDelayMs: null, maxQueue: null, newcamdMaxMsgSize: null, newcamdClientIdRef: null,
        radegastResponseTimeoutMs: null, cccamEnabled: true, cccamNodeId: null,
        cccamVersion: "2.3.0", cacheexMode: 2, cacheexFilter: "1830:000000",
        cacheexMaxSend: null, chameleonProfiles: null, conaxReaderIndex: null,
        priority: 50, maxCapacity: 4, version: 3, proxyName: null,
        cardDiscovery: false, reshareGrant: null, newcamdDialect: "legacy",
        reshareCap: null, newcamdPasswordEncoding: "plaintext",
      },
      {
        name: "conn-b", profileName: "sky", protocol: "newcamd", enabled: true, debug: true,
        host: "10.0.0.9", port: 34000, username: "u2", passwordRef: "file:/s/conn-b.pwd",
        desKeyRef: "file:/s/conn-b.des", connectTimeoutMs: 3000, reconnectDelayMs: 2000,
        keepAliveSec: 60, minDelayMs: 100, maxQueue: 32, newcamdMaxMsgSize: 1024,
        newcamdClientIdRef: null, radegastResponseTimeoutMs: null, cccamEnabled: false,
        cccamNodeId: null, cccamVersion: null, cacheexMode: null, cacheexFilter: null,
        cacheexMaxSend: null, chameleonProfiles: null, conaxReaderIndex: null,
        priority: 70, maxCapacity: 0, version: 7, proxyName: "socks1",
        cardDiscovery: true, reshareGrant: 3, newcamdDialect: "oscam_ncd525",
        reshareCap: 2, newcamdPasswordEncoding: "md5crypt",
      },
    ],
    proxies: [
      {
        name: "socks1", enabled: true, host: "127.0.0.1", port: 1080,
        username: null, passwordRef: null, description: "local", connectorCount: 1, version: 1,
      },
    ],
    profileListeners: [
      {
        id: 11, profileName: "hdplus", protocol: "cccam", port: 12001, bindAddress: "0.0.0.0",
        enabled: true, desKeyRef: null, allowList: null, denyList: null, version: 4,
        cardMode: "manual", strictPolicy: true, reshareCap: 2, maxCards: 512,
        maxInflight: 32, maxPerIp: 5, nodeId: null, idleTimeoutSec: 60,
      },
    ],
    cache: { cwMaxAgeMs: 30000, maximumSize: 100000, maxCacheWaitMs: 500, maxCacheWaitPercent: -1, version: 2 },
    clusterUdp: {
      enabled: true, bindPort: 8099, bindAddress: "0.0.0.0", remoteHosts: null,
      multicastGroup: null, multicastPort: null, multicastTtl: null, autoAddPeers: true,
      trackerUrlRef: null, pingIntervalMs: 4000, resendThrottleMs: 50, maxPacketsPerSec: 50,
      blacklist: null, syncPeriodMs: 100, version: 1,
    },
    extendedNewcamd: {
      enabled: false, listenPort: 34001, bindAddress: "0.0.0.0", catchAll: false,
      debug: false, desKeyRef: null, allowList: null, denyList: null, maxMsgSize: 1024, version: 1,
    },
    loadedAtMs: 1695816000000,
  };
}

export function usersFixture() {
  return [
    {
      name: "alice", displayName: "Alice", email: "alice@example.net", enabled: true,
      admin: false, debug: false, mapExcluded: false, maxConnections: 2, ipMask: null,
      allowedEcmRate: -1, version: 2, profiles: ["hdplus"], allowedServices: {},
      blockedServices: {}, allowedConnectors: ["conn-a"],
    },
    {
      name: "root", displayName: "Root", email: null, enabled: true, admin: true,
      debug: false, mapExcluded: false, maxConnections: 5, ipMask: null, allowedEcmRate: -1,
      version: 1, profiles: [], allowedServices: {}, blockedServices: {}, allowedConnectors: [],
    },
  ];
}

export function json(status: number, body: unknown) {
  return {
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  };
}

export interface MockApi {
  captured: Captured[];
  last(urlPart: string, method?: string): Captured | undefined;
}

/** Installe les mocks API par défaut + capture toutes les requêtes API. */
export async function mockApi(page: Page, opts: {
  snapshot?: unknown;
  users?: unknown;
  overrides?: Array<{ match: (url: string, method: string) => boolean; handle: (route: Route) => Promise<void> }>;
} = {}): Promise<MockApi> {
  const captured: Captured[] = [];
  const snap = opts.snapshot ?? snapshotFixture();
  const users = opts.users ?? usersFixture();
  // Session mockée : fermée au départ, POST /session l'ouvre, DELETE la ferme.
  let sessionAlive = false;
  await page.route("**/api/admin/**", async (route) => {
    const req = route.request();
    const url = req.url();
    const method = req.method();
    let body: unknown = undefined;
    try {
      const raw = req.postData();
      body = raw ? JSON.parse(raw) : undefined;
    } catch {
      body = req.postData();
    }
    captured.push({ method, url, headers: req.headers(), body });
    const path = new URL(url).pathname;

    for (const o of opts.overrides ?? []) {
      if (o.match(url, method)) {
        await o.handle(route);
        return;
      }
    }

    if (path === "/api/admin/session" && method === "POST") {
      const b = (body ?? {}) as { username?: string; password?: string };
      if (b.username === "admin" && b.password === "correct-12-chars") {
        sessionAlive = true;
        await route.fulfill(json(200, { name: "admin" }));
      } else {
        await route.fulfill(json(401, { code: "UNAUTHORIZED", message: "Identifiants invalides" }));
      }
      return;
    }
    if (path === "/api/admin/session" && method === "DELETE") {
      sessionAlive = false;
      await route.fulfill({ status: 204, body: "" });
      return;
    }
    if (path === "/api/admin/me") {
      if (!sessionAlive) {
        await route.fulfill(json(401, { code: "UNAUTHORIZED", message: "Non authentifié" }));
      } else {
        await route.fulfill(json(200, { name: "admin", roles: ["admin"] }));
      }
      return;
    }
    if (path === "/api/admin/config" && method === "GET") {
      await route.fulfill(json(200, snap));
      return;
    }
    if (path === "/api/admin/users" && method === "GET") {
      await route.fulfill(json(200, users));
      return;
    }
    if (path === "/api/admin/config/relay/nodes") {
      await route.fulfill(json(200, [
        { nodeName: "worker-1", displayName: "Worker 1", enabled: true, authorized: true, revoked: false, version: 2, desiredVersion: 9, cspHost: "10.0.0.5", cspPort: 8443 },
      ]));
      return;
    }
    if (path === "/api/admin/config/relay/caches") {
      await route.fulfill(json(200, [
        { name: "cache-a", kind: "local", nodeName: null, enabled: true, maxAgeMs: 30000, maxEntries: 5000, maxInflight: 100, version: 3 },
        { name: "cache-u", kind: "udp", nodeName: null, enabled: true, maxAgeMs: 30000, maxEntries: 5000, maxInflight: 100, version: 1 },
      ]));
      return;
    }
    if (path === "/api/admin/config/relay/links") {
      await route.fulfill(json(200, [
        { listenerId: 11, cacheName: "cache-a", canConsult: true, canFeed: true, canExport: false, enabled: true, version: 2 },
      ]));
      return;
    }
    if (path === "/api/admin/relay/status") {
      await route.fulfill(json(200, {
        workerConfigTtlMs: 60000,
        nodes: [{ nodeName: "worker-1", appliedVersion: 9, expired: false, stale: false }],
        workerStats: {},
      }));
      return;
    }
    if (path === "/api/admin/dcw/policy") {
      await route.fulfill(json(200, {
        enabled: true, adaptiveRoutingEnabled: false, autoObserveEnabled: true,
        zeroCountingEnabled: false, defaultChecksumMode: "inherit", repairMode: "observe",
        allowZeroHalf: false, rulesCount: 1, mappingsCount: 1, version: 4, updatedAtMs: 1695816000000,
      }));
      return;
    }
    if (path === "/api/admin/dcw/rules") {
      await route.fulfill(json(200, [
        { id: 1, profile: "hdplus", caid: 1830, provider: null, sid: 0, sourceId: null, digestSha256: null, action: "observe", checksumMode: "inherit", notes: "r1", enabled: true, version: 1, updatedAtMs: 1695816000000 },
      ]));
      return;
    }
    if (path === "/api/admin/dcw/mappings") {
      await route.fulfill(json(200, [
        { id: 2, profile: "hdplus", caid: 1830, provider: 0, sourceSid: 100, targetSid: 200, approved: true, enabled: true, expiresAtMs: 0, version: 1, updatedAtMs: 1695816000000 },
      ]));
      return;
    }
    if (path === "/api/admin/dcw/stats") {
      await route.fulfill(json(200, { anomalies: 0, candidateLinks: 1, maxRules: 1024, maxMappings: 1024, adaptiveScoresExposed: false }));
      return;
    }
    if (path === "/api/admin/dcw/anomalies") {
      await route.fulfill(json(200, []));
      return;
    }
    if (path === "/api/admin/dcw/candidates") {
      await route.fulfill(json(200, [{ id: 7, connector: "conn-a", reason: "burst" }]));
      return;
    }
    if (path === "/api/admin/config/proxies") {
      await route.fulfill(json(200, (snap as { proxies: unknown }).proxies));
      return;
    }
    if (path.includes("/api/admin/catalog/capabilities/") && path.endsWith("/sources")) {
      await route.fulfill(json(200, [
        { id: 5, capabilityId: 10, connectorName: "conn-a", originCardId: 424242, trust: "direct", hop: 1, reshare: 5, updatedAt: "2024-01-01T00:00:00Z" },
      ]));
      return;
    }
    if (path.includes("/api/admin/catalog/capabilities/")) {
      await route.fulfill(json(200, [
        { id: 10, profile: "hdplus", caid: 1830, provider: 0, state: "published", cardId: 424242, version: 1, updatedAt: "2024-01-01T00:00:00Z" },
      ]));
      return;
    }
    if (path === "/api/admin/cache/status") {
      await route.fulfill(json(200, {
        generatedAtMs: 1695816000000, ready: true,
        local: { cwMaxAgeMs: 30000, maximumSize: 50000, pendingEcms: 3 },
        caches: [{ name: "cache-a", kind: "local", state: "enabled", nodeName: null, version: 3, stats: null, statsAgeMs: null, statsFresh: false }],
        nodes: [],
        udp: { enabled: true, bindPort: 8099, stats: null, statsAgeMs: null, statsFresh: false },
      }));
      return;
    }
    if (path === "/api/admin/cacheex/stats") {
      await route.fulfill(json(200, {
        generatedAtMs: 1695816000000,
        pairs: [{ peerId: "conn-a", direction: "outbound", protocol: "cccam", mode: 2, online: true, state: "connected", pushedCw: 0, receivedCw: 200, droppedCw: 1, dropReasons: { hop: 1 }, lastPushAtMs: null, lastReceiveAtMs: 1695815900000 }],
      }));
      return;
    }
    if (path === "/api/admin/config/cw-cycle") {
      await route.fulfill(json(200, { enabled: false, caids: [], maxList: 500, keepTimeSec: 15, onBad: "drop", dropOld: true, usePeerCycleMetadata: false, version: 1 }));
      return;
    }
    if (path === "/api/admin/peer-quality/quality") {
      await route.fulfill(json(200, {
        generatedAtMs: 1695816000000,
        windowMs: 300000,
        peers: [
          { nodeId: "central", peerId: "conn-a", accepted: 200, invalid: 2, total: 202, invalidRatioBps: 99, windowMs: 300000, quarantined: false },
          { nodeId: "worker-1", peerId: "conn-w", accepted: 50, invalid: 0, total: 50, invalidRatioBps: 0, windowMs: 300000, quarantined: false },
        ],
      }));
      return;
    }
    if (path === "/api/admin/peer-quality/hits") {
      await route.fulfill(json(200, {
        generatedAtMs: 1695816000000,
        origins: [
          { nodeId: "central", origin: "10.0.0.1:12000", hits: 42, lastHitAtMs: 1695815950000 },
          { nodeId: "worker-1", origin: "10.0.0.2:12000", hits: 18, lastHitAtMs: 1695815900000 },
        ],
      }));
      return;
    }
    if (path === "/api/admin/peer-quality/quarantine" && method === "GET") {
      await route.fulfill(json(200, {
        config: { enabled: false, mode: "observe", minSamples: 50, invalidRatioBps: 5000, windowMs: 300000, durationMs: 600000, maxPeers: 256, version: 1 },
        entries: [
          { nodeId: "central", peerId: "conn-bad", reason: "invalid-ratio-bps=6000 eligible=50", quarantinedAtMs: 1695815800000, expiresAtMs: 1695816400000, observedOnly: true },
          { nodeId: "worker-1", peerId: "conn-w-bad", reason: "invalid-ratio-bps=8000 eligible=60", quarantinedAtMs: 1695815800000, expiresAtMs: 1695816400000, observedOnly: false },
        ],
        observed: 3,
        enforced: 0,
      }));
      return;
    }
    if (path === "/api/admin/peer-quality/quarantine/release" && method === "POST") {
      const b = (body ?? {}) as { peerId?: string; nodeId?: string };
      if ((b.nodeId && b.nodeId !== "central") || (b.peerId && b.peerId.includes("/"))) {
        await route.fulfill(json(422, { code: "WORKER_SCOPE_NOT_SUPPORTED", message: "La levée manuelle s'applique uniquement au nœud central." }));
        return;
      }
      if (b.peerId === "conn-absent") {
        await route.fulfill(json(200, { released: false, peerId: "conn-absent", note: "Pair non trouvé dans la quarantaine centrale active." }));
        return;
      }
      await route.fulfill(json(200, { released: true, peerId: b.peerId ?? "conn-bad", note: "Levée centrale effectuée ; audit enregistré." }));
      return;
    }
    if (path === "/api/admin/peer-quality/quorum" && method === "GET") {
      await route.fulfill(json(200, {
        config: { mode: "off", minOrigins: 2, windowMs: 30000, maxKeys: 1024, profiles: [], caids: [], version: 1 },
        stats: { keys: 1, observed: 10, quorum: 8, pending: 2, mismatch: 0, expired: 0 },
        keys: [
          { profile: "hdplus", caid: 1830, provider: 0, sid: 100, parity: 128, dataHash: 12345, origins: ["node:0102030405060708", "node:090a0b0c0d0e0f10"], firstSeenMs: 1695815980000, quorumReached: true },
        ],
      }));
      return;
    }
    // Mutations par défaut : écho version+1. Bulk : écho {updated:[{id,version+1}]}.
    if (method === "PUT" || method === "POST") {
      if (path.endsWith("/bulk")) {
        const b = (body ?? {}) as { items?: Array<{ id: string; version: number }> };
        await route.fulfill(json(200, {
          updated: (b.items ?? []).map((it) => ({ id: it.id, version: it.version + 1 })),
        }));
      } else {
        await route.fulfill(json(200, { version: 99 }));
      }
      return;
    }
    if (method === "DELETE") {
      await route.fulfill({ status: 204, body: "" });
      return;
    }
    await route.fulfill(json(404, { code: "NOT_FOUND", message: "mock: route non prévue " + path }));
  });
  return {
    captured,
    last(urlPart: string, method?: string) {
      const list = captured.filter((c) => c.url.includes(urlPart) && (!method || c.method === method));
      return list[list.length - 1];
    },
  };
}

/** Connexion standard du mock (admin / correct-12-chars). */
export async function login(page: Page) {
  await page.goto("/admin/login");
  await page.getByLabel("Identifiant").fill("admin");
  await page.getByLabel("Mot de passe").fill("correct-12-chars");
  await page.getByRole("button", { name: "Se connecter" }).click();
  await page.getByRole("heading", { level: 1, name: "Vue d'ensemble" }).waitFor();
}
