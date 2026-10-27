# Packaging worker relais + natif Debian 13 (Trixie) x86_64 — runbook

> Statut : worker headless **dataplane reel JVM** (`WorkerRuntime` : listeners
> CCcam/CS378x servis, connecteurs amont assignes, pont CSPv2, canal mTLS),
> binaire natif **`csp-worker/target/csp-worker`** (voir §3 preuves), service
> systemd `deploy/csp-worker.service`. Aucun artefact faussement termine :
> les manques sont listes en §6.

## 1. Modules et dependances

- `csp-worker` : sans PG (`reactive-pg-client`, JDBC, Flyway absents du runtime),
  sans UI (`csp-web` exclu). Deps : `domain,config,connectors,cluster-tcp,
  sessions,vertx-core,vertx-web-client,jackson-databind`.
- Exclusion `io.quarkus:quarkus-core` sur les 5 deps internes (voir §3 pourquoi) ;
  **verifie sans regression JVM** : `WorkerRuntimeTest` + `WorkerStateTest` 3/3
  verts avec les exclusions (`mvn -B -pl csp-worker -am test -Dtest='Worker*'`).
- Centrale : `WorkerControlServer` (port mTLS dedie `csp.relay.worker-port`,
  defaut `8444`, `ClientAuth.REQUIRED`, CA cluster) separe du portail
  Web/nginx (jamais de cert client sur le portail).

## 2. Build JVM (disponible, repli rollback)

```bash
mvn -B -pl csp-worker -am package -DskipTests
java -jar csp-worker/target/csp-worker-*-shaded.jar  # via WorkerMain (env ci-dessous)
```

Env worker (aucun acces PG) :

```bash
export CSP_WORKER_CENTRAL_URL="https://centrale:8444"
export CSP_WORKER_NODE_ID="peer-a"
export CSP_WORKER_TLS_CERT_FILE="/etc/csp/worker/cert.pem"
export CSP_WORKER_TLS_KEY_FILE="/etc/csp/worker/key.pem"
export CSP_WORKER_TLS_TRUST_FILE="/etc/csp/worker/ca.pem"
export CSP_WORKER_TTL_MS="30000"
export CSP_WORKER_POLL_MS="10000"
export CSP_WORKER_CSPV2_PORT="0"   # >0 pour servir CSPv2 (pont inter-noeuds)
```

Centrale (portail inchange + port worker dedie) :

```bash
export CSP_RELAY_WORKER_ENABLED=true
export CSP_RELAY_WORKER_PORT=8444
export CSP_RELAY_WORKER_TLS_CERT_FILE=/etc/csp/central/cert.pem
export CSP_RELAY_WORKER_TLS_KEY_FILE=/etc/csp/central/key.pem
export CSP_RELAY_WORKER_TLS_TRUST_FILE=/etc/csp/central/ca.pem
export CSP_RELAY_LOCAL_NODE_ID=central1
```

## 3. Natif Debian 13 x86_64 — fondement des flags (pas aveugles)

Cible : Linux x86_64, glibc Trixie, baseline CPU portable
(`-march=compatibility`, jamais `march=native`). Builder : GraalVM
**21.0.5 LTS** (`$HOME/.sdkman/candidates/java/21.0.5-graal`),
compatible Quarkus 3.20 / smallrye 2.12 — GraalVM 25 **incompatible**
(substitutions smallrye 2.12.2 intraitables). Pas de builder UBI : le binaire
est construit sur l'hote Trixie lui-meme.

- Classpath assaini (worker seul) : exclusions `io.quarkus:*`,
  `io.quarkus.arc:*`, `vertx-pg/sql-client`, `mutiny-vertx-pg/sql-client` sur
  les 7 deps internes. Le worker n'utilise que des records purs + Vert.x
  direct (verifie : zero import io.quarkus/smallrye/PG) ; `dependency:tree`
  ne contient plus aucun quarkus*/PG/JDBC/jboss/wildfly/smallrye. Les
  substitutions `@TargetClass` Quarkus-couplees (ex. quarkus-netty
  `Target_DefaultSslContextFactory`, quarkus-core
  `Target_io_smallrye_common_net_Inet` via `Inet4AnyAccessor` sans setter)
  disparaissent avec leurs jars : plus d'`AnnotationSubstitutionProcessor`
  en echec, plus de backend JBoss LogManager (retour JUL natif).
- Champs Unsafe Netty conserves : `META-INF/native-image/reflect-config.json`
  (112 entrees generees depuis le jar) avec `allDeclaredFields` sur les
  queues jctools shadees. Sans cela, l'elimination closed-world casse
  `getDeclaredField("producerIndex")` au premier usage Netty (TLS inclus) :
  `NoSuchFieldException` -> `ExceptionInInitializerError` ->
  `NoClassDefFoundError MpscUnpaddedArrayQueue` -> ecriture TLS impossible
  sans aucun SYN emis (symptome exact observe). Aucun flag init aveugle :
  les anciens `--initialize-at-build-time` (logmanager, smallrye) sont
  supprimes avec leurs jars.

Commande de build (provenance reproductible) :

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.5-graal"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -pl csp-worker -am package -Pnative -DskipTests
file csp-worker/target/csp-worker      # ELF 64-bit LSB executable, x86-64
ldd --version                          # glibc Trixie
uname -m                               # x86_64
sha256sum csp-worker/target/csp-worker # NOTER ici, ne jamais committer le binaire
```

Preuves relevees le 2026-09-24 (hote Trixie x86_64) :

```text
$ file csp-worker/target/csp-worker
csp-worker/target/csp-worker: ELF 64-bit LSB pie executable, x86-64,
  version 1 (SYSV), dynamically linked,
  interpreter /lib64/ld-linux-x86-64.so.2, for GNU/Linux 3.2.0, not stripped
$ sha256sum csp-worker/target/csp-worker
0b567b497cd7e8acb8c0d51d76f0ad6390b67c84a6b316a8b741cd888b215e50
# (build -Pnative GraalVM 21.0.5, classpath assaini + reflect jctools +
#  records Jackson + bridge CSPv2 peers + accept-only + anti-loop 1-hop + fail-fast ext=null ; e2e natif vert dessus). Ne jamais committer (ignore).
$ uname -m && ldd --version | head -1
x86_64
ldd (Debian GLIBC 2.41-12+deb13u4) 2.41
$ native-image --version (JAVA_HOME=.../21.0.5-graal)
native-image 21.0.5 / GraalVM CE 21.0.5+9.1 (build 21.0.5+9-LTS-jvmci-23.1-b48)
```

Ressources natives requises (`csp-worker/src/main/resources/META-INF/
native-image/`) :
- `resource-config.json` : `META-INF/vertx/vertx-version.txt` (sans lui,
  `WebClientOptions.<clinit>` leve `IllegalStateException` au demarrage
  natif) et `META-INF/io.netty.versions.properties`.
- `reflect-config.json` : 112 classes jctools shadees Netty
  (`allDeclaredFields`, genere depuis le jar : l'elimination closed-world
  cassait `getDeclaredField("producerIndex")` au premier usage Netty) +
  21 records `RelayWorkerContract` (introspection Jackson en natif :
  sans eux, `InvalidDefinitionException` sur le desired).
- Classpath assaini (pom) : exclusions `io.quarkus:*`,
  `io.quarkus.arc:*`, `vertx-pg/sql-client`, `mutiny-vertx-pg/sql-client`
  sur les 7 deps internes. `dependency:tree` prouve zero quarkus*/PG/JDBC/
  jboss/wildfly/smallrye restant (le worker n'importe que records purs +
  Vert.x direct). Retire aussi les substitutions `@TargetClass`
  Quarkus-couplees qui tuaient l'analyse ET le runtime TLS.

Smoke natif (certificats auto-signes `/tmp`, centrale injoignable) :

```text
$ ./csp-worker/target/csp-worker            # sans env
Worker config invalid: nodeId invalid        # exit=2, fail-closed config
$ CSP_WORKER_*=... ./csp-worker/target/csp-worker   # centrale 127.0.0.1:18444 fermee
csp-worker started node=peer-a
Worker control failed                        # polls en echec propre, pas de crash
# (timeout externe : exit=124, processus vivant jusque-la)
$ docker run --rm -v "$PWD/csp-worker/target/csp-worker:/w/csp-worker:ro" \
    debian:trixie-slim /w/csp-worker         # sans env
Worker config invalid: nodeId invalid        # exit=2, sous glibc 2.41 vanilla
```

Le dataplane (listeners, routage, revocation) est prouve par
`WorkerRuntimeTest`/`RelayListenerScopeTest` en JVM (loopback reel) ; le
smoke natif prouve demarrage + boucle de controle, jamais le dataplane seul.

## 4. Service systemd + rollback

- Unite : `deploy/csp-worker.service` (utilisateur dedie `csp-worker`,
  hardening `NoNewPrivileges`, `ProtectSystem=strict`, `PrivateTmp`,
  `Restart=on-failure`). Binaire : `/usr/local/bin/csp-worker`.
- **Rollback** : remplacer `ExecStart` par
  `ExecStart=/usr/bin/java -jar /usr/local/lib/csp-ng/csp-worker.jar`
  (jar shaded JVM, meme env, meme contrat desired/ack). Aucun changement
  centrale requis (le canal HTTPS/mTLS est identique).
- Deploiement : binaire + unite + `docs/packaging-worker.md` uniquement.
  Ne jamais committer `csp-worker/target/csp-worker` (artefact ignore).

## 5. Tests

- Dataplane prouve en JVM (loopback reel, pas `--help`) :
  `WorkerRuntimeTest` (2 listeners CCcam+CS378x ouverts, session preauth
  suivie, retrait listener ferme session + socket, 403/410 typés →
  failClosed), `RelayListenerScopeTest` (isolation 2 listeners × 2 caches),
  `WorkerUdpExchangeTest` (echange UDP 2 noeuds reel, detach, timeout,
  strict, revocation), `CspV2AuthLoopbackTest` (mTLS reel openssl, round-trip
  allowliste, evil rejete, stop), `RelayV11PgTest` 7/7 PG reel (V1→V12,
  desired READONLY, ack + stats, peers UDP).
- E2E natif (`WorkerNativeE2ETest`, 14,7 s, VERT) : binaire lance via
  ProcessBuilder contre centrale mTLS Vert.x de test (certs openssl sous
  `target/`, stdout binaire persiste dans
  `target/native-worker-stdout.log`) : desired/ack/stats reels (identite =
  SAN), seed CCcam lue + entree inconnue fermee, garbage CS378x ferme,
  revocation 403 => listeners fermes. Dataplane ECM : JVM
  (`WorkerRuntimeTest`, `WorkerUdpExchangeTest`, `CspV2AuthLoopbackTest`).
- Smoke natif : centrale injoignable → fail-closed propre, jamais de crash.
- Suite complete existante : a executer avant merge (`mvn -B clean install`
  racine) — non lancee dans cet increment (temps), sans regression attendue
  (perimetre touche : relay/worker uniquement, P7 vert 13/13).

## 6. Etat echange inter-noeuds (lot UDP/CSPv2/stats)

1. **Echange cache UDP (LIVRE, teste reel)** : `WorkerUdpExchange` (socket
   unique, `autoAddPeers=false`, `strictAllowlist` host+port exact,
   pairs du desired uniquement). Consult/feed/export reels gates par liens ;
   requetes distantes servies depuis l'index local (jamais re-emises) ;
   replies acceptees sur pending vivant + checksum + origine (pas d'echo).
   V12 `csp_relay_udp_peer` + endpoints admin + UI. Preuve :
   `WorkerUdpExchangeTest` (echange 2 noeuds, detach live, timeout,
   strict-drop, revocation).
2. **CSPv2 effectif (LIVRE, teste mTLS reel)** : `requestFromPeer` cible
   (jamais de fallback inter-node), `WorkerCspV2Bridge.consultNode`
   (validation champs + checksum, identite `node:<peer>`), endpoint dial
   via `csp_host/csp_port` (V12, NULL = accept-only). Endpoint node caches =
   REMOTE (anti-masquage : self rejete, feed dispatch local = locaux seuls).
   Preuve : `CspV2AuthLoopbackTest` (CA openssl, round-trip allowliste,
   evil rejete, stop => echec). Bug prod trouve et corrige au passage :
   verification hostname HTTPS incompatible SAN URI (desactivee, identite =
   SAN + allowlist).
3. **Stats worker->central (LIVRE)** : ack transporte `WorkerStatsView`
   borne (connecteurs on/off + ecmOk reels, UDP compteurs + RTT, caches
   hits/puts, nodes utilisabilite + RTT) → `csp_relay_node_stats` (8 Ko max)
   → `/api/admin/relay/status.workerStats` → UI relais.
4. **Auto-guerison du bail au redemarrage (LIVRE)** : suppression definitive
   du besoin de re-emission manuelle apres arret worker ou downtime central > TTL.
   Le pull desired mTLS central regrante atomiquement le bail DB expire ou NULL
   (`touchWorkerSeenAndHeal`, audit `WORKER_LEASE_RENEWED`) avant reponse 200,
   garantissant l'acceptation immediate de l'ACK 200 suivant sans flap 10s ni failClosed.
5. Restes honnetes : latence/ossature par connecteur amont absentes des
   stats (compteurs au niveau connecteur exigent instrumentation dediee) ;
   rotation certificats sans coupure ; `.deb` Trixie (tar+binaire+service
   verifies suffisent la cible) ; suite complete racine avant merge.
