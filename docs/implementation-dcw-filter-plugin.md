# Guide d'implémentation : Filtre DCW Avancé (DcwFilterPlugin)

> **Statut : IMPLÉMENTÉ**. Cette fonctionnalité est entièrement livrée dans les modules `csp-filters` et `csp-app` (fusionnée dans `main`).

## 1. Comportement Historique de Référence et Livré
D'après `plugins/DcwFilterPlugin/src/com/bowman/cardserv/DcwFilterPlugin.java` (module legacy historique) et son implémentation autonome dans `csp-ng` :
- **Règles de base** : Longueur 16, validité checksum (`DcwValidator.checksumDcw`). Rejet via liste de digests SHA-256 configurée (`badDcws` / `badDcwDigests`). Le tout-zéro est systématiquement inclus dans la bad-list. `zeroCounting` vérifie `DcwValidator.hasFiveZeroes`.
- **`forceContinuity`** : Surveille l'alternance du `commandTag` (0x80/0x81) dans une fenêtre de 12s. Conservation temporaire des 16 octets bruts en mémoire avec TTL strict de 12s. Propose le mode `COMPATIBILITY` (`data[0]+data[1]+data[3]==0 || data[9]+data[10]+data[11]==0`) et le mode `STRICT` (`DcwValidator.hasZeroDcw`).
- **`verifyReplies`** : Compare les réponses de connecteurs distincts dans une fenêtre cible de 20s. Exige le fan-out `requiresMultipleReplies()` au niveau du manager. Retourne `FilterDecision.Hold` ou `FilterDecision.ReleasePrevious`.
- **`detectLinks`** : Détecte dynamiquement les services TV distincts débloquant le même CW (invalidation 2s, fenêtre de strike 20s, retrait du groupe au second strike). S'appuie sur `ServiceDirectory` pour restreindre la détection aux services TV du même profil.

## 2. Point d'intégration (Flux de réponse Connecteur)
Le filtrage est câblé sur la réponse de routage connecteur dans `CwsConnectorManager.setFilterChain` avant la complétion de la Future de résolution.
- **Décisions du filtre** (`FilterDecision`) :
  - `Accept(reply)` : Transmet la réponse au client et au cache.
  - `Drop` : Ignore le paquet silencieusement (ne modifie pas le registre pending des abonnés).
  - `Rewrite(newCw)` : Remplace la réponse (ex. continuité forcée).
  - `Hold` : Retient temporairement la réponse en mémoire.
  - `ReleasePrevious(reply, connectorRef)` : Libère une réponse en attente avec réinjection asynchrone bypassant la chaîne pour éviter les boucles.
- **Sécurité anti-CW-vide** : `FilterChain` convertit automatiquement toute décision `Accept`, `Rewrite` ou `ReleasePrevious` portant un CW vide en `Drop` fail-closed.

## 3. Architecture et Composants Livrés

### 3.1. Module d'Abstractions et Filtres (`csp-filters/src/main/java/com/bowman/cspng/filters/`)
- `CwFilter.java` (l.19) : SPI de filtre CW/DCW définissant `evaluateReply(context, reply)` et `requiresMultipleReplies()`.
- `FilterDecision.java` (l.26) : Interface scellée représentant les 5 décisions possibles (`Accept`, `Drop`, `Hold`, `Rewrite`, `ReleasePrevious`).
- `FilterChain.java` (l.35) : Chaîne ordonnée synchrone évaluant les filtres et appliquant la garde anti-CW-vide.
- `FilterContext.java` (l.39) : Contexte d'évaluation immuable fournissant `ReplyRelease` (canal de libération asynchrone) et `FilterScheduler` (abstraction de timers sans dépendance directe à Vert.x).
- `DcwFilter.java` (l.21) : Moteur de filtrage stateful single-writer sans lock ni `synchronized`, implémentant les 4 règles (bad-list, forceContinuity, verifyReplies, detectLinks), le mode shadow (`shadowMode`), et la gestion des snapshots/restaurations de liens.
- `DcwFilterConfig.java` (l.24) : Record de configuration immuable avec support des modes `COMPATIBILITY` et `STRICT`.
- `DcwFilterStats.java` (l.9) : Snapshot immuable des compteurs de métriques de filtrage.
- `ServiceDirectory.java` (l.11) : SPI d'annuaire de services filtrant les types de services TV legacy (`1, 2, 17, 25, 211`).
- `LinkStore.java` (l.22) & `VersionedJsonLinkStore.java` (l.56) : SPI et implémentation Vert.x de persistance JSON v1 (`version: 1`, `links: [...]`). Garantit des écritures atomiques (fichier temporaire + `setAtomicMove(true)`), un fallback et une quarantaine automatique (`.corrupt-<uuid>`) en cas de corruption.

### 3.2. Module Runtime Applicatif (`csp-app/src/main/java/com/bowman/cspng/app/runtime/`)
- `DcwFilterRuntime.java` (l.46) : Composant CDI `@ApplicationScoped` assurant le câblage et le cycle de vie du filtre DCW :
  - **Ordre CDI** : Startup à `@Priority(225)` (entre `RuntimeCore` à 200 et `ConnectorLifecycleManager` à 250), shutdown à `@Priority(175)`.
  - **Gestion de la persistance** : Debounce auto-save configurable (`csp.dcw.save-debounce-ms`, défaut 1000ms), fichier cible (`csp.dcw.links-file`, défaut `/var/lib/csp/links.json`), et flush synchrone/asynchrone à l'arrêt.
  - **Reload à chaud** : Écoute du bus d'événements Vert.x (`ConfigWatcher.CONFIG_RELOADED_ADDRESS`), rechargement dynamique de la configuration et fusion des groupes de liens.

## 4. Tests et Validation
- **Couverture de tests unitaires et d'intégration** :
  - `DcwFilterTest.java` : Tests unitaires complets des règles du filtre (bad-list, checksum, forceContinuity, verifyReplies, detectLinks, shadowMode).
  - `DcwFilterRuntimeTest.java` : Validation du cycle de vie CDI, de l'auto-save debounce, du rechargement de configuration et du flush à l'arrêt.

## 5. Limites Connues
1. **Format de Persistance** : Le format Java Serialization legacy `links.dat` a été supprimé au profit du format structuré `VersionedJsonLinkStore` (JSON v1 atomique avec quarantaine fail-safe).
2. **Champ d'Application `detectLinks`** : La détection de liens s'effectue par profil et est strictement restreinte aux services de type TV (types `1, 2, 17, 25, 211`).
