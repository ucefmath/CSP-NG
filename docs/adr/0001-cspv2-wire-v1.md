# ADR 0001 : Spécification du Protocole Wire CSPv2 (v1)

- **Statut** : Accepté
- **Date** : 2026-08-11
- **Auteurs** : Équipe CSP-NG
- **Domaine** : Transport TCP inter-nœuds, Sécurité mTLS, Multiplexage, Framing binaire

---

## 1. Contexte

Le système CSP-NG s'appuie historiquement sur un protocole UDP (`UdpLegacyTransport`) pour les échanges inter-proxys. Afin d'offrir des garanties de sécurité renforcées, de fournir un canal exempt des risques d'amplification UDP et d'autoriser le multiplexage bidirectionnel de requêtes ECM sur des liaisons longue distance, un protocole TCP sécurisé dédié inter-nœuds est introduit pour CSPv2.

La coexistence avec le cluster UDP legacy reste supportée ; l'utilisation de CSPv2 protège le transport TCP mTLS spécifique sans modifier le comportement propre du canal UDP s'il demeure activé.

Cette ADR fige la spécification normative octet par octet du protocole wire CSPv2 (version 1) pour le transport TCP inter-nœuds. Les exigences formulées dans ce document emploient les termes normatifs de la RFC 2119 : `DOIT` (**MUST**), `NE DOIT PAS` (**MUST NOT**), `DEVRAIT` (**SHOULD**), `NE DEVRAIT PAS` (**SHOULD NOT**), et `PEUT` (**MAY**).

---

## 2. Décisions Validées

1. **Protocole binaire et framing strict** : En-tête binaire fixe de 20 octets en ordre réseau (Big-Endian) suivi d'une charge utile.
2. **Identification mTLS stricte et règle d'appairage** : Authentification mutuelle TLS (`ClientAuth.REQUIRED`) avec vérification d'identité basée sur exactement un SAN URI (`urn:csp:node:<id>`) et SAN DNS pour la vérification du nom d'hôte. Tout nœud entrant ou sortant `DOIT` être configuré et activé dans `peers[i]`. Le champ `host` est réservé aux connexions sortantes et au contrôle SAN DNS.
3. **Règle d'Initiation Unique par Paire (Tie-Break)** : Pour chaque paire de nœuds, seul le nœud au `node-id` lexicalement inférieur (octets ASCII normalisés) est autorisé à initier la connexion TCP. Une seule connexion à l'état `ESTABLISHED` est active par pair.
4. **Architecture TLS Vert.x & Secret Resolver** : Utilisation explicite des options Vert.x (`NetServerOptions`, `NetClientOptions`, `PemKeyCertOptions`, `PemTrustOptions`) et de `RuntimeSecretResolver` (références `env:` et `file:` uniquement, `vault:` explicitement hors v1), sans recourir au TLS Registry de Quarkus afin d'isoler la CA de cluster dédiée.
5. **Suites de chiffrement TLS 1.2 restreintes** : TLS 1.3 est le protocole obligatoire par défaut. En cas d'activation de TLS 1.2 (`allow-tls12=true`), seules les suites ECDHE avec AEAD (AES-GCM ou ChaCha20-Poly1305) sont autorisées.
6. **Noyau v1 restreint à 6 types de trames** : `HELLO` (0x01), `HELLO_ACK` (0x02), `ECM_REQUEST` (0x10), `DCW_REPLY` (0x11), `ERROR` (0x31) et `HEARTBEAT` (0x40).
7. **Multiplexage via `streamId`** : `streamId` de 64 bits (représenté en Java par un `long` positif, bit 63 à 0), alloué séparément par chaque direction (impairs pour l'initiateur TCP, pairs non nuls pour l'accepteur TCP).
8. **Politique d'erreur header différenciée et validation pré-allocation** :
   - Violation de `magic` => Fermeture silencieuse immédiate de la socket TCP dans tous les cas.
   - Pré-handshake (avant `HELLO_ACK` validé) => Violation de version/type/flags/oversize/streamId => Fermeture silencieuse immédiate sans allocation ni lecture du payload.
   - Post-handshake (`HELLO_ACK` validé) => Violation de version/type/flags/oversize/streamId => Émission d'une trame `ERROR` normative bornée puis fermeture contrôlée, sans allouer ni lire le payload binaire.
9. **Limite de payload dynamique** : Cap local configuré en pré-handshake (`max-payload-size`), négocié à `min(cap local, maxPayloadAccepted récepteur)` post-handshake.
10. **Gestion du Heartbeat, Backpressure & Reconnect** : Émission périodique de `HEARTBEAT` ; réarmement du timer `idle-ms` par toute trame valide reçue. Suspension du timer idle durant la pause de backpressure et substitution par un timer `backpressure-pause-ms` (< `idle-ms`). Reconnexion avec facteur fixe 2.0 et *full jitter* sur l'intervalle `[0, min(max, initial*2^attempt)]`, réinitialisé après connexion stable.
11. **Déduplication protocolaire intra-session** : Clé `(nodeId, connectionId, streamId)` avec cache borné TTL. Idempotence inter-reconnexion déléguée à la couche métier.

---

## 3. Spécification Wire Octet par Octet

Toutes les valeurs numériques multi-octets (u16, u32, u64, i32) `DOIVENT` être encodées en Big-Endian (Network Byte Order). Les chaînes de caractères `DOIVENT` être encodées en UTF-8 strict.

### 3.1. En-tête de trame (20 octets fixe)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           magic                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    version    |     type      |             flags             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         payloadLength                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                         streamId                              +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| Offset | Taille (octets) | Champ | Type | Description / Valeurs normatives |
|---|---|---|---|---|
| 0 | 4 | `magic` | u32 | Magique fixe : `0x43535032` (ASCII `"CSP2"`). `DOIT` correspondre exactement. |
| 4 | 1 | `version` | u8 | Version du protocole wire. `DOIT` être égale à `0x01` en v1. |
| 5 | 1 | `type` | u8 | Type de trame (voir section 3.2). |
| 6 | 2 | `flags` | u16 | En-tête réservé v1. `DOIT` être égal à `0x0000`. Aucune compression ni fragmentation. |
| 8 | 4 | `payloadLength` | u32 | Taille du payload binaire en octets (lu en entier non signé 32 bits, Java `long` via `int & 0xFFFFFFFFL`). |
| 12 | 8 | `streamId` | u64 | Identifiant de multiplexage / corrélation (Java `long` positif, `0` à `Long.MAX_VALUE`). Bit 63 à 1 strictement interdit. |

### 3.2. Types de trames v1 et Types Réservés

| Type (hex) | Type (décimal) | Nom | Implémenté v1 | Description |
|---|---|---|---|---|
| `0x01` | 1 | `HELLO` | Oui | Initialisation du handshake applicatif |
| `0x02` | 2 | `HELLO_ACK` | Oui | Acquittement du handshake et négociation |
| `0x10` | 16 | `ECM_REQUEST` | Oui | Demande de résolution d'ECM |
| `0x11` | 17 | `DCW_REPLY` | Oui | Réponse contenant le DCW ou le statut |
| `0x20` | 32 | `STATE_SNAPSHOT` | **Non** | Réservé (post-handshake => ERROR `UNSUPPORTED_TYPE` puis close) |
| `0x21` | 33 | `STATE_DELTA` | **Non** | Réservé (post-handshake => ERROR `UNSUPPORTED_TYPE` puis close) |
| `0x30` | 48 | `ACK` | **Non** | Réservé (post-handshake => ERROR `UNSUPPORTED_TYPE` puis close) |
| `0x31` | 49 | `ERROR` | Oui | Trame de signalement d'erreur normative |
| `0x40` | 64 | `HEARTBEAT` | Oui | Signal de maintien de connexion (keepalive) |

Tout autre code de type reçu `DOIT` être rejeté (pré-handshake => close silencieux ; post-handshake => trame `ERROR` `UNSUPPORTED_TYPE` puis fermeture).

---

### 3.3. Détail des charges utiles (Payloads)

#### 3.3.1. Trame `HELLO` (`0x01`)
- **`streamId` normatif** : `0`
- **Structure du payload** :

| Offset Relatif | Taille | Champ | Type | Description |
|---|---|---|---|---|
| 0 | 2 | `nodeIdLen` | u16 | Longueur du `nodeId` (1 à 63 octets). |
| 2 | N | `nodeId` | bytes | Identifiant du nœud émetteur en UTF-8 strict (regex: `[a-z0-9][a-z0-9._-]{0,62}`). |
| 2+N | 8 | `connectionId` | u64 | Identifiant unique aléatoire non nul généré pour cette session TCP. `0` est strictly interdit. |
| 10+N | 4 | `capabilities` | u32 | Masque de fonctionnalités. Bit 0 (`0x00000001`) = `ECM_ROUTING`. Bits inconnus ignorés lors de la négociation. |
| 14+N | 4 | `maxPayloadAccepted` | u32 | Taille maximale de payload acceptée par l'émetteur (borne [4096..16777216]). |
| 18+N | 4 | `maxInflightAccepted` | u32 | Nombre maximal de requêtes en vol simultanées acceptées par l'émetteur ([1..65535]). |

Taille totale du payload : `22 + nodeIdLen` octets.

Si `connectionId == 0` ou si `maxPayloadAccepted` / `maxInflightAccepted` sont hors des bornes autorisées, la trame `DOIT` être rejetée avec fermeture de la connexion.

#### 3.3.2. Trame `HELLO_ACK` (`0x02`)
- **`streamId` normatif** : `0`
- **Structure du payload** :

| Offset Relatif | Taille | Champ | Type | Description |
|---|---|---|---|---|
| 0 | 2 | `nodeIdLen` | u16 | Longueur du `nodeId` (1 à 63 octets). |
| 2 | N | `nodeId` | bytes | Identifiant du nœud répondeur en UTF-8 strict. |
| 2+N | 8 | `connectionId` | u64 | **Écho exact** du `connectionId` reçu dans le `HELLO` d'origine. |
| 10+N | 4 | `capabilities` | u32 | Capabilities négociées par intersection masquée (Bit 0 = `ECM_ROUTING`). |
| 14+N | 4 | `maxPayloadAccepted` | u32 | Capacité de payload négociée (`min(local, distant)`). |
| 18+N | 4 | `maxInflightAccepted` | u32 | Nombre maximal de requêtes en vol négocié (`min(local, distant)`). |

Taille totale du payload : `22 + nodeIdLen` octets.

#### 3.3.3. Trame `ECM_REQUEST` (`0x10`)
- **`streamId` normatif** : `> 0` (impair si initiateur, pair si accepteur)
- **Structure du payload** :

| Offset Relatif | Taille | Champ | Type | Description |
|---|---|---|---|---|
| 0 | 1 | `commandTag` | u8 | Tag de commande ECM (partie d'EcmKey). |
| 1 | 2 | `serviceId` | u16 | Identifiant de service DVB (partie d'EcmKey). |
| 3 | 2 | `networkId` | u16 | Identifiant de réseau DVB (partie d'EcmKey). |
| 5 | 2 | `caId` | u16 | Identifiant du système CA (partie d'EcmKey). |
| 7 | 4 | `dataHash` | i32 | Hash 32 bits signé calculé sur les données ECM (partie d'EcmKey). |
| 11 | 2 | `profileLen` | u16 | Longueur du nom de profil UTF-8 (1 à 64 octets). |
| 13 | P | `profile` | bytes | Nom du profil de carte / connecteur en UTF-8 strict. |
| 13+P | 2 | `ecmLen` | u16 | Longueur des données brutes de l'ECM (3 à 512 octets). |
| 15+P | E | `ecm` | bytes | Octets bruts de la trame ECM (`ecmLen >= 3`). |
| 15+P+E | 4 | `maxWaitMs` | u32 | Délai d'attente maximal accordé en millisecondes ([50..30000]). |

Taille totale du payload : `19 + profileLen + ecmLen` octets.

**Règle du calcul de Hash ECM** : Le serveur récepteur `DOIT` recalculer le `dataHash` sur le corps de l'ECM amputé **exactement des 3 premiers octets** (`table_id` [1 octet] + `section_length` [2 octets]), selon la règle métier canonique `ClusterUdpCodec.ecmHash`. Si `ecmLen < 3` ou si le `dataHash` calculé ne correspond pas au `dataHash` transmis dans la clé `EcmKey`, la trame `DOIT` être rejetée avec une trame `ERROR` `MALFORMED_PAYLOAD`.

#### 3.3.4. Trame `DCW_REPLY` (`0x11`)
- **`streamId` normatif** : `DOIT` être l'écho exact du `streamId` de l'`ECM_REQUEST` associée.
- **Structure du payload** :

| Offset Relatif | Taille | Champ | Type | Description |
|---|---|---|---|---|
| 0 | 1 | `commandTag` | u8 | Echo d'EcmKey (`commandTag`). |
| 1 | 2 | `serviceId` | u16 | Echo d'EcmKey (`serviceId`). |
| 3 | 2 | `networkId` | u16 | Echo d'EcmKey (`networkId`). |
| 5 | 2 | `caId` | u16 | Echo d'EcmKey (`caId`). |
| 7 | 4 | `dataHash` | i32 | Echo d'EcmKey (`dataHash`). |
| 11 | 1 | `replyTag` | u8 | Tag de réponse. |
| 12 | 1 | `status` | u8 | Statut de réponse : `0` = OK, `1` = NOT_FOUND, `2` = DENIED. |
| 13 | 1 | `cwLen` | u8 | Longueur de la Control Word : `16` si `status == 0`, `0` sinon. |
| 14 | C | `cw` | bytes | Clé de contrôle CW (16 octets si `status == 0`, 0 octet sinon). |
| 14+C | 2 | `connectorNameLen` | u16 | Longueur du nom de connecteur (0 à 64 octets). |
| 16+C | K | `connectorName` | bytes | Nom du connecteur source UTF-8 (vide si désactivé/config `expose-connector-name=false`). |

Taille totale du payload : **`16 + cwLen + connectorNameLen` octets**. (Clé `EcmKey` = 11 octets ; champs additionnels fixes = 5 octets).

**Règles d'association du statut (`status`)** :
- `0 (OK)` : Réservé exclusivement à la délivrance d'une CW valide de 16 octets dont la somme de contrôle est vérifiée conforme par `DcwValidator.checksumDcw`.
- `1 (NOT_FOUND)` : Indique une absence de réponse ou un timeout métier d'obtention de la CW. Une réponse métier vide (`CwReply.isEmpty()`) `DOIT` être cartographiée en `NOT_FOUND` uniquement lorsqu'elle représente une absence ou un timeout, jamais un refus d'autorisation.
- `2 (DENIED)` : Indique un refus d'autorisation applicatif ou un profil non autorisé par la politique d'accès locale.

#### 3.3.5. Trame `ERROR` (`0x31`)
- **`streamId` normatif** : Copié de la requête originale si l'erreur concerne un `streamId` spécifique (`> 0`), ou `0` pour les erreurs globales / handshake.
- **Structure du payload** :

| Offset Relatif | Taille | Champ | Type | Description |
|---|---|---|---|---|
| 0 | 2 | `code` | u16 | Code d'erreur normatif (voir tableau 3.3.6). |
| 2 | 2 | `reasonLen` | u16 | Longueur du message d'explication (0 à 120 octets). |
| 4 | R | `reason` | bytes | Raison textuelle en UTF-8 issue exclusivement d'un vocabulaire fermé. |

Taille totale du payload : `4 + reasonLen` octets.

Le champ `reason` `NE DOIT PAS` contenir de données dynamiques, de secrets, d'ECM bruts, de CW ou d'informations de débogage d'infrastructure.

#### 3.3.6. Vocabulaire fermé des codes d'erreur

| Code (hex) | Code (décimal) | Constante Normative | Description |
|---|---|---|---|
| `0x0001` | 1 | `UNSUPPORTED_VERSION` | Version du header protocole non supportée (!= 1) |
| `0x0002` | 2 | `UNSUPPORTED_TYPE` | Type de trame inconnu ou non implémenté en v1 |
| `0x0003` | 3 | `UNSUPPORTED_FLAGS` | Flags non nuls reçus (!= 0x0000) |
| `0x0004` | 4 | `FRAME_TOO_LARGE` | `payloadLength` supérieur à la limite négociée/configurée |
| `0x0005` | 5 | `MALFORMED_PAYLOAD` | Erreur de désérialisation du payload binaire ou hash ECM invalide |
| `0x0006` | 6 | `STREAM_ID_INVALID` | `streamId` hors séquence, bit signe présent, wrap, ou invalide pour le type |
| `0x0007` | 7 | `TOO_MANY_INFLIGHT` | Dépassement du nombre maximal de requêtes simultanées |
| `0x0008` | 8 | `IDENTITY_MISMATCH` | Incohérence entre `nodeId` applicatif et le cert SAN URI mTLS |
| `0x0009` | 9 | `NOT_AUTHORIZED` | Action non autorisée, direction d'initiation invalide ou trame métier avant `HELLO_ACK` |
| `0x000A` | 10 | `TIMEOUT` | Expiration de traitement applicatif ou de réponse |
| `0x000B` | 11 | `SHUTTING_DOWN` | Le nœud distant est en cours d'arrêt propre |
| `0x000C` | 12 | `RATE_LIMITED` | Seuil d'abus ou de débit dépassé |

#### 3.3.7. Trame `HEARTBEAT` (`0x40`)
- **`streamId` normatif** : `0`
- **Structure du payload** : Charge utile vide (`payloadLength = 0`).

---

## 4. Machines d'état, Multiplexage et Timers

### 4.1. Cycle de vie de la connexion et Règle d'Initiation Unique (Tie-Break)
1. **Règle d'Initiation Unique (Tie-Break)** :
   - Pour chaque paire de nœuds configurée (ex. `node-alpha` et `node-beta`), le nœud dont l'identifiant `node-id` est **lexicalement inférieur** (comparaison d'octets ASCII normalisés) est le **seul initiateur autorisatif** de la connexion TCP vers l'autre nœud.
   - Le nœud au `node-id` lexicalement supérieur `DOIT` rester en attente passive et refuser d'initier une connexion vers le nœud inférieur.
   - Si une connexion sortante est tentée par le nœud non autorisé ou si une connexion entrante provenant d'un nœud inférieur est reçue sur le serveur, la connexion `DOIT` être traitée normalement si elle provient de l'initiateur légitime. En revanche, si une connexion est initiée dans la direction interdite, elle `DOIT` être rejetée immédiatement après la trame `HELLO` par une trame `ERROR` `NOT_AUTHORIZED` puis fermeture de la socket.
   - **Unicité** : Une seule connexion à l'état `ESTABLISHED` est active simultanément par pair. Une nouvelle connexion valide remplace l'ancienne uniquement lors d'un drain contrôlé (rotation/restart).
   - **Restriction v1** : Cette contrainte exclut explicitement en v1 les topologies réseau nécessitant un sens d'appel (*dial*) imposé par des pare-feux asymétriques.
2. **Handshake TLS / mTLS** : Établissement du canal TCP sécurisé. Échec => Fermeture immédiate de la socket TCP sans réponse applicative.
3. **Handshake Applicatif CSPv2** :
   - L'initiateur TCP légitime envoie immédiatement une trame `HELLO` (`streamId = 0`).
   - L'accepteur TCP valide l'identité mTLS et le payload `HELLO`, puis répond par une trame `HELLO_ACK` (`streamId = 0`).
   - Toute trame métier (`ECM_REQUEST`, `DCW_REPLY`, `HEARTBEAT`) reçue **avant** la complétion et validation du `HELLO_ACK` `DOIT` être rejetée avec la trame `ERROR` `NOT_AUTHORIZED` puis fermeture de la connexion.
4. **Phase Opérationnelle** : Échange bidirectionnel multiplexé de trames `ECM_REQUEST`, `DCW_REPLY`, `HEARTBEAT` et `ERROR`.
5. **Fermeture** : Fermeture propre sur envoi de `ERROR` avec le code `SHUTTING_DOWN` ou déconnexion TCP brute.

### 4.2. Règles d'allocation et de contrôle des `streamId`
- `streamId = 0` : Réservé exclusivement aux trames de contrôle / connexion (`HELLO`, `HELLO_ACK`, `HEARTBEAT`, et trames `ERROR` au niveau connexion).
- `streamId > 0` :
  - **Initiateur TCP** (client Vert.x NetClient) : Alloue des identifiants `streamId` **impairs** (1, 3, 5, 7, ...).
  - **Accepteur TCP** (serveur Vert.x NetServer) : Alloue des identifiants `streamId` **pairs non nuls** (2, 4, 6, 8, ...).
- **Monotonicité** : Les `streamId` alloués par chaque direction `DOIVENT` être strictly croissants.
- **Réponse** : Les trames `DCW_REPLY` et `ERROR` ciblées `DOIVENT` reprendre l'identifiant exact de la trame `ECM_REQUEST` à laquelle elles répondent.
- **Gestion du Wrapping** : Si la prochaine allocation (`streamId + 2`) dépasserait `Long.MAX_VALUE` (2^63 - 1), l'émetteur `DOIT` fermer proprement la connexion pour forcer une nouvelle session.
- **Trames tardives / inconnues** : Si une trame de réponse arrive avec un `streamId` inconnu ou expiré, la trame `DOIT` être ignorée (*dropped*) et la métrique `csp.cspv2.stream.unknown.drop` `DOIT` être incrémentée. Si le nombre de `streamId` inconnus dépasse `unknown-streams-per-minute` (défaut 100/min), la connexion `DOIT` être fermée.

### 4.3. Gestion du Heartbeat, Idle et Pause de Backpressure
- **Émission du Heartbeat** : Après la validation du handshake applicatif (`HELLO_ACK`), chaque nœud émet périodiquement une trame `HEARTBEAT` (`streamId = 0`) selon l'intervalle configuré `timeouts.heartbeat-ms` (ex. 10000 ms). La propriété `timeouts.heartbeat-ms` `DOIT` être configurée strictement inférieure à `timeouts.idle-ms / 2`. Aucun ACK n'est généré en réponse à un `HEARTBEAT`.
- **Réarmement de l'Idle** : La réception de **toute trame valide** (y compris `HEARTBEAT`) réarme le timer d'inactivité `timeouts.idle-ms`. Si aucune trame n'est reçue durant `timeouts.idle-ms`, la connexion est fermée.
- **Comportement sous Backpressure** :
  - Lorsque la lecture du socket est mise en pause (`socket.pause()`) en raison du dépassement du seuil `max-inflight`, le timer `idle-ms` normal `DOIT` être temporairement suspendu afin d'éviter tout faux timeout d'inactivité dû au blocage volontaire de la lecture.
  - Pendant cette pause, un timer dédié `timeouts.backpressure-pause-ms` (ex. 15000 ms) est activé. La condition `backpressure-pause-ms < idle-ms` `DOIT` être validée au démarrage.
  - Si la pause de backpressure s'étire au-delà de `backpressure-pause-ms`, la connexion `DOIT` être fermée pour libérer les ressources.
  - Dès la reprise de la lecture (`socket.resume()`), le timer `idle-ms` normal est réarmé.

---

## 5. Sécurité mTLS, PKI et Choix d'Architecture

### 5.1. Authentification Mutuelle, Identité et Suites TLS 1.2
1. **Versions et Suites TLS** :
   - TLS 1.3 `DOIT` être activé par défaut.
   - TLS 1.2 est autorisé uniquement si `csp.cspv2.tls.allow-tls12=true`. En cas d'activation de TLS 1.2, **seules les suites de chiffrement ECDHE associées aux algorithmes AEAD** (AES-GCM ou ChaCha20-Poly1305) sont autorisées. Les mécanismes d'échange de clés RSA statiques, les modes de chiffrement CBC, 3DES, RC4 et NULL sont **strictement interdits**.
   - SSLv3, TLS 1.0 et TLS 1.1 `DOIVENT` être strictement désactivés.
2. **Authentification Mutuelle Obligatoire** : `ClientAuth.REQUIRED` `DOIT` être configuré sur Vert.x `NetServerOptions`. Le paramètre `trustAll` `DOIT` être positionné à `false` sur client et serveur.
3. **Vérification d'Identité par SAN URI et Appairage Entrant/Sortant** :
   - L'identité d'un nœud `DOIT` être extraite **exclusivement** à partir d'exactement un SAN URI dans le certificat X.509 (`urn:csp:node:<nodeId>`).
   - Le `Subject DN` et le `CN` (Common Name) `NE DOIVENT PAS` être utilisés.
   - **Règle d'Appairage Entrant/Sortant** : Tout nœud entrant `DOIT` être préalablement déclaré et activé (`enabled=true`) dans la liste de configuration des pairs `peers[i]`. Le champ `peers[i].node-id` définit l'`Expected Peer nodeId`.
   - **Validation Triple de l'Identité** : Pour toute connexion (entrante ou sortante), le système `DOIT` vérifier l'égalité stricte : `peers[i].node-id == Cert SAN URI nodeId == HELLO / HELLO_ACK nodeId`.
   - Le champ `peers[i].host` n'est utilisé que pour établir les connexions sortantes et réaliser la vérification HTTPS Hostname Verification via SAN DNS.
   - L'allowlist des profils autorisés (`peers[i].profiles`) s'applique de manière symétrique aux flux entrants et sortants.

### 5.2. Justification : Vert.x NetOptions & Secret Resolver vs Quarkus TLS Registry
Le module `csp-cluster-tcp` utilise directement les options Vert.x (`NetServerOptions`, `NetClientOptions`, `PemKeyCertOptions`, `PemTrustOptions`) couplées à `RuntimeSecretResolver`, au lieu du TLS Registry de Quarkus, pour les raisons suivantes :
1. **CA de Cluster Dédiée** : Le cluster CSPv2 requiert un Truststore CA strictement isolé du Truststore HTTPS global de l'application Quarkus.
2. **Contrôle Réseau Bas Niveau Vert.x** : Exigence de configurer directement la vérification d'hôte HTTPS via SAN DNS, la sélection des suites AEAD et le mode `ClientAuth.REQUIRED` au niveau du transport réactif Vert.x TCP.
3. **Gestion des Secrets Existante** : Réutilisation directe du resolver du projet supportant les préfixes `env:` et `file:` (avec rejet fail-fast des schémas non supportés comme `vault:`).
4. **Validation Applicative de l'Identité SAN** : Inspection explicite et validation de l'extension X.509 SAN URI (`urn:csp:node:<id>`) post-handshake TLS.
5. **Rotation à chaud** : La rotation dynamique des certificats est hors périmètre v1 ; un redémarrage ou un drain contrôlé des connexions est requis.

---

## 6. Configuration MicroProfile Exhaustive

Toutes les propriétés de configuration sont déclarées sous le préfixe `csp.cspv2.` via MicroProfile Config. La configuration `NE DOIT PAS` utiliser de chaîne opaque de pairs ; les pairs sont déclarés via des structures indexées typées.

### 6.1. Tableau des Propriétés Normatives

| Propriété | Type | Valeur par défaut | Bornes / Contraintes | Description |
|---|---|---|---|---|
| `csp.cspv2.enabled` | boolean | `false` | `true`, `false` | Activation globale du service TCP CSPv2 |
| `csp.cspv2.node-id` | string | `""` | Regex `[a-z0-9][a-z0-9._-]{0,62}` | Identifiant du nœud local. Obligatoire si `enabled=true`. DOIT égaler le SAN URI du certificat local |
| `csp.cspv2.listen-host` | string | `"0.0.0.0"` | IP / Hostname | Adresse d'écoute du serveur TCP |
| `csp.cspv2.listen-port` | int | `9090` | `1..65535` | Port d'écoute du serveur TCP |
| `csp.cspv2.max-payload-size` | int | `1048576` (1 Mo) | `4096..16777216` | Cap local configuré de taille de payload par trame (octets). Limite effective post-handshake = `min(local, distant)` |
| `csp.cspv2.max-inflight` | int | `1000` | `1..65535` | Nombre maximal de requêtes ECM simultanées en vol par session |
| `csp.cspv2.max-connections` | int | `100` | `1..10000` | Nombre maximal de connexions TCP simultanées sur le serveur |
| `csp.cspv2.max-connections-per-node` | int | `10` | `1..100` | Limite de connexions simultanées concourantes (incluant handshakes et drain) autorisées pour un même `nodeId` (max 1 ESTABLISHED par pair) |
| `csp.cspv2.requests-per-second-per-node` | int | `5000` | `1..100000` | Limite de débit de requêtes par seconde par nœud (rate limiting post-identité) |
| `csp.cspv2.timeouts.tls-handshake-ms` | long | `5000` | `1000..60000` | Timeout maximal pour réaliser le handshake mTLS (ms) |
| `csp.cspv2.timeouts.hello-ms` | long | `5000` | `1000..60000` | Timeout maximal pour finaliser la séquence `HELLO`/`HELLO_ACK` (ms) |
| `csp.cspv2.timeouts.idle-ms` | long | `30000` | `5000..300000` | Timeout d'inactivité du canal sans trame valide reçue (ms) |
| `csp.cspv2.timeouts.heartbeat-ms` | long | `10000` | `1000..60000` | Fréquence d'émission des heartbeats (ms). DOIT être `< idle-ms / 2` |
| `csp.cspv2.timeouts.drain-ms` | long | `5000` | `1000..30000` | Timeout max d'attente du `drainHandler` en cas de socket pleine (ms) |
| `csp.cspv2.timeouts.shutdown-drain-ms` | long | `10000` | `1000..60000` | Timeout de vidange des requêtes en cours lors de l'arrêt propre (ms) |
| `csp.cspv2.timeouts.connect-ms` | long | `5000` | `1000..30000` | Timeout d'établissement de connexion TCP vers un pair (ms) |
| `csp.cspv2.timeouts.backpressure-pause-ms` | long | `15000` | `1000..60000` | Durée maximale d'une pause de lecture due au max-inflight (ms). DOIT être `< idle-ms` |
| `csp.cspv2.reconnect.initial-ms` | long | `100` | `10..5000` | Intervalle initial de reconnexion (ms). Multiplicateur fixe 2.0 avec full jitter `[0, min(max, initial*2^attempt)]`, réinitialisé sur connexion stable |
| `csp.cspv2.reconnect.max-ms` | long | `30000` | `1000..300000` | Intervalle maximal de reconnexion (ms) |
| `csp.cspv2.dedup.max-entries` | int | `10000` | `100..1000000` | Nombre maximal d'entrées conservées dans le cache de déduplication protocolaire |
| `csp.cspv2.dedup.ttl-ms` | long | `60000` | `1000..3600000` | TTL des entrées du cache de déduplication protocolaire (ms) |
| `csp.cspv2.unknown-streams-per-minute` | int | `100` | `1..1000` | Nombre maximal autorisé de streamId inconnus par minute avant déconnexion |
| `csp.cspv2.tls.allow-tls12` | boolean | `false` | `true`, `false` | Autoriser TLS 1.2 en fallback avec suites ECDHE+AEAD uniquement (TLS 1.3 obligatoire par défaut) |
| `csp.cspv2.tls.cert-ref` | string | `""` | `file:...`, `env:...` | Référence vers le certificat du nœud local |
| `csp.cspv2.tls.key-ref` | string | `""` | `file:...`, `env:...` | Référence vers la clé privée du nœud local |
| `csp.cspv2.tls.trust-ref` | string | `""` | `file:...`, `env:...` | Référence vers le truststore CA du cluster |
| `csp.cspv2.expose-connector-name` | boolean | `false` | `true`, `false` | Transmettre le nom du connecteur source dans les réponses `DCW_REPLY` |

### 6.2. Configuration des Pairs Structurés

Chaque pair distant à connecter est déclaré sous une clé structurée indexée `csp.cspv2.peers[i].` :
- `csp.cspv2.peers[0].enabled=true`
- `csp.cspv2.peers[0].node-id=node-beta` *(Identité attendue)*
- `csp.cspv2.peers[0].host=node-beta.cluster.internal` *(Nom DNS obligatoire pour connexions sortantes et SAN DNS, IP littérale interdite)*
- `csp.cspv2.peers[0].port=9090`
- `csp.cspv2.peers[0].profiles=profile-sec,profile-hd` *(Allowlist des profils autorisés appliquée de manière symétrique aux flux entrants et sortants)*

### 6.3. Règles de Validation Fail-Fast au Démarrage
L'application `DOIT` refuser de démarrer (échec fail-fast) si l'une des conditions invalides suivantes est détectée :
1. `csp.cspv2.enabled=true` alors que `node-id` est vide ou non conforme à la regex `[a-z0-9][a-z0-9._-]{0,62}`.
2. L'une des références `tls.*-ref` est vide ou utilise un schéma non supporté (notamment `vault:`).
3. Un `peers[i].host` contient une adresse IP littérale (ex. `192.168.1.10` ou `::1`).
4. `timeouts.heartbeat-ms >= timeouts.idle-ms / 2`.
5. `timeouts.backpressure-pause-ms >= timeouts.idle-ms`.

---

## 7. Robustesse de Parsing et Politique d'Erreurs

### 7.1. Validation Pré-Allocation de l'En-tête
1. Le parser Vert.x consomme d'abord l'en-tête binaire fixe de 20 octets.
2. Dès la réception des 20 octets, la validation contrôle immédiatement : `magic`, `version`, `type`, `flags`, `payloadLength` et le bit de signe de `streamId`.
3. Si une violation d'en-tête est détectée :
   - **Fermeture Silencieuse Immédiate** : La socket TCP est fermée sur-le-champ (`socket.close()`).
   - **Absence d'allocation** : Aucun buffer de payload N'EST alloué, lu ou consommé en mémoire.
   - **Traitement de la Trame ERROR** :
     - Si le header est corrompu **avant** la validation du `HELLO_ACK` (ou si `magic != 0x43535032`), **AUCUNE** trame `ERROR` n'est émise afin de couper court aux tentatives de déni de service et d'amplification.
     - Si une erreur d'en-tête survient **après** la validation du `HELLO_ACK` sur un canal mTLS authentifié, une trame `ERROR` normative bornée est transmise avant la fermeture du socket TCP.

### 7.2. UTF-8 Strict et Contrôles Métier
- La conversion des chaînes de caractères (`nodeId`, `profile`, `connectorName`, `reason`) `DOIT` employer un décodeur UTF-8 strict rejetant les séquences malformées.
- Si le payload binaire d'une trame est tronqué ou malformé, une trame `ERROR` `MALFORMED_PAYLOAD` est émise sur canal authentifié, suivie de la déconnexion.

---

## 8. Backpressure, Contrôle de Flux et DoS

1. **Gestion des requêtes en vol (`max-inflight`)** :
   - Si le nombre de requêtes simultanées en vol atteint la limite négociée, l'event-loop applique `socket.pause()`.
   - La lecture reprend via `socket.resume()` dès que le nombre de requêtes repasse sous le seuil d'eau bas.
2. **Gestion des sockets saturées (`writeQueueFull`)** :
   - Lorsque `socket.writeQueueFull()` retourne `true`, l'application positionne un `drainHandler` Vert.x et interrompt la production.
   - Si le drain n'intervient pas dans un délai de `timeouts.drain-ms` (ex. 5000 ms), la connexion est fermée.
3. **Protections DoS Réseau** :
   - **Connexions globales & par nœud** : Rejet silencieux de toute nouvelle connexion au-delà de `max-connections` ou `max-connections-per-node`.
   - **Rate Limiting** : Limite stricte `requests-per-second-per-node` appliquée dès la validation de l'identité du nœud.
   - **Pré-handshake DoS** : Limite du nombre de handshakes TLS simultanés en cours par IP / budget d'allocation global ; fermeture silencieuse des connexions excédentaires.

---

## 9. Déduplication Protocolaire et Idempotence

1. **Clé de Déduplication Protocolaire Intra-Session** :
   - Chaque trame est identifiée au niveau protocolaire par le triplet : `(nodeId, connectionId, streamId)`.
   - Le cache de déduplication protocolaire (LRU borné à `dedup.max-entries` avec TTL `dedup.ttl-ms`) conserve cette clé pour éviter les re-traitements au sein d'une même session TCP.
2. **Gestion des Replays et Idempotence Inter-Reconnexion** :
   - Si une trame dupliquée exacte avec le même triplet `(nodeId, connectionId, streamId)` est reçue sur la même session, la réponse en cache est réémise. Si la réponse n'est plus en cache, la trame est ignorée (*dropped*) sans double dispatch métier.
   - **Portée Limitée de la Clé** : Une reconnexion TCP génère obligatoirement un nouveau `connectionId` (nouvelle session), et les réémissions applicatives métier créent de nouveaux `streamId`. L'idempotence inter-reconnexion dépend uniquement de la couche de cache / routage métier et **n'est pas garantie** par la clé protocolaire `(nodeId, connectionId, streamId)`. La clé `EcmKey` / `dataHash` seule `NE DOIT PAS` être utilisée comme clé de déduplication protocolaire en raison des risques de collisions sur des ECM identiques légitimes.

---

## 10. Coexistence avec le Cluster UDP

1. **Indépendance des Transports** :
   - Le protocole CSPv2 TCP fonctionne de manière totalement indépendante du transport `UdpLegacyTransport`.
   - Le transport UDP historique conserve son comportement d'origine. L'introduction de CSPv2 sécurise le transport TCP sans éliminer les risques inhérents au canal UDP s'il reste activé.
2. **Absence de Bridge Automatique & Couplage** :
   - Aucun bridge automatique ni routage UDP <-> TCP n'est fourni en v1.
   - CSPv2 est un service dédié de traitement d'ECM et ne dérive pas de `CacheTransport`.

---

## 11. Conséquences et Impact

- **Positifs** :
  - Transport TCP inter-nœuds fortement sécurisé (mTLS, SAN URI, SAN DNS, aucune sérialisation Java).
  - Suppression des risques d'amplification et de spoofing sur le canal TCP mTLS dédié.
  - Structure de trame déterministe prévenant les attaques DoS mémoire.
- **Négatifs / Limites** :
  - Le canal UDP legacy, s'il est conservé en parallèle, garde ses caractéristiques propres.
  - Absence de support de la sérialisation complexe ou de la compression en v1.
  - La règle d'initiation unique par comparaison lexicalement inférieure de `node-id` exclut en v1 les topologies exigeant un sens d'appel (dial) imposé par des pare-feux asymétriques.

---

## 12. Non-Objectifs (Out of Scope v1)

1. **Intégration HashiCorp Vault** : Support non inclus en v1 (seules les références `env:` et `file:` sont valides).
2. **Trames d'état de topologie (`0x20` / `0x21`)** : La synchronisation dynamique de topologie fera l'objet d'un lot ultérieur.
3. **Compression applicative** : Le champ `flags` reste fixé à `0x0000`.
4. **Bridge UDP <-> TCP** : La conversion automatique de paquets UDP vers CSPv2 est exclue de cette ADR.

---

## 13. Critères de Conformité (Acceptance Criteria)

- [ ] L'en-tête de trame mesure exactement 20 octets en Big-Endian avec `magic = 0x43535032` et `version = 0x01`.
- [ ] La structure de payload `DCW_REPLY` respecte exactement `16 + cwLen + connectorNameLen` octets.
- [ ] Le calcul de hash ECM est effectué sur l'ECM amputée des 3 premiers octets via `ClusterUdpCodec.ecmHash`.
- [ ] Les violations de header pré-handshake provoquent une fermeture silencieuse sans allocation de payload ; les violations post-handshake envoient une trame `ERROR` normative avant fermeture.
- [ ] La règle de tie-break impose que seul le nœud au `node-id` lexicalement inférieur initie la connexion TCP (max 1 connexion ESTABLISHED par pair).
- [ ] Tout nœud entrant est vérifié présent et activé dans `peers[i]`, avec validation triple de l'identité SAN URI.
- [ ] En cas d'activation de TLS 1.2, seules les suites ECDHE avec AEAD (AES-GCM / ChaCha20-Poly1305) sont autorisées.
- [ ] La configuration MicroProfile valide toutes les propriétés déclarées, exige des noms DNS pour les pairs et applique le fail-fast au démarrage (sans double négation dans la spécification).
- [ ] Le timer d'idle est suspendu pendant la pause de backpressure et remplacé par `backpressure-pause-ms`.
- [ ] L'architecture TLS s'appuie sur les options Vert.x et `RuntimeSecretResolver` sans utiliser le TLS Registry de Quarkus.
- [ ] La déduplication protocolaire emploie `(nodeId, connectionId, streamId)` sans utiliser `EcmKey` seul.
