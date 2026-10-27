# Guide d'implémentation : Protocole CSPv2

> **Statut : IMPLÉMENTÉ**. Cette fonctionnalité est entièrement livrée et fusionnée dans la branche principale (`main`). La spécification binaire du fil (wire) reste régie par l'ADR normatif **[docs/adr/0001-cspv2-wire-v1.md](adr/0001-cspv2-wire-v1.md)**.

## 1. État actuel vérifié
Le protocole CSPv2 est désormais intégralement implémenté. Il coexiste avec le transport UDP legacy (`UdpLegacyTransport`) et les connecteurs/sessions TCP standards (Newcamd, Radegast). Le protocole est désactivé par défaut via la propriété de configuration `csp.cspv2.enabled=false`. Lors de son activation, il offre un transport TCP mTLS exclusif, multiplexé et sécurisé entre nœuds `csp-ng`.

## 2. Objectifs et Exigences
- **Objectif** : Protocole TCP exclusif inter-nœuds `csp-ng` (framing binaire multiplexé, mTLS obligatoire fail-closed, sans Java Serialization).
- **Compatibilité** : Coexistence exigée avec le cluster UDP legacy existant. La présence de CSPv2 protège le transport TCP mTLS spécifique sans modifier les caractéristiques du canal UDP s'il reste activé.

## 3. Spécification Normative du Frame v1
La spécification binaire normative du protocole Wire CSPv2 v1 est définie de manière stricte et engageante dans l'ADR **[docs/adr/0001-cspv2-wire-v1.md](adr/0001-cspv2-wire-v1.md)**.

### 3.1. En-tête Binaire (20 octets Big-Endian)

| Offset | Taille | Champ | Description |
|---|---|---|---|
| 0 | 4 | `magic` | `0x43535032` ("CSP2") |
| 4 | 1 | `version` | `0x01` |
| 5 | 1 | `type` | Code binaire du message (uint8) |
| 6 | 2 | `flags` | Réservé v1 (`0x0000`, pas de compression/fragmentation applicative) |
| 8 | 4 | `payloadLength` | Taille du payload binaire (uint32 non signé, Java `long` via `int & 0xFFFFFFFFL`) |
| 12 | 8 | `streamId` | ID de corrélation (`0` = contrôle/connexion, `>0` = requêtes/réponses, bit signe 63 à 0) |

- **Politique de validation pré-allocation** : Le magic, la version, le type, les flags, le payloadLength (cap local configuré en pré-handshake, `min(local, distant)` post-handshake) et le streamId sont validés immédiatement dès la lecture des 20 octets de l'en-tête.
  - Pré-handshake (avant `HELLO_ACK` validé) ou magic invalide : fermeture silencieuse immédiate de la socket TCP sans allocation ni lecture du payload.
  - Post-handshake (`HELLO_ACK` validé) : émission d'une trame `ERROR` normative bornée puis fermeture contrôlée, sans allouer ni lire le payload.

### 3.2. Types de Trames v1 et Payloads

Le noyau v1 du protocole CSPv2 est restreint exclusivement aux six types de trames suivants :
- `0x01 HELLO` : Handshake initial, transmission de `nodeId`, `connectionId` (u64 non nul), `capabilities` (bit 0 = `ECM_ROUTING`), `maxPayloadAccepted` et `maxInflightAccepted`.
- `0x02 HELLO_ACK` : Validation du handshake, écho du `connectionId`, négociation des capacités et limites (`min(local, distant)`).
- `0x10 ECM_REQUEST` : Demande de résolution d'ECM (contient `EcmKey` 11 octets, profil UTF-8, ECM brut `ecmLen >= 3` et `maxWaitMs`). Hash ECM calculé sur le corps amputé des 3 premiers octets via `ClusterUdpCodec.ecmHash`.
- `0x11 DCW_REPLY` : Réponse reprenant le `streamId` exact. Payload exact de `16 + cwLen + connectorNameLen` octets (contient `EcmKey`, statut, CW 16 octets avec checksum `DcwValidator.checksumDcw` et nom de connecteur).
  - Statut `0 (OK)` : réservé à une CW 16 octets valide avec checksum.
  - Statut `1 (NOT_FOUND)` : absence de CW ou timeout métier (`CwReply.isEmpty()` mappe `NOT_FOUND` uniquement pour absence/timeout).
  - Statut `2 (DENIED)` : refus d'autorisation applicatif ou profil non autorisé.
- `0x31 ERROR` : Trame de signalement d'erreur issue d'un vocabulaire fermé normatif à 12 codes.
- `0x40 HEARTBEAT` : Keepalive asynchrone (payload vide, `streamId = 0`).

Les types `0x20 STATE_SNAPSHOT`, `0x21 STATE_DELTA` et `0x30 ACK` sont réservés et non implémentés en v1. Leur réception post-handshake entraîne l'émission d'une trame `ERROR` `UNSUPPORTED_TYPE` suivie de la fermeture de la connexion.

## 4. Machines d'état, Multiplexing et Timers
- **Tie-Break & Initiation Unique** : Seul le nœud dont l'identifiant `node-id` est lexicalement inférieur (ASCII) initie la connexion TCP vers l'autre. Une connexion initiée dans le sens interdit est rejetée avec `NOT_AUTHORIZED` puis fermée. Une seule connexion `ESTABLISHED` est active par pair.
- **Handshake & Appairage** : Valide l'égalité stricte `peers[i].node-id == Cert SAN URI nodeId == HELLO / HELLO_ACK nodeId`. Tout nœud entrant `DOIT` être configuré et activé dans `peers[i]`.
- **Heartbeat & Idle** : Émission périodique de `HEARTBEAT` à la fréquence `timeouts.heartbeat-ms` (`< idle-ms / 2`). Toute trame valide reçue réarme le timer d'inactivité `timeouts.idle-ms`. Aucun ACK n'est envoyé pour un heartbeat.
- **Backpressure & Pause** : Si `max-inflight` est atteint, l'event-loop met en pause la lecture du socket (`socket.pause()`). Durant la pause, le timer `idle-ms` est suspendu et remplacé par le timer `timeouts.backpressure-pause-ms` (`< idle-ms`). Si `writeQueueFull`, suspension de la production locale jusqu'à l'invocation du `drainHandler` Vert.x (avec timeout `timeouts.drain-ms` puis fermeture).
- **Corrélation & Déduplication** : `streamId` unique par connexion (impairs pour l'initiateur TCP, pairs non nuls pour l'accepteur TCP). Clé de déduplication protocolaire intra-session : `(nodeId, connectionId, streamId)` avec TTL et cache LRU borné. L'idempotence inter-reconnexion dépend de la couche métier.

## 5. Composants et Classes Livrés

### 5.1. Module Codec (`csp-codec/src/main/java/com/bowman/cspng/codec/cspv2/`)
- `CspV2Constants` : Constantes protocolaire normatives du wire v1 (MAGIC `0x43535032`, VERSION `0x01`, HEADER_SIZE 20, tailles et limites de payloads).
- `CspV2MessageType` : Énumération des 6 types de trames implémentés (`HELLO`, `HELLO_ACK`, `ECM_REQUEST`, `DCW_REPLY`, `ERROR`, `HEARTBEAT`) et 3 types réservés (`STATE_SNAPSHOT`, `STATE_DELTA`, `ACK`).
- `CspV2ErrorCode` : Vocabulaire fermé à 12 codes d'erreur protocolaire (`UNSUPPORTED_VERSION`, `IDENTITY_MISMATCH`, etc.) avec raison canonique.
- `CspV2ProtocolException` : Exception protocolaire typée avec catégorie (`INVALID_MAGIC`, `FRAME_TOO_LARGE`, etc.) permettant au moteur de trancher entre fermeture silencieuse ou émission d'erreur.
- `CspV2Header` : Record immuable représentant l'en-tête binaire de 20 octets et appliquant les validations sémantiques.
- `CspV2Frame` : Record associant un en-tête `CspV2Header` à un payload typé `CspV2Payload`.
- `CspV2Payload` : Interface scellée (`sealed interface`) regroupant les payloads immuables (`Hello`, `HelloAck`, `EcmRequest`, `DcwReply`, `ErrorPayload` et `Heartbeat`).
- `CspV2Codec` : Codec pur fonctionnel sans I/O ni dépendance externe (`decodeHeader`, `decodePayload`, `decodeFrame`, `encodeFrame`).

### 5.2. Module Transport TCP (`csp-cluster-tcp/src/main/java/com/bowman/cspng/cluster/tcp/`)
- `CspV2Config` : Interface `@ConfigMapping` (préfixe `csp.cspv2`) définissant l'ensemble de l'arborescence de configuration.
- `CspV2Settings` : Snapshot immuable et validé des paramètres avec méthode statique `from(config)` réalisant les contrôles fail-fast.
- `CspV2PeerSettings` : Représentation normalisée de la configuration d'un nœud distant (validations DNS strictes et profils).
- `CspV2Identity` : Contrôleur d'identité mTLS extrayant et vérifiant le SAN URI `urn:csp:node:<id>` sur la feuille du certificat X.509 sans aucun fallback CN/Subject DN.
- `CspV2TlsMaterial` : Conteneur immuable et `AutoCloseable` des certificats/clés PEM en mémoire avec support de réinitialisation des buffers (`wipe` / `destroy`).
- `CspV2TlsOptionsFactory` : Fabrique construisant les objets Vert.x `NetServerOptions` et `NetClientOptions` en mTLS strict (TLS 1.3 par défaut, TLS 1.2 AEAD opt-in).
- `CspV2Connection` : Moteur de connexion single-thread sur event-loop Vert.x (framing par `RecordParser` 20 octets, handshake `HELLO`/`HELLO_ACK`, tie-break lexical, multiplexage par `streamId`, déduplication LRU+TTL, gestion de la backpressure 1-slot, timers idle/heartbeat et drain gracieux).
- `CspV2ConnectionListener`, `CspV2ConnectionState`, `CspV2ConnectionRole` : Interfaces d'écoute asynchrone, états de machine à états (`PRE_HANDSHAKE`, `HELLO_SENT`, `ESTABLISHED`, `DRAINING`, `CLOSED`) et rôles TCP (`INITIATOR`, `ACCEPTOR`).
- `ConnectionHandle` : Abstraction interne (`RealHandle`) permettant l'isolation et le faking des connexions pour les tests unitaires.
- `CspV2Manager` : Orchestrateur réseau gérant l'écoute `NetServer` et les connexions clientes `NetClient`, le respect des limites globales et par nœud, le routage round-robin par profil via `requestWithPeer` / `CspV2PeerReply`, et la procédure d'arrêt bornée et idempotente.
- `CspV2ReconnectPolicy` : Calculateur de politique de reconnexion à backoff exponentiel et full jitter uniforme O(log) garanti sans overflow.

### 5.3. Module Adaptateur Applicatif (`csp-app/src/main/java/com/bowman/cspng/app/runtime/`)
- `CspV2DomainMapper` : Conversion pure wire ↔ domaine (`EcmRequest`, `CwReply`), comparaison manuelle des 5 champs de `EcmKey` (jamais `equals`), et traçabilité de provenance (`cspv2:<peer>`).
- `CspV2EcmHandler` : Traitement des ECM entrantes (`peekLocalCache` en lecture seule puis `CwsConnectorManager`, sans jamais appeler `processRequest` ou `handleRemoteRequest`).
- `CspV2CacheTransport` : Acheminement des ECM sortantes via `manager.requestWithPeer`, réinjection dans le cache par `cache.handleReply` uniquement sur statut OK validé ; `onReply` est un NOOP explicite (aucun pont automatique UDP ↔ TCP conforme à l'ADR §10).
- `CspV2Bootstrap` : Composant CDI `@ApplicationScoped` assurant le cycle de vie du sous-système CSPv2 :
  - **Démarrage** : `@Priority(260)` (exécuté après `RuntimeCore` à 200 et avant `ListenerManager` à 300).
  - **Arrêt** : `@Priority(20)` (exécuté avant `RuntimeCore` à 200). Ordre CDI ascendant confirmé par la documentation officielle Quarkus et le pattern `ListenerManager` existant.
  - **Fonctionnalités** : Résolution asynchrone des secrets `env:`/`file:` hors de l'event-loop, destruction garantie du matériel TLS en mémoire, et gestion des timeouts de démarrage avec nettoyage des managers orphelins.

## 6. Modules, Dépendances et Configuration
- **Indexation Jandex** : Le module `csp-cluster-tcp` intègre le plugin Maven `jandex-maven-plugin` (à l'instar de `csp-infinispan-bridge`, `csp-web` et `csp-users`) afin de générer son index Jandex (`META-INF/jandex.idx`), permettant la découverte de son `@ConfigMapping` par Quarkus dans les modules dépendants tels que `csp-app`.
- **Déclarations dans `application.properties`** : Seule la propriété `csp.cspv2.enabled=${CSP_CSPV2_ENABLED:false}` est explicitement déclarée dans le fichier `application.properties` du module `csp-app`. Toutes les autres propriétés utilisent les valeurs par défaut documentées dans l'ADR et portées par les annotations `@WithDefault` de `CspV2Config`, tout en restant surchargeables par variables d'environnement MicroProfile standard.

## 7. Threat Model et Sécurité
- **Spoofing & Downgrade** : mTLS obligatoire (`ClientAuth.REQUIRED`, CA de cluster dédiée), avec vérification d'identité stricte basée sur exactement un SAN URI (`urn:csp:node:<nodeId>`) et Hostname Verification HTTPS via SAN DNS. TLS 1.3 par défaut (TLS 1.2 opt-in avec suites ECDHE+AEAD seulement). Aucune authentification fallback, fail-closed systématique.
- **Flood & Oversized Frame** : Cap strict sur le payload (`max-payload-size`), rejet pré-allocation, limitation du nombre de handshakes TLS concourants et rate limiting par nœud post-identité.
- **Replay** : Déduplication `(nodeId, connectionId, streamId)` en mémoire tampon avec TTL.

## 8. Bilan d'Implémentation et Couverture de Tests
L'ensemble des 5 lots d'implémentation est entièrement livré, fusionné dans `main` et validé par la suite de tests globale (`mvn test`) :
1. **Lot 1 - Porte Documentaire** : ADR du frame binaire validée (`docs/adr/0001-cspv2-wire-v1.md`).
2. **Lot 2 - Codec Binaire** : Codec pur et golden vectors dans `csp-codec` (209 tests unitaires au total dans le module).
3. **Lot 3 - Transport TCP mTLS** : Module `csp-cluster-tcp` (config/TLS/identité ~42 tests, moteur de connexion ~41 tests, manager et reconnect policy ~38 tests).
4. **Lot 4 - Adaptateur Applicatif** : Composants `csp-app/runtime` (~52 tests dédiés à CSPv2).

## 9. Limites Connues et Travaux Futurs
1. **Couverture Constructeur de Production `CspV2CacheTransport`** : Le constructeur à 3 arguments de production de `CspV2CacheTransport` ne dispose pas de test d'intégration dédié. Cette absence est jugée non bloquante par revue de code, le câblage étant un assemblage trivial d'appels directs vers `manager.requestWithPeer` et `cacheVerticle.handleReply`.
2. **Tests d'Intégration TLS/mTLS Réels** : Il n'y a pas encore de tests d'intégration bout-en-bout ouvrant de véritables sockets TCP TLS/mTLS sur la boucle locale avec des certificats X.509 réels générés à la volée. Les validations du transport TCP reposent actuellement sur des mocks et sockets simulées dans les tests unitaires.
3. **Absence de Pont Automatique UDP ↔ TCP** : Conformément à la conception normative de l'ADR §10, aucun bridge automatique n'est établi entre les paquets UDP legacy et le réseau TCP CSPv2. Les deux sous-systèmes coexistent de manière indépendante.

## 10. Références Officielles Vert.x et Spécification
- [ADR 0001 : Spécification du Protocole Wire CSPv2 (v1)](adr/0001-cspv2-wire-v1.md)
- [Vert.x Core TCP Servers and Clients](https://vertx.io/docs/vertx-core/java/#_writing_tcp_servers_and_clients)
- [Vert.x SSL/TLS Configuration](https://vertx.io/docs/vertx-core/java/#_ssl_tls_servers_and_clients)
