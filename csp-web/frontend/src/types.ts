// Types miroirs des DTO Java (lecture seule cote UI, champs backend optionnels
// toleres quand l'agent backend n'a pas encore livre une extension).

export interface ErrorResponse {
  code?: string;
  message?: string;
  details?: string;
  correlationId?: string;
}

export interface SnapshotRoot {
  proxyName?: string;
  version?: number;
}

export interface Profile {
  name: string;
  enabled?: boolean;
  debug?: boolean;
  cacheOnly?: boolean;
  caId?: number;
  networkId?: number;
  requireProviderMatch?: boolean;
  checkEcmLength?: boolean;
  maxCwWaitMs?: number;
  congestionLimitMs?: number | null;
  mismatchedCards?: boolean;
  serviceConflicts?: number;
  blockCaidMismatch?: boolean;
  providerFilter?: string | null;
  servicesFile?: string | null;
  servicesFileFormat?: string | null;
  version: number;
}

export interface Connector {
  name: string;
  profileName?: string | null;
  protocol?: string;
  enabled?: boolean;
  debug?: boolean;
  host?: string;
  port?: number;
  username?: string | null;
  passwordRef?: string | null;
  desKeyRef?: string | null;
  connectTimeoutMs?: number;
  reconnectDelayMs?: number;
  keepAliveSec?: number | null;
  minDelayMs?: number | null;
  maxQueue?: number | null;
  newcamdMaxMsgSize?: number | null;
  newcamdClientIdRef?: string | null;
  newcamdDialect?: string;
  newcamdPasswordEncoding?: string;
  radegastResponseTimeoutMs?: number | null;
  cccamEnabled?: boolean;
  cccamNodeId?: string | null;
  cccamVersion?: string | null;
  cacheexMode?: number | null;
  cacheexFilter?: string | null;
  cacheexMaxSend?: number | null;
  cacheexMaxHop?: number | null;
  chameleonProfiles?: string | null;
  conaxReaderIndex?: number | null;
  cardDiscovery?: boolean;
  reshareGrant?: number | null;
  reshareCap?: number | null;
  priority?: number;
  maxCapacity?: number;
  proxyName?: string | null;
  version: number;
}

export interface ProxyEntry {
  name: string;
  enabled?: boolean;
  host?: string;
  port?: number;
  username?: string | null;
  passwordRef?: string | null;
  description?: string | null;
  connectorCount?: number;
  version: number;
}

export interface ListenerEntry {
  id: number;
  profileName?: string;
  protocol?: string;
  port?: number;
  bindAddress?: string;
  enabled?: boolean;
  desKeyRef?: string | null;
  allowList?: string | null;
  denyList?: string | null;
  nodeId?: string | null;
  cardMode?: string;
  strictPolicy?: boolean;
  reshareCap?: number;
  maxCards?: number;
  maxInflight?: number;
  maxPerIp?: number;
  idleTimeoutSec?: number;
  version: number;
}

export interface UserEntry {
  name: string;
  displayName?: string | null;
  email?: string | null;
  enabled?: boolean;
  admin?: boolean;
  debug?: boolean;
  mapExcluded?: boolean;
  maxConnections?: number;
  ipMask?: string | null;
  allowedEcmRate?: number;
  profiles?: string[];
  allowedConnectors?: string[];
  allowedServices?: Record<string, number[]>;
  blockedServices?: Record<string, number[]>;
  version: number;
}

export interface ProtocolSecret {
  protocol?: string;
  secretRef?: string | null;
  secretVersion?: number;
  validUntil?: string | number | null;
  enabled?: boolean;
  cacheex?: { mode?: number | null; maxHop?: number | null; filter?: string | null } | null;
  version?: number;
  updatedBy?: string;
}

export interface ConfigSnapshot {
  root?: SnapshotRoot;
  profiles?: Profile[];
  connectors?: Connector[];
  proxies?: ProxyEntry[];
  profileListeners?: ListenerEntry[];
  cache?: Record<string, unknown>;
  clusterUdp?: Record<string, unknown>;
  extendedNewcamd?: Record<string, unknown> | null;
  loadedAtMs?: number;
}

// --- Nouveaux contrats (agent backend, doc api-contracts-ui-refonte.md v2) ---

export type SecretPurpose =
  | "connector-password"
  | "connector-des-key"
  | "listener-des-key"
  | "proxy-password"
  | "user-protocol-secret"
  | "extended-newcamd-des-key";

export interface MaterializeResponse {
  secretRef: string;
  purpose: SecretPurpose;
  owner: string;
}

export interface BulkItem {
  id: string;
  version: number;
}

export interface BulkResult {
  updated: BulkItem[];
}

export interface CacheStatus {
  generatedAtMs?: number;
  ready?: boolean;
  local?: Record<string, unknown> | null;
  caches?: Array<{
    name: string;
    kind?: string;
    state?: string;
    nodeName?: string | null;
    maxAgeMs?: number;
    maxEntries?: number;
    maxInflight?: number;
    version?: number;
    peers?: Array<{ host?: string; port?: number; enabled?: boolean }>;
    stats?: { consultHits?: number; feedPuts?: number } | null;
    statsAgeMs?: number | null;
    statsFresh?: boolean;
  }>;
  nodes?: Array<{
    nodeName: string;
    usable?: boolean | null;
    consultOk?: number | null;
    avgRttMs?: number | null;
    statsAgeMs?: number | null;
    statsFresh?: boolean;
  }>;
  udp?: {
    enabled?: boolean;
    bindPort?: number;
    stats?: Record<string, unknown> | null;
    statsAgeMs?: number | null;
    statsFresh?: boolean;
  } | null;
}

export interface CacheExStats {
  generatedAtMs?: number;
  pairs?: Array<{
    peerId: string;
    direction?: string;
    protocol?: string;
    mode?: number;
    profile?: string;
    online?: boolean;
    state?: string;
    maxHop?: number;
    maxSendQueue?: number;
    filterActive?: boolean;
    pushedCw?: number;
    receivedCw?: number;
    droppedCw?: number;
    /** Compteurs par cause backend (loop/hop/checksum/filtered/invalid/queue/flood), toujours pré-renseignés. */
    dropReasons?: Record<string, number>;
    lastPushAtMs?: number | null;
    lastReceiveAtMs?: number | null;
  }>;
}

export interface CwCycleConfig {
  enabled?: boolean;
  caids?: number[];
  maxList?: number;
  keepTimeSec?: number;
  onBad?: "log" | "drop";
  dropOld?: boolean;
  usePeerCycleMetadata?: boolean;
  version: number;
  updatedAtMs?: number;
  updatedBy?: string;
}
export interface DcwStats {
  cwCycle?: {
    enabled?: boolean;
    trackedEntries?: number;
    checks?: number;
    badCycles?: number;
    dropped?: number;
    logged?: number;
  } | null;
  [k: string]: unknown;
}

// --- Qualité pairs (lots 2-5, /api/admin/peer-quality) ---

export interface PeerQualityRow {
  nodeId?: string;
  peerId: string;
  accepted?: number;
  invalid?: number;
  total?: number;
  invalidRatioBps?: number;
  windowMs?: number;
  quarantined?: boolean;
}

export interface PeerQualityResponse {
  generatedAtMs?: number;
  windowMs?: number;
  peers?: PeerQualityRow[];
}

export interface CacheHitsResponse {
  generatedAtMs?: number;
  origins?: Array<{ nodeId?: string; origin: string; hits?: number; lastHitAtMs?: number | null }>;
}

export interface QuarantineConfig {
  enabled?: boolean;
  mode?: string;
  minSamples?: number;
  invalidRatioBps?: number;
  windowMs?: number;
  durationMs?: number;
  maxPeers?: number;
  version: number;
}

export interface QuarantineResponse {
  config?: QuarantineConfig | null;
  entries?: Array<{
    nodeId?: string;
    peerId: string;
    reason?: string;
    quarantinedAtMs?: number;
    expiresAtMs?: number;
    observedOnly?: boolean;
  }>;
  observed?: number;
  enforced?: number;
}

export interface QuorumConfig {
  mode?: string;
  minOrigins?: number;
  windowMs?: number;
  maxKeys?: number;
  profiles?: string[];
  caids?: number[];
  version: number;
}

export interface QuorumResponse {
  config?: QuorumConfig | null;
  stats?: {
    keys?: number;
    observed?: number;
    quorum?: number;
    pending?: number;
    mismatch?: number;
    expired?: number;
  } | null;
  keys?: Array<{
    profile?: string | null;
    caid?: number;
    provider?: number;
    sid?: number;
    parity?: number;
    dataHash?: number;
    origins?: string[];
    firstSeenMs?: number;
    quorumReached?: boolean;
  }>;
}

export interface ConnectorRuntimeSnapshot {
  name: string;
  protocol: string;
  profile?: string | null;
  connected: boolean;
  loginState: string;
  state?: "CONNECTED" | "UNSTABLE" | "DEAD" | string;
  hits?: number;
  ecmSent?: number;
  ecmFailed?: number;
  lastSeenMs?: number;
  caids?: string[];
  providers?: string[];
}

export interface LogEntry {
  seq: number;
  timestamp: number;
  level: string;
  logger: string;
  message: string;
}

export interface LogsResponse {
  source: string;
  items: LogEntry[];
  nextAfter: number;
}

