# Qualité des pairs — contrats et limites (lots 1-5)

> Aucune copie MultiCS, aucun pattern/entropy/48bits. Aucun log de CW ni de secret
> (digests et compteurs uniquement).

Références officielles lues avant implémentation : Quarkus
« Native reference guide » (enregistrement reflection explicite pour Jackson
en natif — cf. `reflect-config.json`) et Quarkus « Using Flyway »
(migrations versionnées `V__*.sql` strictement additives — cf. `V16`).

## Lot 1 — Ingress UDP aligné `DcwQualityPolicy`

- `UdpLegacyTransport.handleIncomingReply` :
  - reply vide protocolaire (miss distant) : comptée `received-discarded` et
    transmise telle quelle (protocole préservé) ;
  - chemin **worker** (`UdpWorkerHooks` non null) : **aucune pré-validation**
    dans le transport — seul le hook connaît le pending (profil/provider/SID)
    et valide avec ce contexte (`WorkerUdpExchange.feedRemote`) ;
  - chemin **central** : validation contextuelle **avant** `handleReply`
    (profil `null`, CAID de la clé, provider **inconnu**, SID de la clé,
    sourceId = endpoint vérifié `udp:<host>:<port>`, garde
    `CHECKSUM_REQUIRED`). Invalide ⇒ compteur `received-rejected-dcw`,
    **aucune injection au cache**.
- Checksum d'observation corrigé : 4 triplets DVB complets
  (`DcwQualityPolicy.validateChecksumFull`) au lieu du premier triplet seul.
- Préservés : checksum scopé (règle la plus spécifique), demi-CW tolérée par
  défaut (`allowZeroHalf`, `REJECT_VALID` sur moitié active), parité `0`
  OSCam injectée telle quelle (jamais convertie en `0x80`), provider inconnu
  sans relaxation (`REQUIRE_VALID` forcé).
- Connecteurs/sessions CacheEx : validation policy avec le contexte réel
  (profil/CAID/provider/SID, sourceId = nom du connecteur = transport TCP
  vérifié, ou `L<id>:<user>` = session authentifiée). Parité validée avant
  la gate (R2). Mémorisation amont (`noteUpstreamPath`) effectuée uniquement
  après acceptation de la gate pour éviter la pollution en cas de rejet.
  Sans porte câblée (tests directs) : garde historique `DcwValidator` inchangée.
- Alignement assumé : avec la policy par défaut, l'heuristique 5–7 zéros et
  la demi-CW nulle ne rejettent plus (défauts `zeroCountingEnabled=false`,
  `allowZeroHalf=true` déjà actés par la feature DCW). Restauration possible
  via `zeroCountingEnabled=true` / `allowZeroHalf=false`.

## Lot 2 — Qualité fenêtrée (`PeerQualityTracker`)

- **Structure à buckets temporels circulaires (B4)** : ring buffer de 720
  buckets de 5 s couvrant une rétention plafond d'1 heure (3600 s / 5 s).
  Mémoire strictement constante : 3 tableaux `long[720]` par pair (~17 KB),
  soit &lt; 9 MB total pour 512 pairs au lieu des ~200 MB d'un modèle à
  événements individuels.
- **Granularité temporelle** : la fenêtre glissante s'évalue à la granularité
  du bucket (5 s, approximation bornée à +/- 5 s, jamais de scan linéaire
  d'événements).
- **Chemin chaud O(1)** : aucun objet alloué sur `recordAccepted` /
  `recordInvalid` ; supporte les seuils `minSamples` jusqu'à 10 000 sans
  dérive mémoire.
- **Concurrence sûre (B3)** : éviction des pairs surnuméraires réalisée hors
  `computeIfAbsent` (aucun risque de `Recursive update` ou CME).
- Population **éligible = accepted + invalid** uniquement. Les drops de
  routage/protocole (`loop`, `hop`, `queue`, `flood`, `filtered`,
  `quarantine`, `quorum`, cw-cycle, pas de cache routable) sont **exclus**
  (non imputables au contenu).
- Identités : noms de connecteurs (TCP vérifié), `L<id>:<user>` (sessions
  authentifiées), `udp:<host>:<port>` (pairs allowlistés worker uniquement).
  Les pairs UDP dynamiques centraux (usurpables) sont **exclus** de la
  quarantaine.

## Lot 3 — Hits servis (`CacheHitAttribution`, `CacheHitSink`)

- Compté **exactement une fois** par réponse servie à un vrai demandeur :
  hit instantané servi + waiter réveillé non vide (`CacheVerticle`), hit
  lookup servi (`StrictEcmRouter`). Probes internes (`peekLocalCache`,
  lookups sans hit, miss, rejets) **jamais** comptés.
- Origine `null`/vide = `unknown` **explicite**. Aucune confusion avec le
  volume (`received/pushed` de `CacheExStatsRegistry`, inchangé : reçus
  acceptés, pas volume brut).
- Borné : 512 origines, éviction sûre hors `computeIfAbsent` (B3).

## Lot 4 — Quarantaine (`PeerQuarantine`, `PeerQuarantineConfig`, V16)

- Opt-in : `enabled=false` par défaut, mode `observe` par défaut (compté,
  jamais exclu). `enforce` : exclusion **temporaire du routage** uniquement.
- Seuil : `minSamples` échantillons éligibles (défaut 50, jusqu'à 10 000) +
  ratio invalide `>= invalidRatioBps` (défaut 5000 bps) sur `windowMs`, sinon
  aucune décision.
- État **mémoire borné** : borne live `cfg.maxPeers()` respectée (plafond de
  sécurité 1024), `durationMs` défaut 10 min : expiration automatique +
  levée admin `POST /api/admin/peer-quality/quarantine/release`.
  **Jamais de bannissement**, jamais sur source UDP seule.
- Config persistée V16 (`csp_peer_quarantine_config`, triggers NOTIFY,
  audit, bump desired pour les modifications de config ⇒ relayée aux workers
  via `PeerQuarantineView` + `reflect-config.json`).
- Levée de quarantaine (R6 / B2 / B3) :
  - La levée manuelle s'applique **uniquement au nœud central**.
  - Si le pair n'est pas dans la quarantaine active centrale, aucun audit n'est écrit (réponse 200 `released=false`).
  - Si le pair est actif, l'audit persistant en BD (`QUARANTINE_RELEASED`) est exécuté AVANT la mutation mémoire.
  - En cas d'échec BD, la requête échoue en HTTP 500 (`AUDIT_FAILED`) et l'état mémoire reste intact (`shouldSkip` reste `true`).
  - Les entrées de quarantaine sur les workers expirent **automatiquement en mémoire**
    (aucun faux relai ni promesse de levée distante synchrone).
  - Tentative de levée ciblant un worker explicite → statut HTTP 422.

## Lot 5 — Quorum (`CacheExQuorumTracker`, `CacheExQuorumConfig`, V16)

- **Comparaison exacte** (octet-à-octet) de CW **déjà validées** — aucune
  réparation (`tryRepair`/`match12` interdits et absents).
- **Double exigence de corroboration (B1)** : pour atteindre le quorum,
  un candidat CW doit être corroboré par au moins {@code minOrigins}
  **pairs authentifiés distincts** (`peerId`) **ET** au moins {@code minOrigins}
  **sources originaires distinctes** (`upstreamOrigin` dérivé du dernier `node_id`
  dans `push.nodeIds()` selon l'ordre protocolaire OSCam, ou fallback transport).
  Un pair unique forgeant plusieurs `node_ids` ne peut pas fabriquer de quorum.
  Deux pairs relayant la même source originaire ne comptent que pour 1 origine.
- **Un pair = un vote par clé ECM (R1)** : un pair ne peut voter que pour
  un seul candidat à la fois. Changer de CW retire son vote antérieur.
- **Anti-starvation multi-candidats (R1)** : conserve jusqu'à 4 candidats CW
  distincts par clé ECM. Les candidats non corroborés sont évincés au profit
  de nouvelles corroborations si les slots sont saturés.
- **Concurrence fine sans verrou global (R3)** : synchronisation par entrée
  `QuorumEntry`, scans de snapshots non bloquants.
- Identité : profil/CAID/provider/SID/**parité exacte** (`0` distinct de
  `0x80`/`0x81`)/hash. Clé sans corps ECM (limite documentée des push).
  `verifyReplies` de `DcwFilter` **inchangé**.
- Modes :
  - `off` (défaut, bypass total) ;
  - `observe` (B2) : toute CW valide est acceptée et injectée, opportunités et
    divergences enregistrées dans les métriques sans altérer le routage ;
  - `enforce` : `PENDING` ⇒ pas d'injection, `QUORUM` ⇒ injection,
    `MISMATCH`/`EXPIRED` ⇒ rejet explicite `REASON_QUORUM`, **jamais de repli
    silencieux**.
- Expiration live (R4) : balayage périodique actif dans `RuntimeCore` et
  `WorkerMain` sur fenêtre live. Les snapshots n'exposent jamais de clés expirées.
- Raison de drop dédiée `quorum` (distincte d'`invalid`) dans
  `CacheExStatsRegistry`.

## Relai workers & ingestion centrale (B5)

- `WorkerStatsView` étendu avec les vues bornées (64 max) :
  `peerQuality`, `cacheHits`, `quarantine`, `quorum`.
- `WorkerRuntime.collectStats()` sérialise ces métriques réelles (enregistrées
  dans `reflect-config.json` pour la compilation native GraalVM).
- Ingestion centrale (`AdminPeerQualityResource`) : expose un champ `nodeId`
  explicite (`"central"` ou `<node>`) sans ambiguïté.
- Sécurité web : sessions protégées par cookie `SameSite`/`HttpOnly`, header CSRF
  `X-CSP-Requested-With: CSP-WebUI` contrôlé sur le login (`AdminBrowserSecurityFilter`)
  et émis sur toutes les mutations frontend.
- Compatibilité descendante garantie avec les anciens payloads JSON.

## UI (pages existantes, pas de console séparée)

- `CacheEx` : cause `quarantine` et `quorum` affichées ; sections qualité
  fenêtrée, hits servis par origine, quarantaine (config + levée centrale avec
  bouton désactivé sur lignes worker), quorum (config + compteurs + clés actives).
- `Dcw` observabilité : lien vers la page CacheEx.

## Fichiers et tests

- Nouveaux composants :
  - `PeerQualityTracker(+Test)` (ring buffer 720 buckets B4, éviction B3)
  - `CacheHitAttribution(+Test)` (éviction B3)
  - `PeerQuarantine(+Test)`
  - `CacheExQuorumTracker(+Test)` (double corroboration B1, un-pair-un-vote R1, multi-candidats R1)
  - `CacheExIngressGate(+Test)` (gate intégrée + provenance B1 + observe B2)
  - `PeerQuarantineConfig`, `CacheExQuorumConfig`
  - `V16__peer_quality_quorum.sql`, `PeerQualityQuorumPgTest`
  - `AdminPeerQualityResource(+Test)` + DTOs
  - `CacheHitSink`, `UdpIngressPolicyTest`, `peer-quality.test.ts`,
    `peer-quality.spec.ts` (E2E Playwright, 6 scénarios dont a11y, 409 et worker disabled).
- Modifiés :
  - `UdpLegacyTransport`, `UdpStats`, `UdpClusterVerticle`
  - `CcCamConnector`, `Camd35TcpConnector`, `CcCamSession`, `Camd35TcpSession`
  - `CacheExStatsRegistry`, `StrictEcmRouter`, `CacheVerticle`
  - `WorkerUdpExchange`, `WorkerRuntime`, `WorkerState`, `WorkerMain`
  - `RuntimeCore`, `ConnectorLifecycleManager`, `ListenerManager`,
    `InteropClusterBootstrap`
  - `ConfigSnapshot`, `ConfigRepository`, `RelayWorkerContract`,
    `WorkerDesiredBuilder`
  - `reflect-config.json`, `WorkerRelayContractReflectTest`
  - `AdminCacheexResource`, `CacheEx.tsx`, `Dcw.tsx`, `types.ts`,
    `fixtures.ts`.
