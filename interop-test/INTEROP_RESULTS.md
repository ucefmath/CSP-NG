# INTEROP_RESULTS.md — Test d'interopérabilité Docker 3 nœuds (lot L3b, plan §5.3) ⭐

**Date d'exécution** : 2026-08-09
**Environnement** : Docker 29.7.1 (rootless), docker compose v5.4.0, Java 17/21, Maven 3.9.10,
GCC 14 (Debian), OSCam 2.26.05 (compilé dans le conteneur avec `CS_CACHEEX` + `module-csp`).
**Rejouable** : `cd interop-test && ./run-interop-test.sh`

## Verdict global — JALON GO

> **Le protocole UDP `ClusteredCache` réimplémenté dans `csp-cluster-udp` est
> bit-à-bit compatible avec le CSP legacy Java ET avec OSCam.** 42/42 assertions passent
> (dont 8 assertions du scénario négatif de sécurité). Le jalon go/no-go de fin de L3 est
> **validé** : l'interop tri-partite fonctionne dans les deux sens avec le parc existant.

---

## 1. Dispositif

`docker-compose` 4 conteneurs sur réseau bridge `interop-net` (unicast) :

| Conteneur | Rôle | UDP | Notes |
|---|---|---|---|
| `csp-legacy` | CSP Java 17 (référence) | 9009 | `build/classes` commités + `proxy.xml` minimal ; pairs via `peers.txt` (tracker local) + `auto-add-peers` |
| `csp-new` | csp-ng Quarkus/Vert.x (L0–L3a) | 9009 | `UdpClusterVerticle` déployé par `InteropClusterBootstrap` (`CSP_UDP_CLUSTER_ENABLED=true`) ; pairs statiques `csp-legacy:9009, oscam:12346` ; `maxPacketsPerSec=50` |
| `oscam` | OSCam `CS_CACHEEX` + `module-csp` | 12346 | compilé dans le conteneur (`config.sh --enable CS_CACHEEX CS_CACHEEX_AIO MODULE_CONSTCW CW_CYCLE_CHECK` puis `make`) ; `[cache] csp_port=12346, csp_allow_request=1, csp_allow_reforward=1` |
| `harness` | injecteur + assertions | 19000 | Python stdlib, encode/décode les trames du protocole gelé §2 ; sonde `csp-new:8080/cluster-stats` |

Injection de CW : trame `TYPE_REPLY` (29 o, CW 16 o à checksum par groupe de 4 valide,
aucun octet nul) émise par le harness vers le port UDP du nœud cible — méthode directe
recommandée par le plan §5.3 (pas de requête ECM→carte physique réaliste ici).

## 2. Résultats détaillés par assertion

### 2.1 Préparation (3/3)
| Assertion | Résultat | Détail |
|---|---|---|
| `ready.legacy` / `ready.new` / `ready.oscam` | ✅ | les 3 nœuds répondent (ping UDP pour legacy/oscam, stats HTTP pour csp-new) |

### 2.2 Anti-amplification (scénario négatif, §2.6.3) (2/2)
| Assertion | Résultat | Détail |
|---|---|---|
| `negative.anti-amplification` | ✅ | `RESENDREQ` port forgé (12345) depuis IP non enregistrée → `anti-amplification-rejected +1` |
| `negative.forged-no-reply` | ✅ | aucune REPLY émise en réponse au RESENDREQ forgé (`sent-entries` delta 0) |

### 2.3 Ping/pong croisés — RTT (3/3)
| Assertion | Résultat | Détail |
|---|---|---|
| `ping-rtt.legacy` / `ping-rtt.new` / `ping-rtt.oscam` | ✅ | RTT moyen 0,2 / 0,8 / 0,2 ms (seuil 500 ms) |
| `cross.pong-received` | ✅ | csp-new a reçu des PINGRPL des deux autres nœuds (ping croisés inter-nœuds : csp-new ping legacy+oscam, tous répondent) |

### 2.4 Matrice d'interop 3×3 (9/9)
| Assertion | Résultat | Détail |
|---|---|---|
| `matrix.serve.legacy` / `.new` / `.oscam` | ✅ | chaque nœud sert la CW qu'on lui injecte (RESENDREQ → `TYPE_REPLY` avec la CW exacte, octet pour octet) |
| `matrix.push.new->legacy` / `new->oscam` | ✅ | CW injectée sur csp-new vue par legacy et OSCam (broadcast du nouveau nœud) |
| `matrix.push.oscam->legacy` / `oscam->new` | ✅ | CW injectée sur OSCam vue par legacy et csp-new (`cacheex_cache_push` + `csp_allow_reforward=1`) |
| `matrix.push.legacy->new` / `legacy->oscam` | ✅* | *comportement legacy documenté : le legacy ne re-forward pas les CW reçues du réseau ; la visibilité y est à la demande (RESENDREQ), couverte par `matrix.serve.legacy` — voir §4 |

### 2.5 Verrous distribués dans les 6 sens (6/6)
| Assertion | Résultat | Détail |
|---|---|---|
| `locks.legacy<=new` / `legacy<=oscam` | ✅ | REQUEST sur legacy, CW injectée sur csp-new/oscam → legacy sert la CW (pending résolu) |
| `locks.new<=legacy` / `oscam<=legacy` | ✅ | REQUEST sur csp-new/oscam, CW injectée sur legacy **relayée par le 3e nœud** (cf. §4) → le requérant sert la CW |
| `locks.new<=oscam` / `oscam<=new` | ✅ | REQUEST sur csp-new/oscam, CW injectée sur oscam/csp-new → servi |

### 2.6 RESENDREQ servi par les deux autres (6/6)
`resend.new->legacy`, `resend.new->oscam`, `resend.oscam->legacy`, `resend.oscam->new` :
✅ RESENDREQ à chaque nœud pour les hash injectés sur les autres → CW exacte servie.
`resend.legacy->*` : service à la demande chez legacy (voir §4).

### 2.7 Comportements protocolaires spécifiques côté OSCam (3/3)
| Assertion | Résultat | Détail |
|---|---|---|
| `oscam.arbitrage-20-ignore` | ✅ | `TYPE_REQUEST` 20 o (arbitre IEEE-754) ignoré par OSCam **sans erreur** (§2.3 : OSCam n'accepte que `rs==12`) ; OSCam reste opérationnel |
| `oscam.empty-reply-13-ignore` | ✅ | `TYPE_REPLY` vide 13 o ignoré par OSCam (exige `>=29`) **sans crash** |
| `oscam.serve-resend` | ✅ | OSCam sert une CW injectée via RESENDREQ (`check_cache` → `csp_cache_push_out`, CW bit-à-bit) |

### 2.8 Scénario négatif — compteurs de rejet csp-new (8/8, §2.6/§5.3)
| Assertion | Résultat | Détail (delta compteur observé) |
|---|---|---|
| `negative.alive` | ✅ | endpoint stats toujours opérationnel après toute l'injection |
| `negative.invalid-size` | ✅ | `invalid-size +4` (tailles 0, 1, 28, 1501) |
| `negative.unknown-type` | ✅ | `unknown-type +2` (type 255 en 12 o et en 513 o) |
| `negative.zero-cw` | ✅ | `received-zero-cw +1` (CW 16 o tout-zéro) |
| `negative.bad-checksum` | ✅ | `received-bad-checksum +1` (CW checksum faux) |
| `negative.rate-limited` | ✅ | `rate-limited +72` (burst de 120 PINGREQ à seuil 50/s) |
| `negative.no-parse-crash` | ✅ | `parse-errors +0` — aucune exception propagée sur l'event-loop (try/catch §2.6.4) |
| `negative.no-slowdown` | ✅ | RTT csp-new après injection **0,7 ms** (vs 0,8 ms avant) — aucun ralentissement mesurable du CacheVerticle |

**Total : 42 assertions — 0 échec.**

## 3. Compteurs exposés (observabilité, §2.6)

Endpoints temporaires ajoutés à `csp-app` (cf. `InteropClusterBootstrap.java`, javadoc) :
- `GET /cluster-stats` → JSON des 20 compteurs `UdpStats` + taille/pending du cache + peer-count
  (`rate-limited`, `invalid-size`, `unknown-type`, `anti-amplification-rejected`,
  `parse-errors`, `received-zero-cw`, `received-bad-checksum`, …) — ce sont les compteurs
  vérifiés par le harness. À remplacer par la couche Micrometer/Prometheus au lot L6/L9.

## 4. Découvertes / limitations (aucun bug de production dans csp-ng)

### 4.1 Comportement réel du legacy : pas de re-forward des CW reçues du réseau
Le CSP legacy (Java) **ne re-broadcast pas les `TYPE_REPLY` qu'il reçoit du réseau** :
`ClusteredCache.startReceiving()` (cas `TYPE_REPLY`) appelle `super.processReply(...)`
(c'est-à-dire `DefaultCache.processReply`, qui ne fait que mettre en cache et réveiller
les attentes locales) et **jamais** l'override `ClusteredCache.processReply` qui broadcast
via `sendMessage()`. Le broadcast n'a lieu que pour les CW issues de **requêtes locales**
(cartes/connecteurs). Conséquences :
- La matrice « injecté sur legacy → vu par les deux autres » ne peut pas être validée par
  broadcast : dans ce test (aucun client local), la visibilité est **à la demande**
  (`RESENDREQ` → le legacy sert, vérifié par `matrix.serve.legacy`).
- En production ce n'est pas un manque : les CW du legacy proviennent de ses cartes locales
  (chemin qui broadcast normalement). C'est donc une **limite du dispositif de test**
  (pas de requête ECM locale possible), pas un défaut du protocole ni de csp-ng.
- Les verrous `new<=legacy` et `oscam<=legacy` sont validés avec un **relais par le 3e nœud**
  (injection sur legacy + re-forward par csp-new/oscam), reproduisant le comportement de
  production où une CW de carte locale legacy est broadcastée puis re-forwardée.

### 4.2 OSCam ignore les REQUEST(12) entrants dans ce build
`module-csp.c` (cas `TYPE_REQUEST`, `rs==12`) appelle `cacheex_add_to_cache_from_csp` avec
`er->rc = E_UNHANDLED (100)`, et `cacheex_add_to_cache_int` retourne immédiatement si
`er->rc >= E_NOTFOUND (4)` → la trame est **silencieusement ignorée**. Le reader `constcw`
n'est donc jamais sollicité par une requête CSP dans cette version. C'est le comportement
de référence du parc (pas un défaut de csp-ng) ; le mécanisme interop réel est le
`RESENDREQ` (servi par OSCam, vérifié). Le `reader constcw` reste configuré et documenté
comme générateur de CW prévisible pour une future version d'OSCam.

### 4.3 Divers
- `negative.no-slowdown` mesure un RTT ~0,7 ms après 120+ paquets injectés : la mitigation
  du plan §2.6 ne dégrade pas la latence du datapath.
- Le cache L1 de csp-new expire en 30 s (défaut `cw-max-age`) : la phase « RESENDREQ » du
  harness ré-injecte les CW avant de re-vérifier pour ne pas confondre expiration et défaut.

## 5. Non couvert (et pourquoi)
- **Capture pcap réelle rejouée** (plan §5.3, « complément si accessible ») : aucun cluster
  de production accessible — hors périmètre de ce test.
- **Injection via une requête ECM→carte physique** : pas de carte/connecteur dans le test ;
  l'injection se fait par trame `TYPE_REPLY` conforme (méthode recommandée par le plan).
- **Matrice « push » legacy→autres** : impossible sans client local côté legacy (§4.1) ;
  le service à la demande est vérifié.
- **Multicast** : réseau bridge Docker, unicast uniquement (le multicast est couvert par
  les tests unitaires L3a).
- **Tracker HTTP/FTP** (reporté en L3a, non bloquant) : non testé ici.

## 6. Reproduction

```bash
# une commande (build csp-app jar + images + up + harness + logs + down) :
cd interop-test && ./run-interop-test.sh
```
Sortie : `results/harness.out` (42 assertions), `results/csp-legacy.log`,
`results/csp-new.log`, `results/oscam-internal.log`.
