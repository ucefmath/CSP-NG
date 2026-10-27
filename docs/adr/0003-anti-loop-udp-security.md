# ADR 0003 : Anti-boucle CSPv2 v1 (max-hop=1) et UDP spoofing — limites de sécurité connues

- **Statut** : Accepté
- **Date** : 2026-09-24
- **Contexte** : `docs/adr/0001-cspv2-wire-v1.md`, `docs/adr/0002-relay-workers-v1.md`, correctif Sonnet FIX #1 + #2 dans `StrictEcmRouter` (non commité, en revue).
- **Docs officielles lues** : Spécification CSPv2 interne (ADR 0001), code `StrictEcmRouter.java`, `WorkerCspV2Bridge.java`, `RelayCachePolicy.java`, `UdpLegacyTransport.java`.

---

## 1. Décision : Anti-boucle CSPv2 v1 — max-hop = 1

### Règle normative (implémentée)

Un ECM entrant depuis un nœud distant CSPv2 identifié par le préfixe `cspv2:<nodeId>` dans `EcmRequest.remoteAddress()` ou `EcmRequest.originId()` **NE DOIT JAMAIS** déclencher une consultation sortante (`externalConsult`) vers un autre nœud CSPv2.

```
remoteAddress.startsWith("cspv2:") || originId.startsWith("cspv2:") => isRemoteOrigin = true
isRemoteOrigin => ext = null (externalConsult forcé nul)
```

Fichier : `StrictEcmRouter.java` lignes ~334-348 (Sonnet FIX #1).

### Justification

- **CSPv2 v1 ne transporte pas de champ TTL/hop-count** dans le framing ECM_REQUEST (ADR 0001 §payload).
  Sans marqueur wire, la seule protection contre les boucles infinies est le refus de re-forward au niveau applicatif.
- Toute constellation cyclique (A→B→C→A) converge en zéro itération car B reçoit `remoteAddress="cspv2:node-a"` et refuse immédiatement de consulter C.
- Preuve fonctionnelle : `WorkerCspV2TwoWorkersTest#cyclicTopologyThreeNodesAntiLoopWithRealClient` — 3 nœuds en cycle, aucun upstream réel, `consultOk("node-c")=0`, `inboundEcmReceived("node-b")=0` depuis C, retour NOK en < 4s.
- Preuve unitaire : `StrictEcmRouterRelayTest#inboundCspv2NeverTriggersExternalConsult` — compteur `extConsultCalls` reste à 1 (local uniquement) même après envoi d'une requête d'origine `cspv2:node-b`.

### Limite documentée L1

**La protection est applicative, pas wire.** Un nœud malicieux ou mal implémenté peut ignorer la convention de préfixe `cspv2:` et envoyer des ECM_REQUEST répétés à haute fréquence sans modifier `originId`. La protection contre ce cas repose sur :
- Le mTLS (identité vérifiée par SAN URI `urn:csp:node:<id>`) — un nœud non authentifié ne peut pas ouvrir de session.
- La limite `max-inflight` par session (ADR 0001 §9, `csp.cspv2.max-inflight`, défaut 1000) — coupe-feu contre la tempête.
- Le `StrictPartitionedCache.InFlightResult` — coalescing des requêtes identiques en un seul in-flight (anti-amplification à l'identique).

**Remédiation v2 envisagée (hors périmètre v1)** : ajout d'un champ `hop_count` u8 dans ECM_REQUEST (nouveau type de trame, rupture de protocole → ADR séparé).

---

## 2. Risque connu : UDP spoofing — checksum non cryptographique

### Contexte

Le transport `UdpLegacyTransport` (cluster UDP historique) authentifie les messages via un **checksum applicatif non cryptographique** (CRC/XOR interne au protocole CCcam/CS378x legacy). Ce checksum :
- Détecte la corruption accidentelle.
- **Ne protège pas contre la falsification intentionnelle** : un attaquant sur le même segment réseau peut forger des paquets UDP avec un checksum valide recalculé.

### Vecteurs d'attaque

| Vecteur | Condition | Impact |
|---------|-----------|--------|
| IP spoofing UDP | L2 adjacence ou réseau non filtré | Injection d'un ECM ou d'une fausse CW dans le cluster |
| Replay UDP | Absence de nonce/timestamp | Réutilisation d'une CW capturée pour un autre ECM |
| Amplification | Multicast/broadcast UDP actif | Flood du cluster |

### Mesures actuelles (mitigations partielles)

- CSPv2 TCP mTLS (ADR 0001) élimine le risque pour les nœuds migrant vers CSPv2 — le canal UDP n'est utilisé que pour les nœuds legacy.
- Configuration réseau : le cluster UDP devrait être isolé sur un VLAN dédié non routable depuis Internet.
- `RelayCachePolicy.isLoop(originChain)` : refuse les ré-émissions depuis un nœud déjà dans la chaîne (protection anti-boucle, insuffisante contre spoofing).

### Risque résiduel documenté

**Le canal UDP legacy est vulnérable au spoofing d'IP si l'isolation réseau L2/L3 n'est pas garantie.**
Catégorie CVSS estimée : Medium (AV:A, AC:H, PR:N, UI:N, S:U, C:L, I:L, A:N) — adjacence réseau requise.

### Remédiation v1 (recommandée, hors scope code actuel)

1. Désactiver le transport UDP si tous les nœuds supportent CSPv2.
2. Filtrage pare-feu strict : autoriser UDP cluster uniquement depuis les IPs connues des nœuds.
3. **Pas de correctif code à appliquer en v1** : toute correction au checksum UDP modifierait le protocole wire partagé avec les nœuds tiers (CardServProxy legacy).

---

## 3. Synthèse des propriétés de sécurité par transport

| Propriété | CSPv2 TCP mTLS | UDP Legacy |
|-----------|---------------|------------|
| Authentification origine | mTLS (SAN URI) ✅ | Checksum applicatif ⚠️ |
| Confidentialité | TLS 1.3 ✅ | Aucune ❌ |
| Intégrité | TLS AEAD ✅ | Checksum non-crypto ⚠️ |
| Anti-replay | TLS session + nonce implicite ✅ | Aucune ❌ |
| Anti-boucle | max-hop=1 applicatif ✅ (L1 ci-dessus) | `isLoop(originChain)` ✅ |
| Résistance au spoofing IP | N/A (TCP) ✅ | Faible (UDP, L2 requis) ⚠️ |

---

## 4. Décisions d'implémentation associées

- `StrictEcmRouter` : `isRemoteOrigin` gate sur préfixe `cspv2:` → `externalConsult = null` si remote (Sonnet FIX #1).
- `WorkerCspV2Bridge.NodeExchangeStats` : compteur `inboundEcmReceived(fromNode)` pour preuve testable d'absence de forwarding.
- `RelayCachePolicy.isLoop()` : mécanisme complémentaire (champ `relayOriginChain`) conservé et non supprimé — défense en profondeur.

## 5. Références

- `csp-connectors/src/main/java/com/bowman/cspng/connectors/routing/StrictEcmRouter.java`
- `csp-worker/src/main/java/com/bowman/cspng/worker/WorkerCspV2Bridge.java`
- `csp-connectors/src/test/java/com/bowman/cspng/connectors/routing/StrictEcmRouterRelayTest.java`
- `csp-worker/src/test/java/com/bowman/cspng/worker/WorkerCspV2TwoWorkersTest.java`
- ADR 0001 `docs/adr/0001-cspv2-wire-v1.md` — framing wire CSPv2 v1
- ADR 0002 `docs/adr/0002-relay-workers-v1.md` — architecture relay workers
