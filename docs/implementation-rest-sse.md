# Guide d'implémentation : REST / SSE

> **Statut : IMPLÉMENTÉ**. Cette fonctionnalité est entièrement livrée dans le module `csp-app` sous le package `com.bowman.cspng.app.admin` (fusionnée dans `main`).

## 1. État actuel vérifié
L'API REST d'administration (Quarkus REST / RESTEasy Reactive) est complète. Elle inclut l'authentification (Basic Auth + Cookie de session chiffré AES-256-GCM), l'imposition HTTPS en production, les ressources statiques d'administration, les endpoints de consultation de runtime paginés et l'émission d'événements temps réel via Server-Sent Events (SSE).

## 2. Inventaire des Endpoints REST Livrés

Toutes les ressources REST proposées sont implémentées sous le namespace `/api/admin/` et protégées par `@RolesAllowed("admin")` :

| Endpoint | Méthode | Statut | RBAC / Description |
|---|---|---|---|
| `/api/admin/me`, `users`, `config`, `session` | GET / POST | Livré | `@RolesAllowed("admin")` — Auth, session & configuration |
| `/api/admin/connectors/import` | POST | Livré | `@RolesAllowed("admin")` — Import CCcam |
| `/api/admin/status` | GET | Livré | `@RolesAllowed("admin")` — Statut synthétique (nœud, connecteurs, sessions) |
| `/api/admin/profiles` | GET | Livré | `@RolesAllowed("admin")` — Liste des profils (paginé `page`, `size`) |
| `/api/admin/connectors` | GET | Livré | `@RolesAllowed("admin")` — Liste des connecteurs (paginé `page`, `size`) |
| `/api/admin/sessions` | GET | Livré | `@RolesAllowed("admin")` — Liste des sessions actives (paginé `page`, `size`) |
| `/api/admin/cache` | GET | Livré | `@RolesAllowed("admin")` — Statistiques du cache ECM/CW |
| `/api/admin/events` | GET (SSE) | Livré | `@RolesAllowed("admin")` — Flux Server-Sent Events temps réel |

## 3. Contrat d'Enveloppe JSON et SSE
Le flux SSE `/api/admin/events` retourne un objet `Multi<OutboundSseEvent>` via l'injection de `jakarta.ws.rs.sse.Sse`.

Chaque événement émis inclut l'enveloppe JSON standardisée :
```json
{
  "instanceId": "node-alpha",
  "timestamp": "2026-08-11T12:00:00Z",
  "schemaVersion": "1.0",
  "payload": { ... }
}
```
L'ID émis (`id:`) est monotone par instance. En cas de saut non résoluble (gap dans le buffer de replay ou éviction), le serveur émet l'événement spécial `SNAPSHOT_REQUIRED` (raisons : `REPLAY_GAP`, `INVALID_LAST_EVENT_ID`, `FUTURE_LAST_EVENT_ID`, `SLOW_CONSUMER`) puis ferme la connexion. Le client réinitialise son état via les endpoints REST statiques avant de se reconnecter.

## 4. Composants et Classes Livrés (`csp-app/src/main/java/com/bowman/cspng/app/admin/`)

- `AdminRuntimeResource.java` (l.15) : Exposition des endpoints d'administration statique REST (`/api/admin/status`, `/api/admin/profiles`, `/api/admin/connectors`, `/api/admin/sessions`, `/api/admin/cache`), gérant la pagination (`page`, `size`) et appliquant des en-têtes HTTP de sécurité (`Cache-Control: no-store, no-cache, must-revalidate`).
- `AdminEventResource.java` (l.15) : Resource JAX-RS du flux SSE `/api/admin/events`. Lit le header `Last-Event-ID`, souscrit au `AdminEventBroker` et transforme les événements en `OutboundSseEvent` formatés en JSON avec `instanceId` et `timestamp`.
- `AdminEventBroker.java` (l.28) : Courtier d'événements SSE non-bloquant single-writer sur context Vert.x :
  - **Types d'événements** : `STATUS_CHANGED`, `CONFIG_RELOADED`, `CONNECTOR_CHANGED`, `SESSION_CHANGED`, `CACHE_CHANGED`, `DCW_CHANGED`, `SNAPSHOT_REQUIRED`, `HEARTBEAT`.
  - **Buffer de Replay** : Tampon circulaire (défaut 500 événements, `csp.sse.replay-size`).
  - **Gestion des clients lents** : File d'attente locale par abonné (défaut 64, `csp.sse.subscriber-queue-size`). Tout client saturé reçoit `SNAPSHOT_REQUIRED` ("SLOW_CONSUMER") et est déconnecté.
  - **Limitation & Heartbeat** : Plafond d'abonnés simultanés (`csp.sse.max-subscribers`, défaut 50) et ping périodique (`csp.sse.heartbeat-ms`, défaut 15s).
- `AdminEventBridge.java` (l.28) : Pont CDI `@ApplicationScoped` (startup `@Priority(300)`, shutdown `@Priority(100)`). Effectue un balayage périodique du runtime (`csp.sse.scan-ms`, défaut 1000ms) et écoute l'EventBus (`CONFIG_RELOADED_ADDRESS`) pour diffuser les événements au broker SSE en éliminant les redondances via des digests/baselines.
- `AdminSseResponseFilter.java` (l.14) : Filtre JAX-RS / RESTEasy Reactive qui intercepte `/api/admin/events` et renvoie une réponse HTTP 404 JSON si SSE est désactivé (`csp.sse.enabled=false`).

## 5. Configuration et Sécurité
- **Propriétés de configuration** :
  - `csp.sse.enabled=false` (désactivé par défaut en production, activé en `%test`).
  - `csp.sse.replay-size=500` (borné de 10 à 10 000).
  - `csp.sse.subscriber-queue-size=64` (borné de 4 à 1 000).
  - `csp.sse.max-subscribers=50` (borné de 1 à 1 000).
  - `csp.sse.heartbeat-ms=15000` (borné de 1 000 à 120 000 ms).
  - `csp.sse.scan-ms=1000` (borné de 250 à 60 000 ms).
- **Headers HTTP & Sécurité** : Les endpoints SSE forcent les en-têtes `Cache-Control: no-cache, no-store, must-revalidate` et `X-Accel-Buffering: no` (pour éviter le buffering des reverse-proxies comme Nginx). Authentification requise (`@RolesAllowed("admin")`) via cookie de session ou Basic Auth.

## 6. Tests et Validation
- `AdminRuntimeResourceTest.java` : Validation unit/integration des endpoints d'administration statique et de la pagination.
- `AdminEventResourceTest.java` / `AdminEventResourceQuarkusTest.java` : Validation de l'endpoint SSE et du header `Last-Event-ID`.
- `AdminEventBrokerTest.java` : Validation du broker SSE (replay, slow consumer eviction, heartbeat, snapshot required).
- `AdminEventBridgeTest.java` / `AdminEventBridgeQuarkusTest.java` : Validation du pont de balayage runtime vers SSE.

## 7. Limites Connues
1. **Désactivation par défaut en production** : La propriété `csp.sse.enabled` est à `false` par défaut et nécessite une activation explicite via la configuration MicroProfile (`CSP_SSE_ENABLED=true`).
2. **Buffer de Replay Local** : Le buffer de replay est maintenu en mémoire par instance (`AdminEventBroker`). En architecture multi-instances, la réinvalidation via `Last-Event-ID` suppose une affinité de session (sticky-session) ou déclenche un `SNAPSHOT_REQUIRED` en cas de bascule d'instance.
