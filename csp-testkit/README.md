# csp-testkit — Harnais de vérité binaire

Ce module génère et conserve les **vecteurs golden** du protocole `ClusteredCache` UDP.
Ces vecteurs sont le critère de non-régression byte-for-byte du lot L1
(`csp-codec/cluster`).

## Méthode de génération (Option A)

Les vecteurs de type `REQUEST` (0x01) sont produits en appelant **directement**
le code legacy CardServProxy Java 17, sans le modifier :

- `com.bowman.cardserv.ClusteredCache.writeCacheReq(DataOutputStream, CamdNetMessage, boolean)`

Les vecteurs de type `REPLY` (0x02) reproduisent fidèlement le code de
`ClusteredCache.sendMessage(request, reply)` : la trame est construite en
chaînant **deux objets `CamdNetMessage` distincts** :

1. l'objet `request` (l'ECM) fournit le `CacheReq` de 11 octets
   (`commandTag`, `serviceId`, `networkId`, `caId`, `dataHash`) via
   `ClusteredCache.writeCacheReq(...)` ;
2. l'objet `reply` (le CW) fournit le `rplTag` de 1 octet, le CW de 16 octets
   et le `connectorName` en `writeUTF` via
   `ClusteredCache.writeCacheRpl(...)`.

Cela donne les tailles exactes du plan §2.3 : **13 octets** (CW vide),
**29 octets** (CW sans nom) et **29+N octets** (CW + `connectorName`).

Les types `PINGREQ` (0x03), `PINGRPL` (0x04) et `RESENDREQ` (0x05) sont
retranscrits à partir du code d'émission privé de `ClusteredCache`
(`sendPing`, `sendPingReply`, `sendResendRequest`) car ces méthodes ne sont pas
publiques ; le layout est identique à celui de la spécification gelée §2.3 du
plan.

## Relancer la génération

```bash
cd csp-testkit
./src/main/resources/legacy-tool/generate-vectors.sh
```

Le script :

1. compile `LegacyVectorGenerator.java` avec le classpath legacy
   (`build/classes` + `lib/*`) ;
2. exécute le générateur ;
3. écrit le résultat dans
   `src/test/resources/vectors/cluster-udp.json`.

## Contenu du corpus

- **≥30 vecteurs** couvrant les tailles obligatoires 9/12/13/16/20/29/29+N.
- Cas particuliers : `connectorName` vide / 32 octets / non-ASCII, hash négatif,
  `onid=0`, `caid=0`, arbitre présent/absent, paquet tronqué/surdimensionné,
  type inconnu, PINGREQ/PINGRPL/RESENDREQ.

## Utilisation dans les tests

```java
VectorRepository repo = new VectorRepository();
for (VectorRepository.Vector v : repo.all()) {
    byte[] expected = v.bytes();
    // comparer avec l'encode du nouveau codec en L1
}
```
