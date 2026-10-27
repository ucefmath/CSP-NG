# Contrats API backend — Refonte UI (passe 1, v2 CORRIGÉE après arbitrages)

> Statut : **CONTRATS FINAUX PROPOSÉS** (passe 2, aucun code). Arbitrages orchestration intégrés
> (D1–D4, secrets, états cache, CW wire). Contrôle comportemental OSCam vérifié dans
> `module-cacheex.c` (l.584-604, 625-672, 722-748) : **mode 2 déclaré sur X = X reçoit les push ;
> mode 3 déclaré sur X = X émet les push**. Comportement csp-ng existant conservé.
> Référence OSCam lecture seule (GPL, NOTICE) ; CSPv2 wire v1 **gelé** (ADR 0001) — aucun
> transport cacheex via CSPv2 ; le support wire des métadonnées de cycle passe par CCcam/CS378x.

## 0. Conventions transverses (existantes, inchangées)

| Sujet | Convention |
|---|---|
| Auth | Session cookie admin (`@RolesAllowed("admin")`), HTTPS obligatoire sinon `426 HTTPS_REQUIRED` |
| CSRF | Non-GET/HEAD/OPTIONS exige `X-CSP-Requested-With: CSP-WebUI` sinon `403 CSRF_INVALID` |
| Content-Type | `application/json` ; body ≤ 64K (`quarkus.http.limits.max-body-size`) |
| Erreurs | `ErrorResponse` ; optimistic lock par ligne → `409 CONFLICT` (mapper existant) ; `503 NOT_READY` |
| Mutations | Audit transactionnel `csp_audit_log` + acteur ; reload NOTIFY → `ConfigWatcher` |
| Secrets | **Refs only** en DB/API (`env:`/`file:`) ; valeurs jamais lues/échoquées/loggées/auditées |
| Versioning | **Par ligne** (`version` de chaque entité) — pas de génération globale (arbitrage D1) |

```ts
type ErrorResponse = { code: string; message: string; details?: string; correlationId?: string };
type VersionResponse = { version: number };
```

---

## 1. Secrets inline → matérialisation serveur 0600

### `POST /api/admin/secrets/materialize` (seul endpoint — pas de DELETE, pas de listing)

Objectif : l'UI saisit une valeur inline ; le serveur crée un **nouveau fichier unique au nom
opaque imprévisible** (0600, parent 0700) sous la racine autorisée
(`csp.import.secret-root` ⊂ `csp.secrets.allowed-file-roots`) et retourne la référence `file:` à
sauver ensuite dans la config (`passwordRef`, `desKeyRef`, `secretRef`).

**Interdit (arbitrage) : tout overwrite idempotent prévisible** — un fichier référencé en DB ne
doit jamais être modifié par un appel ultérieur. Chaque appel crée donc un fichier **distinct**.

Requête :
```json
{
  "purpose": "connector-password" | "connector-des-key" | "listener-des-key"
           | "proxy-password" | "user-protocol-secret" | "extended-newcamd-des-key",
  "owner":  "my-connector",
  "value":  "plain-secret"
}
```
- `owner` : 1..64 chars, **casse préservée en API** (réponse et audit reprennent
  l'owner tel quel) ; seul le NOM DE FICHIER est slug-normalisé via
  `SecretMaterializer.normalizeSlug` (minuscules, invalides→`-`, refus 400 si vide
  après normalisation — ex. `"Alice-Conn"` accepté → slug `alice-conn`). Les
  majuscules ne sont PAS interdites ; deux owners ne différant que par la casse
  restent uniques (suffixe aléatoire).
- `value` : **write-only**, jamais échoquée/loggée/audité. Formats :
  - `*-password`, `user-protocol-secret` : UTF-8, 1..4096 octets ;
  - `*-des-key` : 28 hex (14 octets), espaces/`:` tolérés (parser `parseDesKey14` existant).
- Nom de fichier : `<prefixe-purpose>-<ownerSlug>-<16 hex SecureRandom>.<ext>` —
  préfixes `csp-connector` / `csp-listener` / `csp-proxy` / `csp-user` / `csp-extncd`,
  ext `.pwd` / `.des`. Unicité garantie par le suffixe aléatoire (aucune collision, aucun
  overwrite possible) ; création **atomique** `CREATE_NEW` + `NOFOLLOW_LINKS` (refus si symlink,
  refus si existe), chmod 0600, parent 0700, hors event-loop (`executeBlocking`).

Réponse `201` (`Cache-Control: no-store`) :
```json
{ "secretRef": "file:/etc/csp/secrets/imported/csp-connector-my-connector-a1b2c3d4e5f60718.pwd",
  "purpose": "connector-password", "owner": "my-connector" }
```
Sémantique d'usage : le client DOIT ensuite persister `secretRef` via le PUT de config concerné.
Un fichier non référencé (abandon client) devient orphelin — accepté et documenté (pas de DELETE
par sécurité ; nettoyage = opération d'administration système sur la racine dédiée).

Erreurs : `400 INVALID_INPUT` (purpose inconnu, owner invalide, valeur vide/oversize/hex
invalide) ; `500 SECRET_WRITE_FAILED` (IO racine, symlink, permission) ; `503 NOT_READY`.

---

## 2. Bulk edit — options communes de plusieurs éléments

### `PUT /api/admin/config/{resource}/bulk` — `resource ∈ {connectors, profiles, listeners, users}`

**Garde optimiste par ligne** (arbitrage D1 : pas de génération globale) :

```json
{
  "items": [ { "id": "conn-a", "version": 3 }, { "id": "conn-b", "version": 7 } ],
  "patch": { "enabled": true, "connectTimeoutMs": 5000 }
}
```
- `items` : `1..100`, **ids uniques** (doublon → `400 INVALID_INPUT`). `id` = nom
  (connectors/profiles/users) ou identifiant numérique en chaîne (listeners). `version` ≥ 1 =
  version courante de CETTE ligne (même sémantique que les PUT unitaires).
- `patch` : `1..24` clés, objet partiel — absent = inchangé ; `null` explicite = remise à
  défaut/NULL si la colonne est nullable, sinon `400`.
- **Ordre déterministe** : mise à jour appliquée dans l'ordre croissant des `id`
  (lexicographique pour les noms, numérique pour listeners), indépendamment de l'ordre de la
  requête — comportement, audit et conflits reproductibles.
- **Atomicité** : UNE transaction PG. Tout conflit de version, item inconnu, patch invalide ou
  échec de validation → **rollback total**, rien d'appliqué.
- **Validation croisée conservée** : chaque item est validé avec les règles des PUT unitaires
  (bornes, cohérence dynamique/fixe des connecteurs, existence des profils référencés par les
  listeners, ACL users…). Tout invalide → rollback.
- **Audit** : une ligne `CONFIG_UPDATED` par item + une ligne `BULK_UPDATE` récapitulative,
  même transaction.

Réponse `200` :
```json
{ "updated": [ { "id": "conn-a", "version": 4 }, { "id": "conn-b", "version": 8 } ] }
```

Erreurs :
- `400 INVALID_INPUT` — structure/bornes/doublons/patch vide ;
- `400 INVALID_PATCH_FIELD` — champ interdit (voir §2.1) ;
- `400 UNKNOWN_ITEMS` — items inexistants (`details` les liste), rien d'appliqué ;
- `409 CONFLICT` — version de ligne en désaccord (`details` : `"id 'conn-a': expected 3, current 4"`), rien d'appliqué ;
- `404 RESOURCE_NOT_FOUND` ; `503 NOT_READY`.

### 2.1 Champs patchables (exclusions arbitrees : TOUS secrets, nodeId, identité/routing, admin)

**connectors** — patchables :
`enabled, debug, connectTimeoutMs, reconnectDelayMs, keepAliveSec, minDelayMs, maxQueue,
newcamdMaxMsgSize, radegastResponseTimeoutMs, cccamEnabled, cccamVersion, priority, maxCapacity,
proxyName, cardDiscovery, reshareGrant, reshareCap, newcamdDialect, newcamdPasswordEncoding,
cacheexMode, cacheexFilter, cacheexMaxSend, cacheexMaxHop`.
Interdits : `name, profileName, protocol, host, port, username` (identité/routing),
`passwordRef, desKeyRef, newcamdClientIdRef` (secrets), `cccamNodeId` (nodeId),
`caidProfileMap` (routing), `version`.

**profiles** — patchables :
`enabled, debug, cacheOnly, requireProviderMatch, checkEcmLength, maxCwWaitMs,
congestionLimitMs, mismatchedCards, serviceConflicts, blockCaidMismatch, providerFilter,
servicesFile, servicesFileFormat`.
Interdits : `name, caId, networkId, version`.

**listeners** (items = id numérique) — patchables :
`enabled, cardMode, strictPolicy, reshareCap, maxCards, maxInflight, maxPerIp, idleTimeoutSec,
allowList, denyList`.
Interdits : `id, profileName, protocol, port, bindAddress` (identité), `desKeyRef` (secret),
`nodeId` (nodeId), `version`.

**users** — patchables :
`displayName, email, enabled, debug, mapExcluded, maxConnections, ipMask, allowedEcmRate,
profiles (remplacement du set), allowedConnectors (remplacement), allowedServices (remplacement),
blockedServices (remplacement)`.
Interdits : `name` (identité), `admin` (escalade de privilèges), `passwordHash, password`
(secrets, endpoint dédié), `version`. Secrets protocoles users : endpoint dédié existant, jamais en bulk.

---

## 3. État des caches — fraîcheur explicite, jamais de faux zéros

### `GET /api/admin/cache/status`

Règle (arbitrage) : stats **absentes ou périmées ⇒ `null`/`"unknown"`, jamais de zéros
fabriqués ni d'état `active` déduit de la seule présence de stats**. Fraîcheur explicite :
`statsAgeMs` + `statsFresh` (seuil `csp.admin.stats-fresh-ms`, défaut 90000 ; une stat worker
par ack, cadence ≈ TTL contrat worker).

```json
{
  "generatedAtMs": 1695816000000,
  "ready": true,
  "local": {
    "cwMaxAgeMs": 30000, "maximumSize": 50000, "maxCacheWaitMs": 1000, "maxCacheWaitPercent": 20,
    "pendingEcms": 3, "pendingPeak": 12, "cachedEcms": 1500, "timeouts": 10,
    "instantHits": 500, "waitHits": 200, "contested": 5, "remoteHits": 0
  },
  "caches": [
    {
      "name": "cache-a",
      "kind": "local" | "node" | "udp",
      "state": "enabled" | "disabled",
      "nodeName": "worker-1",
      "maxAgeMs": 30000, "maxEntries": 5000, "maxInflight": 100, "version": 3,
      "peers": [ { "host": "10.0.0.5", "port": 10000, "enabled": true } ],
      "stats": null,
      "statsAgeMs": null,
      "statsFresh": false
    }
  ],
  "nodes": [
    { "nodeName": "worker-1", "usable": true, "consultOk": 12, "avgRttMs": 4,
      "statsAgeMs": 42000, "statsFresh": true }
  ],
  "udp": {
    "enabled": true, "bindPort": 10000,
    "stats": null,
    "statsAgeMs": null,
    "statsFresh": false
  }
}
```
- `local` : compteurs directs `CacheVerticle.stats()` (central, toujours présents) + config
  cache. Si verticle indisponible → `local: null` (pas de zéros).
- `caches[]` = `ConfigSnapshot.relayCaches()` (≤64, pairs ≤16). `state` **reflète la config
  uniquement** (`enabled|disabled`). `stats` = `{ "consultHits": n, "feedPuts": n }` ou `null`
  si aucune stat worker fraîche ; `statsAgeMs` âge de la dernière stat (null si jamais reçue).
- `nodes[]` : une entrée par node **ayant au moins une stat déjà reçue** ; sans stat fraîche,
  `usable: null` (« inconnu ») et compteurs `null`. Pas d'entrée fabriquée pour un node muet.
- `udp.stats` = `UdpStatsView` (`sentReq, sentRepl, recvReq, recvRepl, rejected, rateLimited,
  avgRttMs`) ou `null` si absent/périmé.
- Erreur : `503 NOT_READY`. Read-only.

---

## 4. Stats CacheEx par pair

### `GET /api/admin/cacheex/stats`

Sémantique des modes (arbitrage D2, conforme OSCam **et** au code csp-ng existant, aucun
changement de comportement) — **le côté qui déclare mode 2 reçoit les push ; celui qui déclare
mode 3 les émet** :

| Pair | mode 2 | mode 3 |
|---|---|---|
| Connecteur sortant (nous déclarons) | nous **recevons** du serveur distant | nous **émettons** vers le serveur distant |
| Session entrante user (nous déclarons la policy) | nous **émettons** vers le client (il reçoit) | nous **recevons** du client (il émet) |

```json
{
  "generatedAtMs": 1695816000000,
  "pairs": [
    {
      "peerId": "conn-cacheex-a",
      "direction": "outbound" | "inbound",
      "protocol": "cccam" | "cs378x",
      "mode": 2,
      "profile": "profile-a",
      "online": true,
      "state": "connected" | "disconnected" | "disabled",
      "maxHop": 10,
      "maxSendQueue": 2000,
      "filterActive": true,
      "pushedCw": 100,
      "receivedCw": 200,
      "droppedCw": 3,
      "dropReasons": { "loop": 1, "hop": 1, "checksum": 0, "filtered": 1, "invalid": 0, "queue": 0 },
      "lastPushAtMs": 1695815900000,
      "lastReceiveAtMs": 1695815950000
    }
  ]
}
```
- `peerId` : sortant = nom du connecteur ; entrant = `"L<listenerId>:<userName>"` (agrégation
  par pair utilisateur, indépendante du cycle de vie des sessions).
- `pushedCw` : CW poussés **par nous** vers le pair (mode 3 sortant ; mode 2 entrant).
  `receivedCw` : CW reçus **du** pair (mode 2 sortant ; mode 3 entrant). Jamais de zéros
  fabriqués : une paire sans trafic n'apparaît pas.
- `dropReasons` (réceptions et émissions) : `loop` (notre nodeId dans le chemin), `hop`
  (> maxHop), `checksum`, `filtered` (gate filtre), `invalid` (trame/CW invalide), `queue`
  (file d'émission pleine, cf. `maxSendQueue`).
- `filterActive` : mode émission = filtre du pair reçu (gate effective) ; mode réception =
  notre filtre annoncé appliqué.

### `POST /api/admin/cacheex/stats/reset` → `204` (compteurs seulement, CSRF requis)

---

## 5. CW-Cycle (vrai contrôle de cycle CW)

Options **noyau approuvées** (miroir comportemental OSCam CW_CYCLE_CHECK, aucun code copié) :
`cwcycle_check_enable`, `cwcycle_check_caid`, `cwcycle_maxlist` (défaut 500, cap 4000),
`cwcycle_keeptime` (défaut 15, cap 240), `cwcycle_onbad` (défaut 1), `cwcycle_dropold`
(défaut 1) + **support wire des métadonnées de cycle souhaité** (arbitrage) : transport du
`cycletime` et du flag next-cw-cycle **dans les push cacheex CCcam/CS378x** (layout wire OSCam,
`cycletime < 128`), consommé par le moteur — **CSPv2 v1 non modifié**.

### `GET /api/admin/config/cw-cycle`
```json
{
  "enabled": false,
  "caids": [256, 1280],
  "maxList": 500,
  "keepTimeSec": 15,
  "onBad": "drop",
  "dropOld": true,
  "usePeerCycleMetadata": false,
  "version": 1,
  "updatedAtMs": 1695816000000,
  "updatedBy": "admin"
}
```
- `caids` : int 1..65535, ≤32, trié, doublons interdits ; **vide = toutes**.
- `maxList` : 16..4000 (défaut 500). `keepTimeSec` : 5..240 (défaut 15).
- `onBad` : `"log"` | `"drop"` (défaut `"drop"`, équiv. `onbadcycle=1`).
- `dropOld` : boolean (défaut true).
- `usePeerCycleMetadata` : boolean (défaut false) — exploite le cycletime/flag next-cycle
  transporté par les push cacheex reçus (wire CCcam/CS378x uniquement).

### `PUT /api/admin/config/cw-cycle`
Body complet (remplacement) + `version` (optimistic par ligne, `409 CONFLICT` existant) →
`200 VersionResponse`. Singleton `csp_cw_cycle_config` (migration additive).

### Stats runtime : `GET /api/admin/dcw/stats` (existant), nouvelle clé
```json
"cwCycle": { "enabled": true, "trackedEntries": 123, "checks": 4567, "badCycles": 8, "dropped": 5, "logged": 3 }
```

---

## 6. CacheEx CCCam + CS378x entrant/sortant (extensions des contrats existants)

### 6.1 Connecteurs sortants — extensions `PUT /api/admin/config/connectors/{name}`

`ConnectorDto` gagne :
```json
"cacheexMaxHop": 10
```
- `cacheexMode` : 0/2/3 (existant). **Sémantique conservée** (§4) : mode 2 = réception seule,
  mode 3 = émission seule. **Pas de bidirectionnel** (arbitrage D2 — le comportement central
  actif aujourd'hui est déjà conforme OSCam et ne doit pas changer).
- `cacheexFilter` (existant) : mode 2 = notre filtre annoncé au serveur (gate de réception) ;
  mode 3 = la gate est le filtre reçu du pair (runtime, non configurable, exposé `filterActive`).
- `cacheexMaxSend` (arbitrage D3) : **borne de file d'émission** — nombre maximal de messages
  push en attente vers ce pair, int 0..10000, **défaut 2000**, null/0 = défaut. File pleine →
  drop + compteur `queue`. Pas d'unité de débit.
- `cacheexMaxHop` : int 0..10, défaut 10 (remplace le hardcodage CLM ; explicite, séparé).
- Runtime central requis : `ConnectorLifecycleManager` honore `cacheexMode` (déjà actif),
  `cacheexFilter`, `cacheexMaxSend`, `cacheexMaxHop` (actuellement maxHop figé à 10, filter et
  maxSend ignorés). Counters par pair → §4.

### 6.2 Politique cacheex entrante par utilisateur — extension `PUT /api/admin/users/{name}/secrets/{protocol}`

`UpsertProtocolSecretRequest` gagne un objet optionnel (stockage : colonnes additives sur
`csp_user_protocol_secret`) :
```json
"cacheex": { "mode": 3, "maxHop": 10, "filter": "0100:00006A" }
```
- `mode` : 2 | 3 (0/absent/null = off). **Sémantique** (§4) : mode 2 = csp-ng pousse vers les
  sessions du user (le client reçoit) ; mode 3 = csp-ng reçoit les push du user (le client émet).
- `maxHop` : 0..10 (défaut 10) — gate de réception (mode 3) et information d'anti-boucle des
  pushes émis (mode 2).
- `filter` : mode 3 (nous recevons) = filtre que nous annonçons au client (gate d'acceptation) ;
  mode 2 (nous émettons) = valeur indicative seulement, la gate effective est le filtre reçu du
  client à la session (`filterActive`).
- Sessions concernées : `CcCamSession` (CCcam entrant — pas de cacheex aujourd'hui) et
  `Camd35TcpSession` (CS378x entrant — commandes `0x3C–0x3F` actuellement droppées → à traiter).
- `ProtocolSecretResponse` (GET/PUT) reflète `cacheex` (métadonnées seulement).

### 6.3 Nouveau connecteur sortant `cs378x`

`protocol: "cs378x"` autorisé (client camd35-over-TCP : auth user/`passwordRef`,
crypto/framing réutilisés de `Camd35Crypto`/`Camd35FrameCodec`, push cacheex 0x3C+). Mêmes
contrats que les autres connecteurs (PUT/DELETE/usage), mêmes champs `cacheex*` (§6.1),
compteurs §4 (`direction: outbound, protocol: cs378x`).

### 6.4 Worker (parité)

- `WorkerRuntime.buildConnector` : cesse de forcer `CACHEEX_MODE_OFF` ; honore
  `cacheexMode/cacheexMaxHop` (+ filter/maxSend une fois transportés).
- `RelayWorkerContract.ConnectorView` gagne `cacheexFilter, cacheexMaxSend, cacheexMaxHop`
  (additif, constructeurs compat) ; `UserSecretView` gère l'objet `cacheex`. CSPv2 wire inchangé
  (v1 gelé — le desired existant transporte ces champs).

---

## 7. Phases d'implémentation (scope)

| Phase | Contenu | Modules |
|---|---|---|
| **P1a** | `POST /api/admin/secrets/materialize` — fichier unique opaque, CREATE_NEW anti-symlink, 0600/0700, 6 purposes (§1) | csp-web (Java), csp-app |
| **P1b** | Migration `V15` : `csp_connector.cacheex_max_hop` ; colonnes cacheex `csp_user_protocol_secret` ; table `csp_cw_cycle_config` | csp-app (Flyway) |
| **P1c** | `PUT /api/admin/config/{connectors\|profiles\|listeners\|users}/bulk` — versions par ligne, ordre croissant id, transaction unique, rollback total, audit (§2) | csp-web, csp-config, csp-users |
| **P1d** | `GET/PUT /api/admin/config/cw-cycle` (CRUD config ; moteur en P4) | csp-web, csp-config |
| **P2a** | `GET /api/admin/cache/status` — fraîcheur explicite, null si absent/périmé (§3) | csp-web, csp-cache |
| **P2b** | `CacheExStatsRegistry` + instrumentation `CcCamConnector` (mode 2) + `GET /api/admin/cacheex/stats` + `/reset` (§4) | csp-connectors, csp-web |
| **P3a** | CLM honore filter/maxSend(file)/maxHop ; mode 3 : fan-out push depuis `CacheVerticle` (event bus, single-writer respecté), file bornée `maxSendQueue` | csp-app, csp-connectors, csp-cache |
| **P3b** | Connecteur `cs378x` sortant + push (§6.3) | csp-connectors |
| **P3c** | Entrants : `CcCamSession` push (policy user §6.2), `Camd35TcpSession` 0x3C–0x3F, compteurs inbound | csp-sessions, csp-app |
| **P3d** | Parité worker (§6.4) | csp-worker, csp-config |
| **P4** | Moteur `CwCycleChecker` (csp-filters) + wiring réponses connecteurs & pushes + métadonnées de cycle wire (push CCcam/CS378x, `usePeerCycleMetadata`) + stats `/dcw/stats` | csp-filters, csp-codec, csp-connectors |
| **P5** | Validation : tests unitaires/modules, CI global, interop manuelle OSCam | tous |

CI global (inchangé) :
```bash
mvn -B clean test -Dtest='!*PgTest,!*PgIntegrationTest,!UserRepositoryAclPartialTest,!AdminBootstrapDirectTest,!ConfigUpsertAbsentTest,!CccamImportServiceTest'
```
P1 autoportant pour le frontend. Ordre P1→P2 parallélisable avec P3 après P2b (registry).

## 8. Arbitrages enregistrés (v2)

| ID | Arbitrage | Statut |
|---|---|---|
| D1 | REFUS génération globale — bulk versions **par ligne** `{id, version}`, ordre déterministe croissant, rollback total | ✅ intégré §2 |
| D2 | Sémantique modes **conservée** (reader mode2 reçoit / mode3 émet ; inbound mode2 nous émet / mode3 nous recevons) — vérifiée module-cacheex.c ; central actif déjà conforme, **pas de bidirectionnel** | ✅ intégré §4/§6 |
| D3 | `cacheexMaxSend` = **borne de file push** (messages, défaut 2000), pas de débit ; `cacheexMaxHop` explicite séparé | ✅ intégré §6.1 |
| D4 | Politique cacheex entrante sur la ligne **secret protocole user** | ✅ intégré §6.2 |
| SEC | Materialize : fichier **unique opaque** par appel (jamais d'overwrite d'un secret référencé), POST only, 6 purposes, owner strict, anti-symlink CREATE_NEW | ✅ intégré §1 |
| STA | Stats cache : absent/périmé ⇒ **null/inconnu** + fraîcheur explicite (`statsAgeMs`, `statsFresh`), jamais de faux zéros ni d'état déduit | ✅ intégré §3 |
| CW | Noyau 6 options OK + **métadonnées de cycle wire** (push CCcam/CS378x, `usePeerCycleMetadata`), CSPv2 v1 non modifié | ✅ intégré §5 |

Risques résiduels : R1 — bulk users traverse `UserRepository` (versions `csp_user`, audit) ;
R2 — le fix CLM (filter/maxSend/maxHop) modifie le comportement runtime de connecteurs cacheex
**actifs** (maxHop était figé à 10 = défaut : aucun changement observable si config ≤ défaut) ;
R3 — fan-out mode 3 : nouvelle address event bus depuis `CacheVerticle` (conception P3a) ;
R4 — fichier orphelin materialize possible (documenté, pas de DELETE par sécurité).

## 9. Garde-fous de périmètre (rappel)

- Ressources frontend `csp-web/src/main/resources/**`, tests UI, pom frontend : autre agent.
- Java csp-web (resources/dto admin) : autorisé. Référence OSCam : lecture seule, GPLv3.
- CSPv2 v1 gelé. Aucun secret réel lu. Pas de commit tant que non demandé.

## 10. Notes d'implémentation réelle (passes P1–P4, validées par tests)

Statut : **IMPLÉMENTÉ ET TESTÉ** — suite globale CI verte + suite PG verte (voir §11).
Écarts au contrat v2 (explicites, non silencieux) :

| ID | Écart / précision | Justification |
|---|---|---|
| I1 | ~~Bulk `patch` : `null` = **inchangé**~~ → **RÉVISÉ §12-C3** : présence JSON distinguée (`absent` ≠ `null` explicite, ensemble threadé au repo) ; `null` explicite sur NULLABLE = **reset** (NULL ou défaut unitaire : `reshareCap→0`, `cardMode→'manual'`, `newcamdDialect→'legacy'`, `newcamdPasswordEncoding→'plaintext'`, `cardDiscovery→false`, `proxyName ""→NULL`) ; `null` sur NOT NULL = **400** champ nommé ; sets : `[]` = vide, `null` = 400 | Conforme au contrat §2 (qui promettait déjà reset/400) ; jamais de succès silencieux |
| I2 | Bulk users : réponse avec **noms canoniques** (casse DB) | `lower(name)` côté repo ; le frontend doit réconcilier sur le nom retourné |
| I3 | ~~Worker force OFF~~ → **RÉSOLU §12-C1** : `WorkerCacheExBridge` — réception indexée par hash servie via hook `CacheExLookup`, émission via `onCacheFeed` + forwarding, compteurs → `WorkerStatsView.cacheex` → merge central ; plus aucun OFF (connecteurs et policies users honorés) | Pont vers les vrais caches, sans CacheVerticle ni double cache fantôme ; limites restantes : connecteurs dynamiques (profil null) non routables, pas de CW-cycle worker (config non transportée) |
| I4 | Décodeur camd35 : le 0x3C était **rejeté** (OVERFLOW) par le code pré-existant — corrigé en longueur 8 bits == 242 (conforme module-camd35.c l.328-334) | Bug réel trouvé par les tests loopback ; sans ce fix, aucun FILTER 0x3C n'est décodable |
| I5 | SQL `= ANY($1)` : **tableaux Java requis** (`String[]`/`Long[]`), pas de `List` (le client PG réactif refuse la coercition) | Bug réel trouvé par les tests PG |
| I6 | CLM/ListenerManager **nul-tolérants** hors CDI (registry/service null ⇒ pas de stats/check, comportement historique) | Permet l'instanciation directe (tests) sans changer le comportement prod injecté |
| I7 | Materialize : audit DB best-effort `SECRET_MATERIALIZED` (purpose/owner/ref, **jamais valeur**) via `ConfigRepository.auditEvent` ; échec d'audit ⇏ échec requête | Convention « mutations auditées » respectée sans fragiliser l'écriture du secret |
| I8 | CS378x ECM sortant : `prid=0` (EcmKey ne porte pas le provider) | Limitation honnête : le serveur dérive le provider de l'ECM ; documenté |
| I9 | ~~Chemins minimaux~~ → **RÉSOLU §12-C2** : `UpstreamPathStore` partagé (4 émetteurs) — tout push reçu mémorisé (tous modes) ; tout re-push émet `[ownNode, ...upstream]` + cycle stocké (prioritaire) sinon checker ; anti-boucle + max-hop sur chemin complet | Triangle A→B→C→A impossible (1er noeud répété droppe `loop`, longueur bornée) ; dédup `(dataHash:pair)` ; jamais de reflet source |
| I10 | Registry : borne **512 paires**, éviction least-recently-active ; raisons bornées {loop,hop,checksum,filtered,invalid,queue} ; rejets CW-cycle comptés `invalid` | Bornes testées |
| I11 | `onBad=log` ne rejette jamais (`shouldReject` false) ; `OLD` rejeté selon `dropOld` | Sémantique testée (log vs drop) |
| I12 | Exécution tests : **toujours `-am` ou racine** — sans `-am`, les tests utilisent les jars installés obsolètes (`NoClassDefFoundError` silencieux côté sockets) ; aucun `mvn install` volontaire (ne pas polluer `~/.m2` partagé) | Piège diagnostiqué et documenté |

Composants livrés (fichiers) :
- P1 : `V15__cacheex_cwcycle.sql`, `CwCycleConfig`, `ConfigSnapshot.cwCycle`, `ConfigRepository` (load/updateCwCycle, bulk ×3, auditEvent, cacheex_max_hop, worker secrets), `BulkPatches`, `BulkUnknownItemsException`, `ConnectorConfig.cacheexMaxHop`, `AdminBulkResource` + 2 mappers, `AdminConfigResource` (cw-cycle, cacheexMaxHop), `SecretMaterializer.materializeUnique`, `AdminSecretResource`, `UserBulkPatch`, `UnknownUsersException`, `UserRepository.bulkUpdateUsers`, `UserProtocolSecretRepository` (+cacheex), `ProtocolAuthResolver` (+policy, Fake), `PgProtocolAuthResolver`, DTOs secrets (+cacheex).
- P2 : `CacheExStatsRegistry`, `AdminCacheexResource`, `AdminCacheResource`, `RuntimeCore.cacheExStatsRegistry`.
- P3 : `CcCamConnector` (filtre annoncé mode 2, file bornée, compteurs, états, `noteUpstreamPath`, auto-enregistrement paire), `CwsConnectorManager` (+cs378x), `CLM` (honore filter/maxSend/maxHop, cs378x, nul-tolérant), `Camd35CacheExCodec`, `Camd35TcpConnector` (idem + bridge), `CcCamSession` + `Camd35TcpSession` (policy, push/filter/id, transport, compteurs, bridge, nul-tolérants), `AbstractSession.listenerId`, `ListenerManager` (wiring), `RelayWorkerContract` (+champs cacheex), `WorkerDesiredBuilder`, `WorkerRuntime` (bridge, hook lookup via routeurs, policies réelles, cs378x), `WorkerAuth` (policies réelles), `WorkerCacheExBridge`, `CacheExBridge`/`CacheExEmitter`/`UpstreamPathStore` (SPI partagés).
- P4 : `CwCycleChecker` (+reconfigure, gate interne), `CwCycleService` (+producer), `StrictEcmRouter.setCwCycle` + rejet failover + hook `CacheExLookup` (après ACL, avant gate cartes), `CcCacheExCodec` (spec filtre, métadonnées [18]), `Camd35FrameCodec` (rcByte, len 16 bits 0x3D-0x3F, 0x3C 242), `AdminDcwResource` (+cwCycle), `AdminCacheexResource` (merge paires worker préfixées).
- P5 : 20 classes de tests nouvelles (voir §11), 2 bugs réels corrigés (ANY arrays, virgule SET users), 1 bug pré-existant corrigé (0x3C), 1 race de test corrigée.

## 11. Preuves de validation

- Compile : `mvn -B -q compile/test-compile` global OK.
- Suite globale CI (commande §7) : **BUILD SUCCESS**, 16 modules, ~6 min (2026-09-27, après derniers changements).
- Suites PG (DB isolée localhost:5433 `csp-ng-test-pg-proxy`, schéma reconstruit + Flyway V1→V15) : `BulkCacheexCwCyclePgTest` 12/12 (bulk + null/reset/400 + sets) + 12 classes PG existantes — **107/107 vert** (2026-09-27).
- Loopback wire : CCcam entrant mode 2/3 + loop/hop/forward (6 tests), CS378x entrant (2 tests), CS378x sortant (2 tests), connecteur CCcam triangle (11 tests : forward-path, hop, no-reflect, dédup), worker e2e (push→ECM servi, connecteur→faux serveur), CW-drop routeur (2 tests), bulk guards (5 tests), contrats endpoints (6 tests), provider nonzero (2 tests), concurrence checker, registry/store/codec/config/materializer unitaires — tout vert.
- Non-régression : sessions, connecteurs, web, worker, app, CSPv2, UDP — suites existantes vertes.

## 13. Revue d775bc8 — correctifs appliqués (preuves, pas de proclamation)

### B1 — CW-cycle worker livré (bloqueur levé)
- Contrat : `CwCycleView` additif (validation miroir `CwCycleConfig`, `DEFAULT`,
  `from()`/`toConfig()`, compat Jackson native — records sérialisables sans annotation).
- `DesiredWorkerConfig` + champ `cwCycle` (compat 14/13/8 args → DEFAULT off).
- `WorkerDesiredBuilder` transporte `CwCycleView.from(snap.cwCycle())`.
- `WorkerRuntime` : checker partagé paresseux, `reconcileCwCycle` (création uniquement
  sur changement SÉMANTIQUE — version/horodatage/acteur exclus ; renouvellements TTL
  même version court-circuités avant, historique préservé), push vers routeurs/
  connecteurs/sessions live + câblage à la création (`wireCwCycle`).
- Contrôles : dispatch routeur (existant, actif dès checker câblé), push entrants
  (sessions/connecteurs, existant), hits lookup pont NON re-checkés (single-check C5).
- Stats honnêtes : `WorkerStatsView.cwCycle` (null si jamais configuré) remonté,
  fusionné dans `/dcw/stats` sous `cwCycleWorkers` (absent/illisible => pas d'entrée).
- Preuves : `WorkerCwCycleEndToEndTest` (frozen/swap refusés, log autorise, off
  rétrocompatible via ctor 14 args, reconfig effective, TTL sans reset),
  `WorkerDesiredBuilderTest.transportsCwCycleView`.

### Bugs avérés corrigés
- Compteur `dropped` sur OLD avec `dropOld=false` : suit désormais `shouldReject()`
  (OLD non rejeté => `logged`, jamais `dropped`). Test dédié.
- `evictExpired` : balayage complet borné (plus de `break` — l'ordre d'insertion
  ne reflète pas l'expiration car `lastUsedMs` évolue aux accès). Test déterministe.
- R1 : `isPushValid` accepte `E_FOUND(0)` ET `E_UNHANDLED(100)` (miroir
  module-cccam-cacheex.c:1311, comme CS378x) + vecteurs 0/100 vs 1/2/99/101/255.
- R2 : oddEven inconnu => rejet `invalid` AVANT cycle/store/injection (4 chemins
  réordonnés) + test loopback 0x00 (rien sous 0x80/0x81).
  **ARBITRAGE ULTÉRIEUR (compat OSCam, remplace ce qui précède)** : `0` accepté
  comme INCONNU distinct (jamais converti en 0x80) — servi réellement (lookup
  hash central, fallback exact-puis-unknown pont), bypass cycle compté `IGNORED`
  (ni apprentissage, ni frozen/swap) ; autres valeurs non-80/81 rejetées
  `invalid` (corruption wire, seul 0 a un sens défini côté OSCam) ; TX normalisé
  en 0 (miroir :1230/:1335). Voir §13.
- R4 : `verifyTargetInsideAllowedRoot` (`toRealPath` hors boucle, parent==racine
  réelle, racine sous allowed réelles ; self-allow retiré) + test symlink-dans-arbre.
- R3 : gate RX par pair — sessions : budget `allowedEcmRate` existant, fenêtre
  dédiée `<user>/cacheex` (budgets ECM/push séparés) ; connecteurs : `RxFloodGate`
  6000/min documenté ; raison `flood` dédiée (merge admin étendu) ; test loopback
  (3 admis/2 droppés) + test coût (<100 ms/10k).
- R6 : VÉRIFIÉ SANS MODIFICATION — buf[18] = cycletime(&0x7F)+flag bit7, aucune
  exception 0100@00006A dans le path (grep vide), notre parse est compatible.
- BulkPatches javadoc : distinction absent/null/reset/[] documentée (conforme C3).

### Limites restantes (assumées, testées ou notées)
- HTTP→PG composition (bulk rollback, materialize→ref, cw-cycle persist via HTTP) :
  pas de pattern @QuarkusTest+PG existant — SUIVI EXPLICITE, non proclamé.
- Frontend `types.ts` : ajouter la clé `flood` aux raisons (signalé, non modifié).
- CW-cycle worker : pas de re-check des hits lookup (voulu, C5) ; pas de checker
  partagé avant le premier desired (stats `cwCycle: null`, honnête).

## 12. Corrections orchestration (revue, sans réduction de scope)

### C1 — Worker CacheEx réel (plus de OFF forcé)
`WorkerCacheExBridge` (csp-worker) relie les paires aux VRAIS caches : réception
indexée par hash `(profil, caid, provider, sid, parité, dataHash)` (store borné
2048/TTL 30 s — le cache strict exigeant le corps exact ne peut pas servir les
push), servie via hook `StrictEcmRouter.CacheExLookup` (après ACL, avant gate
cartes — une CW poussée est servable sans carte ; validée par la politique DCW
effective, sans check de provenance déjà gaté à la réception). Émission via
`onCacheFeed` + forwarding (cibles `CacheExEmitter` : `onReply` partagé avec le
central — gates filtre/hop/file/dédup/anti-reflet identiques, aucun nouveau bus).
Compteurs : registry worker-local → `collectStats()` → `WorkerStatsView.cacheex`
(nouveau champ additif) → payload ack → `GET /api/admin/cacheex/stats` fusionne
avec préfixe `<node>/` (jamais de zéros si payload absent). Preuves :
`WorkerCacheExBridgeTest` (6), `WorkerCacheExEndToEndTest` (push→ECM servi,
connecteur→faux serveur). Connecteurs/sessions worker honorent mode/filter/
maxSend/maxHop + policies users réelles (plus de OFF silencieux).
Limites restantes : connecteurs dynamiques (profil null) non routables côté
worker (profil requis pour le routeur) ; pas de CW-cycle worker (config non
transportée dans le desired).

### C2 — Node path / cycle de bout en bout
`UpstreamPathStore` partagé (LRU 1000, copies défensives, cycle 0..127) dans les
4 émetteurs : tout push reçu est mémorisé (tous modes) ; tout re-push émet
`[ownNode, ...upstream]` + cycle stocké (prioritaire) sinon checker. L'anti-boucle
(`containsNode`) et le max-hop portent sur le chemin COMPLET : un triangle
A→B→C→A est impossible (le 1er noeud répété droppe `loop`, et la longueur est
bornée) ; `maxSendQueue` borne chaque file ; dédup `(dataHash:pair)` ; jamais de
reflet vers la source (`originAddress`). Preuves : forward avec chemin
(connecteur + session), hop bound, no-reflect, dédup, loop/hop réception,
`UpstreamPathStoreTest` (7).

### C3 — Bulk null (révisé, remplace I1)
Présence JSON distinguée (`absent` ≠ `null` explicite, ensemble threadé au repo) :
`null` explicite sur colonne NULLABLE = **reset** (NULL, ou défaut unitaire :
`reshareCap→0`, `cardMode→'manual'`, `newcamdDialect→'legacy'`,
`newcamdPasswordEncoding→'plaintext'`, `cardDiscovery→false`, `proxyName ""→NULL`) ;
`null` sur NOT NULL = **400** avec le champ nommé (jamais de succès silencieux) ;
sets : `[]` = vide le set, `null` explicite = 400 (omettre pour inchangé).
Preuves : `BulkCacheexCwCyclePgTest` (reset filtre/email, 400 priority/enabled,
`[]` clear) + `AdminBulkResourceTest` (présence threadée).

### C4 — Provider
`EcmRequest`/`EcmKey` ne portent PAS le provider (par construction) : il transite
par `routeEcm(req, auth, provider)` depuis les en-têtes (CCcam payload, camd35
`prid`) → clé de cache, sélection candidats, scope DCW. Connecteurs sortants
(tous protocoles, pré-existant) : `prid=0` (limite I8 inchangée). Preuves :
`Camd35ProviderPropagationTest` (prid 0x6A intact frame→routeur, routages distincts
par provider, CW servie).

### C5 — CW-cycle : contrôle unique, thread-safety, bypass/failover
Matrice : réponse connecteur → check routeur (1×) → servie/évincée ; push reçu →
check réception (1×) → injecté ; émission/forwarding → JAMAIS re-checké (lecture
seule `cycleMetadataFor`) ; hit cache → pas de check (comme central). Verdict
`OLD`→`dropOld`, `FROZEN/SWAP`→`onBad` ; `log` = compte sans rejeter ; routeur :
rejet = failover candidat suivant (jamais servi) ; sessions : rejet = drop
(pas de failover possible sur push non sollicité). `check()` synchronized +
compteurs atomiques ; gate `enabled/caids` interne (défense en profondeur).
Preuves : `StrictEcmRouterCwCycleTest` (rejet+compteurs, off=no-op), test
concurrence (2000 checks/8 threads, compteurs exacts, borne tenue).

### C6 — Owner secrets
Règle alignée : `owner` 1..64 caractères, **casse préservée en API** (réponse et
audit) ; seul le NOM DE FICHIER est slug-normalisé (minuscules, invalides→`-`,
rejet si vide après normalisation). Les majuscules NE SONT PAS interdites ;
deux owners ne différant que par la casse restent uniques (suffixe aléatoire).
Preuve : `SecretMaterializerUniqueTest.uppercaseOwnerAcceptedSlugLowercasedNoCollision`.

### §13. Arbitrage oddEven=0 — compatibilité OSCam (remplace R2-rejet)
- **RX (4 chemins : CcCamConnector, Camd35TcpConnector, CcCamSession,
  Camd35TcpSession)** : `0` accepté comme INCONNU distinct ; `0x80`/`0x81`
  inchangés ; toute autre valeur rejetée `invalid` (seul 0 a un sens défini —
  `buf[19] != 0x80 && != 0x81 ? 0` côté OSCam :1335, sans inventer de known).
- **Cycle** : l'inconnue contourne le contrôle (`Verdict.IGNORED`, jamais rejeté,
  comptabilisé `ignoredCount`, exposé `bypassed` dans `/dcw/stats` + remontée
  worker) — aucune contamination des moitiés, aucune fausse détection.
- **Servi réellement** : central via égalité hash `EcmKey` (pas de clé-0
  inutilisable) ; pont via fallback exact-puis-unknown (mêmes
  profil/caid/provider/sid/hash, pas de wildcard) ; ACL/DCW inchangés
  (hook routeur après ACL + validation DCW).
- **Paths/dedup/anti-boucle** : l'inconnue est stockée (`noteUpstreamPath`) et
  re-poussée avec chemin complet + cycle stocké — triangle/hop/dedup/anti-reflet
  identiques aux parités connues.
- **TX (4 chemins)** : tags non-80/81 normalisés en 0 sur le wire
  (`CcCacheExCodec.normalizeOddEven`, miroir :1230 ; pidField camd35 idem).
- **Preuves** : tests rejet-0 remplacés par acceptation+servie (connecteur +
  session loopback), `TxNormalize`, `unknownParityBypassedAndCounted`,
  fallback pont (`unknownParityFallbackServes`, `exactBeatsUnknownFallback`) ;
  double `wireCwCycle(session)` retiré.
