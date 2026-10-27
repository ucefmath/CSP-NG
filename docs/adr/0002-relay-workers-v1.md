# ADR 0002 : Workers relais CSP/CCcam/CS378x + administration centrale (v1, socle W0-W3)

- **Statut** : Accepté (socle central W0-W3) — Worker headless natif (W4-W6) hors périmètre, contrat préparé.
- **Date** : 2026-09-24
- **Contexte** : `docs/INBOUND-CAMS-PLAN.md`, `docs/INBOUND-CAMS-PROGRESS.md`, ADR 0001 (CSPv2 wire v1, `docs/adr/0001-cspv2-wire-v1.md`).
- **Docs officielles lues avant code** : Quarkus `building-native-image`, `native-reference`, `native-and-ssl`, `maven-tooling` (builder UBI9 `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21`, `quarkus.native.container-build=true`, SSL natif, `quarkus.native.resources.includes`) ; Quarkus REST/Jackson `RolesAllowed`, reactive-pg-client, Flyway (migrations additives transactionnelles).

## 1. Objectifs W0-W3 (ce lot)

Socle central + politiques réellement branchées, sans worker distant :
caches/connecteurs/nodes activables, matrice par listener `consultation/alimentation/export` à chaud,
`desired/applied` versionné sans faux ack, stats réelles (joignabilité vs utilité),
isolation autorisations/provenance/fraîcheur/anti-boucles/bornes, UI vanilla existante.

## 2. Décisions normatives

### 2.1 Worker SANS accès PG central (correction cadrage §1)
- Le worker relais `NE DOIT JAMAIS` ouvrir de connexion PostgreSQL vers la centrale.
  Le runtime worker est `stateless` : il consomme la config via canal contrôle **HTTPS dédié**
  (`GET /api/worker/desired`, `POST /api/worker/ack`, contrat §5, implémentation complète W4).
- La centrale reste seule source de vérité (PostgreSQL + `ConfigSnapshot` + epoch).
  Références : `csp-app/.../db/migration/V10__relay_workers.sql`,
  `csp-config/.../RelayNodeConfig.java`, `RelayCacheConfig.java`, `ListenerCacheLinkConfig.java`.

### 2.2 Identité worker = identité mTLS authentifiée, jamais id auto-déclaré
- Réutiliser la PKI mTLS existante (`csp-cluster-tcp/.../CspV2Identity.java` : exactement 1 SAN URI
  `urn:csp:node:<id>`, regex `[a-z0-9][a-z0-9._-]{0,62}`, jamais CN/DN fallback, messages génériques).
- **Aucun nouveau type de trame CSPv2** : le framing ADR 0001 est figé (6 types v1).
  Le contrôle worker passe par HTTPS mTLS (`ClientAuth.REQUIRED`, CA cluster dédiée, comme
  `CspV2TlsOptionsFactory`), pas par `ECM_REQUEST/DCW_REPLY`.
- Règle : `authenticatedSanNodeId == URL/Body nodeId == csp_relay_node.node_name`, sinon `403`.
  Un `nodeId` fourni seul sans certificat valide est rejeté (fail-closed).
- Révocation : `csp_relay_node.enabled=false` OU `revoked=true` OU `authorized=false`
  => rejet immédiat + `applied` gelé + sessions/exports du node fermés au reconcile suivant.
  Rotation certificats = redémarrage/drain contrôlé (comme ADR 0001 §5.2, hors périmètre v1).

### 2.3 Pas de lease 2s arbitraire — expiration configurable bornée
- `csp.relay.worker-config-ttl-ms` (défaut `30000`, bornes `5000..300000`, MicroProfile).
  La centrale pose `config_expires_at = now() + ttl` à chaque `desired` publié.
- Le worker `DOIT` re-puller avant expiry ; config expirée = fail-closed
  (plus aucun routage/export avec l'ancienne politique, `applied` marqué `EXPIRED`).
- Tests exigés : expiry dépassée => refus, ack stale rejeté, révocation immédiate
  (`RelayCachePolicyTest`, `RelayPolicyServiceTest`, PG `V10RelayPgTest`).

### 2.4 Préservation comportement existant, détaché par défaut
- Migration **strictement additive** (`ADD COLUMN IF NOT EXISTS` / `CREATE TABLE IF NOT EXISTS`,
  `ON CONFLICT DO NOTHING`, aucun `ALTER` destructif, aucun seed de listener/cache actif).
- `csp_relay_cache.enabled=false`, `csp_listener_cache_link.enabled=false` par défaut ;
  aucun pair inconnu auto-activé (`authorized=false` défaut, pas de `auto_add_peers` relay).
- Snapshot vide = comportement legacy inchangé (routage `StrictEcmRouter` sans politique relay
  = `catalog_strict` actuel ; `ListenerManager` reconcile inchangé hors liens explicites).

### 2.5 Distinguer CSP protocole vs UDP cache
- `csp_relay_cache.kind` : `local` (StrictPartitionedCache/CacheVerticle),
  `node` (pair CSP via CSPv2 mTLS, consult/feed), `udp` (consult/feed/export legacy
  `UdpLegacyTransport` allowliste stricte — consultation implementee via
  `WorkerUdpExchange.consult`, pending par EcmKey).
- L'admin centrale affiche `kind` explicitement ; `udp` consultable uniquement via
  lien explicite + pair allowliste (sans pair assigne, la consultation echoue fermee
  au worker, jamais une erreur de config).

### 2.6 Matrice par listener à chaud
- `csp_listener_cache_link(listener_id, cache_name)` : `can_consult/can_feed/can_export/enabled`.
  `CHECK (can_consult OR can_feed OR can_export)`. Triggers `csp_config_notify`
  => `ConfigWatcher` => `RelayPolicyService.refresh()` + `ListenerManager` reconcile ciblé
  (fermeture sessions du listener ciblé seul, comme existant).
- Détachement immédiat : `enabled=false` ou lien supprimé => `RelayCachePolicy` refuse
  dès l'ECM suivant (pas d'attente TTL), `inflight` du couple purgé.

## 3. Politiques branchées au vrai routage

`csp-connectors/.../routing/RelayCachePolicy.java` (pur, sans Vert.x) :
- `canConsult(listenerId, cacheName)`, `canFeed(...)`, `canExport(...)` (fail-closed si lien absent/désactivé).
- `isFresh(cachedAtMs, nowMs, maxAgeMs)` (fraîcheur par cache, `500..60000`).
- `isLoop(originChain, localNodeId)` : si la chaîne contient déjà le node local => refus (anti-boucle).
- `checkProvenance(replyConnector, eligibleSet)` : `UNKNOWN PROVENANCE ALWAYS REJECT` (P7 conservé).
- `checkInflight(current, max)` : bornage `1..8192`, overflow => refus + `RATE_LIMITED`.
- `StrictEcmRouter` accepte un `RelayCachePolicy` optionnel : `peek` gated par `canConsult`,
  `put` gated par `canFeed`, dispatch filtré par ACL/profils existants + epoch.
  Sans politique (null) = comportement P7 inchangé.

`csp-app/.../runtime/RelayPolicyService.java` (@ApplicationScoped) :
- Construit la vue depuis `ConfigSnapshot` (nodes/caches/links), `refresh()` sur reload,
  expose `policyFor(listenerId)`, `isNodeUsable(nodeName)` (enabled+authorized+!revoked+!expired),
  sépare `reachable` (snapshot CspV2/UDP si présent) vs `useful` (éligible catalogue + ACL).

## 4. Config souhaitée/appliquée versionnée, sans faux ack

- `csp_relay_node.desired_version` bumpé à chaque publication centrale (optimistic-locking).
- `applied_version/applied_at` écrits **uniquement** sur `POST /api/worker/ack` authentifié
  (SAN vérifié, version == desired, non expiré). Jamais d'auto-ack local.
- `GET /api/admin/config/relay/status` retourne `desired/applied/lastSeen/expiresAt/stale`
  réels (null si jamais acké — jamais `0` fictif). `applied>desired` impossible (rejet `409`).

## 5. Contrat concret pour W4 (headless, à implémenter)

```http
GET /api/worker/desired?nodeId=<san-id>
Authorization: mTLS SAN urn:csp:node:<san-id>
=> 200 { nodeId, desiredVersion, expiresAtMs, ttlMs, listeners:[{listenerId,profile,protocol,port}],
         caches:[{name,kind,enabled,maxAgeMs,maxEntries,maxInflight}],
         links:[{listenerId,cacheName,canConsult,canFeed,canExport,enabled}],
         connectors:[{name,protocol,profile,enabled}] }  # subset autorisé seul, jamais config indépendante
=> 403 { code: WORKER_REVOKED|WORKER_UNAUTHORIZED|WORKER_EXPIRED }

POST /api/worker/ack  { nodeId, appliedVersion, ok, error }
=> 200 { appliedVersion } si appliedVersion==desired && SAN==nodeId && !expiré && !révoqué
=> 409 STALE_ACK si version != desired ; 403 si SAN mismatch/révoqué ; 410 si expiré
```

- DTOs préparés : `csp-config/.../RelayWorkerContract.java`
  (`DesiredWorkerConfig`, `WorkerAck`, `WorkerAckResult`, bornes TTL `5000..300000`).
- Dépendances worker (W4) : `domain,codec,cache,cluster-tcp,cluster-udp,connectors,sessions,config,users,filters`
  — exclure `csp-web`, Flyway JDBC, Elytron ; garder `reactive-pg-client` hors worker
  (worker = Vert.x HTTP client + mTLS PEM via `RuntimeSecretResolver` `env:/file:`).
- Connecteurs sortie worker = subset publié par centrale (`connectors[]` ci-dessus) ;
  toute création locale de connecteur est interdite (fail-closed au démarrage si config locale détectée).

## 6. Stats réelles, joignabilité vs utilité

- `GET /api/admin/connectors` existant (snapshots `CwsConnectorManager`, déjà réels) conservé.
- Nouveau `GET /api/admin/config/relay/status` : par node `{ desired, applied, reachable, useful,
  lastSeen, expiresAt, stale, revoked }`.
  - `reachable` = snapshot `CspV2Manager` ESTABLISHED ou UDP `PeerRegistry` vu <60s (si managers absents => `null`, jamais `false` fictif).
  - `useful` = `reachable && nodeUsable && ≥1 source catalogue online + ACL` (via `CardDiscoveryService` si présent).
- UI vanilla (`admin/assets/app.js`) : onglet `Relais` affichant ces champs + matrice liens ;
  aucun contrôle factice (boutons désactivés si `version` manquante ou `NOT_READY`).

## 7. Natif Debian 13 Trixie x86_64 (W5-W6, non livré ici)

- Cible : binaire Linux x86_64, `baseline CPU portable` (`-march=x86-64`, jamais `march=native`),
  libc documentée (glibc Trixie), test artefact **sous** Trixie (container `debian:trixie-slim`).
- Interdit : prétendre qu'un builder UBI9 = build Trixie. Le builder Quarkus par défaut est UBI9 ;
  W5 `DOIT` choisir builder Debian-compatible ou build natif sur hôte Trixie + publier
  `ldd --version`, `file <runner>`, `uname -m`, sortie smoke-test Trixie.
- Config Quarkus à créer W5 : `quarkus.package.type=native`, `quarkus.native.container-build=true`,
  `quarkus.native.builder-image` Debian-compatible, `quarkus.native.resources.includes`
  (`jgroups-udp-csp.xml`), SSL natif (`native-and-ssl`), exclusion `META-INF/resources/admin` du worker.

## 8. Non-objectifs W0-W3 (reste W4-W6, à noter UI/docs)

- Module `csp-worker` headless, canal HTTPS worker mTLS complet, packaging `.deb`/service,
  build natif + tests natifs JVM/parité, bridge auto UDP<->TCP (reste interdit, ADR 0001 §10),
  rotation certificats sans coupure, `nonProxyHosts` relay.

## 9. Critères d'acceptation W0-W3

- [ ] V10 additive, `NOT_READY` jamais masqué, détaché par défaut vérifié PG.
- [ ] `RelayCachePolicy` 100% unit vert (consult/feed/export, fraîcheur, boucles, provenance, inflight).
- [ ] Détachement immédiat prouvé (test : disable lien => ECM suivant refusé, pas de `put`).
- [ ] `desired/applied` sans faux ack (test : pas d'ack local, stale => 409, expiré => fail-closed).
- [ ] Stats sans zéros fictifs (test : managers absents => `null`, pas `0`).
- [ ] UI `Relais` rendus depuis API réelles, CSRF+audit comme existant (`X-CSP-Requested-With`, `actor`, `409`).

## 10. Correctifs review Sonnet (increment W3+ : appliques)

1. Stockage distinct par cache nomme (`RelayCacheStores`) : chaque cache a son
   `StrictPartitionedCache` borne ; scope ACL via `eligibleSet`, provenance
   verifiee par entree, fraicheur via `maxAge` au `put`. Detacher c1 ne touche
   jamais c2 (`RelayTwoCachesTest`). Mutualisation sure : stores partages par
   cache (pas par session), cles partitionnees.
2. `ConfigRepository.isUndefinedTable` strict 42P01 : seule l'absence de table
   relais pre-V10 donne un snapshot vide ; toute autre panne PG propage
   (`RelayUndefinedTableTest`).
3. `RelayPolicyService` abonne `CONFIG_RELOADED_ADDRESS` (`onStart/onStop`,
   `onConfigReloaded`) ; plus aucun `refresh()` opportuniste par connexion ni
   au GET admin ; sessions ouvertes via supplier live
   (`RelayPolicyServiceTest.openSessionSeesReload`).
4. Ack persistant DB (`recordWorkerAck` transactionnel + audit, 409 stale /
   403 revoque / 410 expire) ; bump `desired_version` monotone + expiry sur
   tout changement cache/lien ; ack = version exacte appliquee.
5. Stats : `servedResponses` reels (`consultHits/feedPuts` par cache),
   `reachable` null si inconnu (pas de faux booleen), `useful` via usable+expiry.
6. `localNodeId` obligatoire si relais actif (fail-closed) ; endpoint admin et
   desired coherents. La garde historique "consult UDP export-only" de l'endpoint
   admin a ete supprimee (correctif UDP consult) : la consultation UDP est
   implementee (`WorkerUdpExchange.consult`) et le worker echoue ferme sans
   pair allowliste.
   Canal worker mTLS sur port dedie (`WorkerControlServer`, SAN seule source
   d'identite) ; worker `csp-worker` sans PG/UI ; desired complet sanitise
   (`WorkerDesiredBuilder`, refs seules). UI relais avec actions
   activer/desactiver/brancher/debrancher/supprimer. Natif Trixie : runbook
   honnete `docs/packaging-worker.md` (JVM livree, natif reste a faire).

## 11. Echange inter-noeuds : UDP allowliste + CSPv2 cible + stats (livre)

1. `WorkerUdpExchange` : socket unique, `autoAddPeers=false`,
   `strictAllowlist` host+port exact, pairs du desired ; consult/feed/export
   gates par liens ; requetes distantes servies depuis l'index local
   (jamais re-emises) ; replies sur pending vivant + checksum + origine
   (pas d'echo) ; detachement immediat ; failClosed ferme tout.
   `UdpLegacyTransport` : hooks `UdpWorkerHooks` (null = legacy central
   inchange), `sendTo` public, compteur `notAllowlisted`.
2. Policy : `udp` consultable/alimentable via lien explicite (transport cable
   separement) ; dispatch local n'alimente que les stores locaux
   (anti-masquage node/udp) ; hook `ExternalConsult` concurrent au dispatch
   (premier gagne, re-validation live avant put + completion) ;
   `CacheFeedListener` (export UDP).
3. CSPv2 : `requestFromPeer` cible (jamais de fallback), `consultNode`
   (validation + identite `node:<peer>`), endpoint dial `csp_host/csp_port`
   (NULL = accept-only), caches node = noms distants (self rejete),
   utilisabilite distante vue centrale.
4. Stats worker->central : `WorkerStatsView` borne (connecteurs on/off +
   ecmOk, UDP + RTT, caches hits/puts, nodes + RTT) via ack ->
   `csp_relay_node_stats` (8 Ko) -> status -> UI. Latences par connecteur
   amont : voir §12.

## 12. Lot final : dispatch mesure, TLS hostname, UDP opt-in, natif e2e

1. Stats dispatch reelles (`DispatchStats` : ok/echec/timeout + latence par
   connecteur, timeout attribue a la tentative active exacte, borne 128) ;
   `ConnectorStatsView` += ecmFail/timeouts ; fusion multi-routeurs.
   Re-validation policy avant completion externe post-revocation.
2. TLS hostname verification DESACTIVEE (client CSPv2) — justification
   complete, auditee ligne a ligne :
   - Serveur : `ClientAuth.REQUIRED` + extraction SAN URI + allowlist sur
     l'id EXTRAIT (`onAccept` : inconnu => close).
   - Client/dial : CA trust + extraction SAN + allowlist sur l'id EXTRAIT
     (`CspV2Connection.open`, commune aux deux sens) ; l'adresse dialee
     n'est qu'un indice, jamais une identite (DNS hijack => close ou
     attribution correcte, jamais d'usurpation).
   - HELLO entrant doit egaler l'id extrait (`IDENTITY_MISMATCH` => close) ;
     nodeId regex aux 3 niveaux ; `requestFromPeer` ne selectionne que
     l'id exact etabli.
   - Les certificats ne portent que des SAN URI (jamais DNS) : verifier le
     hostname serait a la fois casse (tout rejet) et inutile (n'apporte
     rien au-dela de SAN+allowlist). Portee : uniquement le client CSPv2
     (`CspV2TlsOptionsFactory.clientOptions`) ; le canal HTTPS worker
     (serveur central + `WebClient` worker avec `verifyHost(true)`) garde
     la verification hostname (cert central a SAN IP/DNS).
3. UDP NON authentifie = opt-in explicite, jamais trusted : allowlist
   host+port, pas d'auto-apprentissage, wire matching granularite EcmKey
   (limite du protocole legacy : pas de body sur le fil), scope store en
   exactBody via contexte pending (provider/epoch/eligible reconstruits a
   l'identique), provenance origine non vide + checksum, spoof DNS contré
   par match exact (pas de repli host-only en mode strict).
4. Copies defensives verifiees : `CwReply`/`StrictCacheKey`/`EcmRequest`
   clonent (ctor + accesseurs) ; l'echange ne stocke que des refs immuables.
5. Natif e2e (`WorkerNativeE2ETest`, binaire via ProcessBuilder, CA/certs
   openssl sous `target/`, jamais prod) : desired/ack/stats reels,
   listener CCcam vivant (seed lue), entree inconnue rejetee, revocation
   403 => listener ferme. Dataplane ECM : JVM (`WorkerRuntimeTest`,
   `WorkerUdpExchangeTest`, `WorkerUdpConsultIntegrationTest`,
   `CspV2AuthLoopbackTest`).

## 13. Correctif UDP consult + activation ops (livre)

1. **Consultation UDP activee** : la garde historique de l'endpoint admin
   (`AdminRelayResource.upsertLink` refusait `canConsult` sur cache `udp`,
   "export-only") a ete supprimee. La consultation est implementee cote worker
   (`WorkerUdpExchange.consult` : pending par EcmKey, timeout borne,
   allowlist stricte host+port, checksum + origine non vide, store scoped
   exactBody). Preuves :
   - `csp-worker/.../WorkerUdpConsultIntegrationTest` : listeners CCCam+CS378x
     reels -> WorkerRuntime SANS connecteur local -> pair UDP allowliste -> CW,
     catalogue publie, cache hit (1 seule emission UDP pour 2 ECM identiques),
     detachement (NOK + store purge), pair absent de l'allowlist (echec ferme),
     export refuse sans lien, spoof REPLY hors allowlist droppe, anti-boucle
     (requete distante jamais re-emise).
   - `csp-web/.../AdminRelayUdpConsultLinkTest` : lien `udp` canConsult accepte
     par l'API admin.
   - `ConfigValidator`/`RelayCachePolicy` autorisaient deja `udp` consult via
     lien explicite (transport cable + pairs allowlistes requis).

2. **Catalogue publie requis pour tout routage worker** : un worker (connecteur
   amont OU consultation UDP) ne route un ECM que si la capacite
   `(profile, caid, provider)` est `state='published'` dans `csp_card_capability` :
   - `ConfigRepository.loadWorkerCatalog()` ne publie que `state='published'` ;
   - `WorkerDesiredBuilder.build` ne retient que `published` ;
   - `WorkerRuntime.seedDiscovery` ne publie que `published` ;
   - `StrictEcmRouter` (mode `manual`) exige `cap.state == PUBLISHED`.
   En prod (etat constate : 0 capacite publiee), aucune capacite n'est servie
   tant qu'au moins la capacite ciblee n'est pas publiee.

3. **Procedure ops (activation ciblee, sans publication massive)** :
   1. Publier UNIQUEMENT les capacites exactes des profils/caids servis :
      `PUT /api/admin/catalog/capabilities/{id}/state` body
      `{ "state": "published", "version": <version courante> }` (optimistic lock,
       une capacite a la fois, pas de `UPDATE ... WHERE 1=1`).
   2. **Bump desired obligatoire** : publier une capacite ne bump PAS
      `csp_relay_node.desired_version` ; or le worker n'applique un desired que
      si la version change (heartbeat sinon). Apres publication, re-PUT minimal
      d'un element relay du node concerne (ex. cache/lien a l'identique avec
      `version+1`) pour propager le catalogue au worker au poll suivant.
   3. Verifier cote worker : `WorkerRuntime.seedDiscovery` a bien publie la
      capacite (statistiques store/hits remontees via ack), puis tester la
      consultation UDP ciblee avant toute generalisation.
   Aucune publication massive n'est necessaire ni souhaitee : chaque capacite
   non publiee reste `discovered` et ne sert pas en mode `manual`.

4. **Auto-guerison du bail worker sur desired pull (deadlock redemarrage & downtime centrale > TTL resolus)** :
   - **Contexte & cause racine** : lorsque le worker est arrete > TTL (30s) ou apres un downtime central > TTL,
     le bail en base (`csp_relay_node.config_expires_at`) est expire. Au demarrage, le worker tire sa configuration
     (`GET /api/worker/desired`), l'applique, puis emet son `POST /api/worker/ack`. L'ancienne implementation ne
     renouvelait le bail DB qu'a l'ACK reussi ou au bump desired ; l'ACK etait donc rejete avec `410 WORKER_EXPIRED`,
     forçant le worker en `failClosed` et provoquant un flap continu toutes les 10s necessitant une re-emission manuelle.
   - **Auto-guerison centrale** : sur pull desired authentifie par mTLS, si le node est usable (`enabled=TRUE`,
     `authorized=TRUE`, `revoked=FALSE`) et que le bail DB est expire ou NULL, la centrale regrante atomiquement
     `config_expires_at = now() + ttl` (avec audit `WORKER_LEASE_RENEWED`, actor `worker:<san>`) AVANT de repondre 200.
   - **Regime sain preserve** : en regime nominal (bail valide), le pull met uniquement a jour `last_seen_at` sans
     modifier `config_expires_at` ni emettre d'audit (l'ACK reste le renouvelleur de bail nominal, sans amplification
     d'ecriture ni race condition).
    - **Defense en profondeur** : la garde `410 WORKER_EXPIRED` reste active dans `recordWorkerAck` et le worker
      garde son comportement fail-closed strict (403/410 -> failClosed). La convergence post-redemarrage est immediate
      sans aucune intervention ops requise.

5. **Annonce des cartes cluster sur workers relais sans connecteur local** :
   - **Contexte & cause racine** : sur un worker relais pur (connecteurs locaux absents, routage ECM délégué au cluster/UDP),
     l'authentification client CCcam réussissait (`AUTH_OK`) mais la session était fermée ou inactive (0 carte annoncée).
     En effet, `WorkerRuntime.seedDiscovery` publiait les capacités via `publishClusterCapability`, mais n'enregistrait des sources
     que via `desired.connectors()` (liste vide sur un worker relais). `CardDiscoveryService.getInitialAnnouncements` exigeant au moins
     une source active (`maxReshare >= 0` et `minHop < 999`), aucune capacité n'était jugée éligible pour les annonces `MSG_NEW_CARD`.
   - **Source synthétique cluster** : dans `WorkerRuntime.seedDiscovery`, pour chaque capacité publiée du catalogue, une source
     synthétique nommée `'cluster'` est automatiquement enregistrée :
     `discovery.onConnectorConnected("cluster", desired.desiredVersion())`
     `discovery.onCardDiscovered("cluster", cap.profile(), cap.caid(), cap.provider(), cap.cardId(), 1, 1, CardSource.Trust.MANUAL, 10, null, desired.desiredVersion())`.
   - **Génération & retrait de capacité au re-apply** : les sources synthétiques portent la `desiredVersion` active. Au re-apply d'un
     desired avec version incrémentée, `activeGenerations.get("cluster")` passe à la nouvelle version, invalidant immédiatement les
     sources des capacités retirées du catalogue sans nécessiter de purge manuelle.
   - **Contrôle d'accès & ACLs** : si un utilisateur possède une ACL `allowedConnectors` restrictive ne contenant pas `'cluster'`,
     les cartes cluster sont naturellement exclues pour cet utilisateur (0 carte annoncée), garantissant la cohérence ACL.
    - **Garde anti-collision** : si un connecteur réel nommé `'cluster'` est configuré dans `desired.connectors()`, la création de la
      source synthétique est ignorée au profit du connecteur réel.

## 14. Routage ECM relay CSPv2 côté centrale & sémantique NOT_FOUND worker

1. **Contexte & cause racine (asymétrie manual / wildcard)** :
   - Lorsqu'un worker consultait la centrale via CSPv2 (`requestFromPeer`), `CspV2EcmHandler` résolvait la requête via
     `CwsConnectorManager.sendEcmRequest(request, provider, discoveryService)`.
   - Ce chemin utilisait par défaut le mode `cardMode = "manual"`, lequel exige `cap.state == PUBLISHED` en mémoire.
   - Or, sur la centrale, l'état `PUBLISHED` n'existe qu'en base de données : `publishClusterCapability` (seul mutateur mémoire vers `PUBLISHED`)
     n'est appelé que par `WorkerRuntime.seedDiscovery` sur les workers. Les capacités mémoire de la centrale sont toutes à l'état `DISCOVERED`
     (émis par `onCardDiscovered`).
   - Alors que les listeners clients directs de la centrale opèrent en `cardMode = "wildcard"` (acceptant l'état `DISCOVERED`), le handler
     CSPv2 en `manual` produisait une sélection de candidats systématiquement vide, répondant `NOT_FOUND` wire.
   - Côté worker, `WorkerCspV2Bridge.consultNode` traitait indifféremment tout status non-OK comme une corruption de trame
     (`invalid node reply`), masquant la sémantique de miss légitime.

2. **Routage par candidats CAID/provider & sémantique relais wildcard** :
   - **Dérivation du provider** : le wire CSPv2 ne transporte pas le provider ; la centrale dérive le provider ident du corps ECM
     via `EcmProviderExtractor` (Viaccess 0x0500 via scan TLV nanos 0x90/0xD2, Seca 0x0100 via header ident, Cryptoworks 0x0D00 via 0x83/0xDF ;
     fallback `0` si indéterminable).
   - **Sémantique relais (`wildcard`)** : `CspV2EcmHandler` invoque `CwsConnectorManager.sendEcmRequest(..., "wildcard")`. La centrale
     sert ce que ses connecteurs portent sans exiger de publication mémoire `PUBLISHED`.
   - **Sécurité et exclusion stricte de `BLOCKED`** : `selectCandidatesFromDiscovery` et `selectCandidates` excluent strictement
     les capacités à l'état `BLOCKED` quel que soit le `cardMode`. Aucun fallback aveugle ne court-circuite le `CardDiscoveryService`
     lorsque celui-ci gère le CAID demandé ou lorsqu'une capacité est bloquée.
   - **Dispatch ordonné** : `CwsConnectorManager.dispatchToCandidates` interroge les candidats séquentiellement jusqu'à obtention d'une CW
     valide (16 octets + checksum DCW), borné par le `maxWaitMs` de la requête.
   - **Invariants préservés** : lookup cache L1 préalable, fail-closed `NOT_FOUND` sur vrai miss, règle 1-hop CSPv2 intacte
     (aucun re-export distant pour les requêtes d'origine CSPv2, dispatch local seul).

3. **Sémantique de validation côté worker (`WorkerCspV2Bridge`)** :
   - Une réponse `NOT_FOUND` reçue avec champs concordants (`commandTag`, `serviceId`, `networkId`, `caId`, `dataHash`) échoue
     désormais de manière distincte avec le message `'node reply not found'`.
   - Une réponse corrompue, un mismatch de champs d'identification ou un checksum CW invalide continue d'échouer avec `'invalid node reply'`.
   - Comportement fail-closed et cycle de vie inchangés (pas d'injection CW ni d'invalidation parasite).
