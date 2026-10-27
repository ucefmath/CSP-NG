# Architecture de CardServProxy NG (csp-ng)

CardServProxy NG est un proxy de card-sharing moderne, performant et réactif, construit sur **Java 21, Quarkus 3.20 et Vert.x**. Contrairement au projet legacy (Java 17 / RMI) qui reposait sur des I/O bloquantes et un verrouillage global, `csp-ng` s'appuie sur une architecture event-driven non-bloquante.

## Modèle d'exécution (Threading & Event-Loop)

- **Event-Loop Vert.x** : Le chemin chaud (datapath) pour les sessions clientes, l'interrogation du cache et les connecteurs vers les cartes s'exécute intégralement sur l'event-loop Vert.x. Il n'y a **aucune** E/S bloquante et aucune synchronisation (`synchronized`, `wait`/`notify`) sur ce chemin.
- **CacheVerticle (Single-Writer)** : Pour éviter la contention sur le cache de clés (CWs), le `CacheVerticle` opère en tant que single-writer, gérant de manière asynchrone les promesses (promises) des clients en attente de CW.
- **Îlots Bloquants Isolés** : Les opérations inévitablement bloquantes (ex. traitement de bases de données) sont exécutées sur des *worker threads* spécifiques via `@Blocking` ou `executeBlocking`, garantissant qu'elles ne saturent jamais l'event-loop principal.

## Caching

L'architecture de cache de `csp-ng` est structurée en plusieurs couches (tiers) :

1. **L1 (Cache Local - Caffeine)**
   Logé dans le `CacheVerticle`, ce cache ultra-rapide stocke les réponses CW récentes. Il évite le coût de re-solliciter le réseau ou les connecteurs pour un ECM déjà résolu. Géré par le verticle single-writer, il élimine les goulots d'étranglement dus à la concurrence.

2. **L2 (Cluster UDP Legacy / CCcam CacheEx)**
   Couche d'exigence de compatibilité. Elle permet de partager le cache avec le parc CSP existant et OSCam (via `module-csp` et `CS_CACHEEX`). Ce protocole UDP historique a été implémenté bit-à-bit dans le module `csp-cluster-udp` via `UdpLegacyTransport` pour garantir l'interopérabilité totale, intégrant des mitigations de sécurité essentielles (rate-limiting par IP, vérification stricte des trames).

3. **L3 (Infinispan Embedded - Optionnel)**
   Couche additionnelle désactivée par défaut, permettant un miroir CW (`REPL_ASYNC`) entre nœuds `csp-ng`.
   - L'implémentation agit comme un listener distant répliquant les CW vers le `CacheVerticle`.
   - L'interrogation (`onRequest`) est un no-op.
   - Le codec sérialise simplement : `Integer dataHash -> byte[]`.
   - La découverte multicast utilise UDP avec un keystore JCEKS partagé. Le protocole est sécurisé par `SYM_ENCRYPT`, dont la validité et la présence sont vérifiées au runtime au démarrage.

## Sessions et Connecteurs

- **Sessions (Entrantes)** : Les clients s'y connectent (ex. via Newcamd, Radegast). Elles traitent le flux réseau, la cryptographie (ex. DES pour Newcamd) de manière asynchrone, et envoient les ECMs au cache ou aux connecteurs.
- **Connecteurs (Sortants)** : Le manager gère les connexions aux serveurs distants.
- **Proxys SOCKS5 (Plan P2, V5)** : Les connecteurs sortants peuvent acheminer leurs connexions via des proxys SOCKS5 nommés (table `csp_proxy`, association optionnelle `csp_connector.proxy_name`, FK `ON DELETE RESTRICT`). DNS de la destination déléguée au proxy (ATYP domain — aucune résolution locale), secrets par références `env:`/`file:` uniquement, fail-closed strict (jamais de repli direct), kill switch par `enabled`, timeouts par phase (TCP + handshake SOCKS5, chacun borné). Détails complets : [`docs/PROXY.md`](PROXY.md).

## Configuration et Imports

- **Import CCcam** : Le parsing des configurations `cccam.cfg` est géré dans le module `csp-config` et exposé via des services et ressources dans `csp-app`.

## Utilisateurs, Authentification et PostgreSQL

La gestion des utilisateurs (historiquement gérée par des fichiers XML et un rechargement complet) repose désormais sur **PostgreSQL et Flyway** :

- **PostgreSQL Réactif** : Utilisé via le client réactif Quarkus (`quarkus-reactive-pg-client`). Les schémas sont versionnés et migrés automatiquement par Flyway au démarrage.
- **Authentification & Hachage** : L'accès à l'API d'administration et la création de nouveaux comptes utilisent le standard `bcrypt` (paramétrable via `csp.admin.bcrypt-cost=12`). La vérification MD5Crypt legacy est conservée pour assurer la compatibilité des mots de passe des utilisateurs historiques.

## Filtres CW/DCW

Un pipeline extensible (`CwFilter`, `DcwFilter`) permet d'intervenir sur le flux. Les validations de base (checksumDcw, hasFiveZeroes, hasZeroDcw, equalsSingleDcw) ainsi que le filtrage avancé (`DcwFilter` dans `csp-filters` : détection de liens `detectLinks`, vérification par connecteurs multiples, forçage de continuité `forceBadCw`, persistance JSON atomique) sont implémentés et intégrés au runtime.

## Interface Web / Administration (API REST & SSE)

- Le protocole RMI historique, source de vulnérabilités et déprécié en Java 21, est **entièrement supprimé**.
- Il est remplacé par une API REST/JSON et un flux d'événements temps réel SSE (`/api/admin/events`) pour l'administration. Le garde HTTPS et l'authentification (Basic Auth et Form/cookie) avec bcrypt sont présents. En production, le reverse-proxy frontal (ex: nginx sur 127.0.0.1) gère la terminaison TLS.

## Observabilité et Santé

- **Health Checks** : SmallRye Health expose `/q/health/live` (liveness du bean `RuntimeCore`) et `/q/health/ready` (readiness non-bloquante de la base de données PostgreSQL réactive).
- **Métriques** : Micrometer Prometheus expose `/q/metrics` pour le scraping de métriques JVM/HTTP.
- **Confinement réseau** : En production, Quarkus écoute uniquement sur le loopback (`127.0.0.1`), confinant mécaniquement `/q/health` et `/q/metrics` à l'accès local. Nginx ne doit pas exposer ces endpoints publiquement.

## Connecteurs inter-proxy

L'interopérabilité s'effectue via :
- UDP (`UdpLegacyTransport` dans `csp-cluster-udp`) pour la compatibilité avec le parc CSP existant et OSCam.
- TCP modernisé via le protocole **CSPv2** (`csp-cluster-tcp`), avec framing borné, multiplexage et support mTLS.

## Gestion des Secrets (Sécurité)

La politique de sécurité de `csp-ng` impose qu'aucun secret ne soit commité dans les fichiers de configuration (comme `proxy.xml` ou `application.properties`). Tous les mots de passe (PostgreSQL, certificats TLS, JCEKS `SYM_ENCRYPT` pour Infinispan) doivent être injectés via des **variables d'environnement** ou un gestionnaire de secrets sécurisé. L'API d'administration doit être protégée par authentification avec une couche TLS (minimum TLS 1.2, recommandé TLS 1.3).
