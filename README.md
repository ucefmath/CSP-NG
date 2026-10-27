# CardServProxy NG (csp-ng)

CardServProxy NG est la réécriture moderne de CardServProxy (CSP) dans une stack réactive, performante et maintenable.

> **Écosystème — [M3U Bridge](https://m3ubridge.com)** : Unifiez vos sources M3U et Xtream derrière un endpoint unique stable — matching EPG automatique, enrichissement des métadonnées VOD, templates multi-comptes et failover automatique entre fournisseurs. **[Essai gratuit 30 jours →](https://m3ubridge.com)**

## 📖 Guides Utilisateur & Installation Automatique

### ⚡ Installation automatique en 1 commande (`install.sh`)

Lancez l'installeur interactif universel (détecte automatiquement votre distribution Linux, l'architecture `x86_64` / `arm64` et la version exacte de `glibc 2.31` / `2.36` / `2.41`) :

```bash
curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash
```

Ou directement en ligne de commande non-interactive selon la variante souhaitée :
- **Serveur Central + HTTPS auto-signé (accès par IP)** :
  ```bash
  curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode central --exposure self-signed -y
  ```
- **Serveur Central + HTTPS Let's Encrypt (nom de domaine)** :
  ```bash
  curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode central --exposure letsencrypt --domain csp.example.com --email admin@example.com -y
  ```
- **Worker Relais Natif GraalVM (sans Java, sélection auto `glibc 2.31/2.36/2.41` & `x86_64/arm64`)** :
  ```bash
  curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode worker-native --central-url https://1.2.3.4:8444 --node-id peer-a -y
  ```
- **Worker Relais JVM (`shaded.jar`)** :
  ```bash
  curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode worker-jvm --central-url https://1.2.3.4:8444 --node-id peer-a -y
  ```
- **Génération des certificats mTLS Centrale ↔ Workers** :
  ```bash
  curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode pki --node-id peer-a
  ```
- **Mise à jour sans perte de données (`--mode upgrade` — Serveur Central ou Worker)** :
  ```bash
  curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode upgrade
  ```
- **Réinitialisation ou récupération du mot de passe `admin` Web (`--mode reset-admin`)** :
  ```bash
  curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode reset-admin
  # (Les identifiants générés sont également sauvegardés dans /root/csp-admin-credentials.txt)
  ```

### 📚 Documentation pas-à-pas

- 🇫🇷 **[Guide Utilisateur Complet (Français)](docs/user-guide.md)**
- 🇬🇧 **[Complete User Guide (English)](docs/user-guide.en.md)**
- 📦 **[Télécharger les binaires pré-compilés (Release v1.0.0 — JARs & Workers Natifs Linux x86_64 / ARM64)](https://github.com/ucefmath/CSP-NG/releases/tag/v1.0.0)**

## Présentation

- **Technologie** : Java 21, Quarkus 3.20, Eclipse Vert.x, GraalVM Native Image (`csp-worker`).
- **Rôle** : Proxy de card-sharing et d'échange de cache 100 % réactif, sans I/O bloquantes ni verrous globaux sur l'event-loop.
- **Interopérabilité** : Compatibilité testée bit-à-bit avec le parc CardServProxy (CSP) legacy, **OSCam** et **MultiCS**.

## ✨ Fonctionnalités complètes (Features Overview)

### 1. Protocoles Entrants (Sessions Clients / Listeners)
- **Newcamd & Extended Newcamd** :
  - Multiplexage multi-CAID sur un port unique (Extended Newcamd) ou un port par profil.
  - Support des deux dialectes : **`legacy`** (CSP standard) et **`oscam-ncd525`** (`nc_des`).
  - Support des mots de passe en clair ou hachés en **`$1$` FreeBSD `md5-crypt`**.
- **CCcam 2.1.x** :
  - Trame ECM complète conforme OSCam, handshake miroir, scrambling CW, gestion du reshare et publication sélective du catalogue de cartes.
- **CS378x / camd35 TCP** :
  - Chiffrement AES-128, contrôle `ucrc`, requêtes ECM et échange CacheEx.
- **Radegast** :
  - Sessions entrantes légères liées à un profil.
- **Protection pré-authentification** :
  - Quotas de connexions pré-auth par IP, capacité globale bornée, listes d'autorisation CIDR (`allowList`) par listener et journalisation d'audit compatible **Fail2ban**.

### 2. Connecteurs Sortants (Sources Amont / CWS) & Proxys SOCKS5
- **Protocoles supportés** : **Newcamd**, **CCcam 2.1.x**, **CS378x (camd35 TCP)** et **Radegast**.
- **Routage par Proxy SOCKS5 (`csp_proxy`)** :
  - Association optionnelle de n'importe quel connecteur amont à un proxy SOCKS5 nommé (avec ou sans authentification).
  - Résolution DNS déléguée au proxy distant (`ATYP domain`) et politique **strictement *fail-closed*** (aucun repli en connexion directe si le proxy tombe : l'IP réelle du serveur n'est jamais exposée).
- **Importateur `cccam.cfg` intégré** :
  - Analyseur syntaxique de fichiers `cccam.cfg` avec sonde asynchrone (*probe*) des serveurs et rapport des couples `CAID/Provider`.
- **Découverte automatique & Catalogue de cartes (`/admin/catalog`)** :
  - Découverte dynamique des cartes annoncées par les connecteurs amont ou publication manuelle contrôlée (`PUBLISHED`).

### 3. Cache Multi-Niveaux & Échange de Cache (CacheEx / Cluster)
- **Cache L1 Local (`CacheVerticle` + Caffeine)** :
  - Architecture *single-writer* sans verrou, coalescence des requêtes ECM identiques en vol (*in-flight coalescing*) et vérification stricte de provenance.
- **CacheEx (Modes 1, 2 et 3)** sur **CCcam** et **CS378x** :
  - Filtrage par nombre de sauts (`cacheexMaxHop`), files d'envoi bornées (`cacheexMaxSend`), conservation de la parité et traçabilité du créateur initial via `node_id`.
- **Cluster UDP Legacy (`ClusteredCache`)** :
  - Implémentation 100 % compatible bit-à-bit avec CSP 0.9.0 et OSCam (`module-csp` / `CS_CACHEEX`), durcie contre l'usurpation et l'amplification UDP.
- **Protocole Inter-Nœuds CSPv2 (TCP + mTLS)** :
  - Transport TCP multiplexé moderne entre nœuds CSP-NG avec authentification mutuelle TLS obligatoire (`ClientAuth.REQUIRED`) et validation d'identité par certificat SAN URI (`urn:csp:node:<id>`).
- **Pont L3 Infinispan (Optionnel)** :
  - Réplication asynchrone `REPL_ASYNC` chiffrée via JGroups `SYM_ENCRYPT` (keystore JCEKS).

### 4. Qualité DCW, Anti-Poisoning, Quorum & Contrôle de Cycle CW
- **Moteur de Politique de Qualité DCW (`DcwQualityPolicy` — `/admin/dcw`)** :
  - Vérification complète des **4 triplets de checksum DVB** sur chaque CW.
  - Réparation scopée de checksum (`off`, `observe`, `repair`), contrôle des demi-CW nulles (`allowZeroHalf`), comptage de zéros et règles de blocage de mauvais CW par `(Profil, CAID, Provider, SID)`.
  - Détection automatique des liens inter-chaînes (`DcwFilter` / `detectLinks`) avec persistance JSON atomique.
- **Qualité des Pairs & Quorum CacheEx (`/admin/cacheex`)** :
  - Télémétrie détaillée par pair (CW valides, mauvais checksums, CW contestés, compteurs de flood).
  - **Quorum configurable** exigeant $N$ pairs d'origine distincts avant d'accepter un CW contesté.
  - **Quarantaine automatique** des pairs défaillants avec libération manuelle auditée en un clic.
- **Contrôle de Cycle CW (`CW_CYCLE_CHECK` — `/admin/cw-cycle`)** :
  - Validation de l'alternance pair/impair (`0x80` / `0x81`) par CAID (`maxList`, `keepTimeSec`, `onBad: drop|log`, `dropOld`, utilisation des métadonnées de cycle des pairs).

### 5. Workers Relais Natifs Headless (`csp-worker`)
- **Binaires natifs autonomes (GraalVM CE 21)** pour **Linux `x86_64`** et **Linux `arm64`** (déclinés en `glibc 2.31`, `glibc 2.36` et `glibc 2.41`) ainsi qu'en version **JAR JVM**.
- **Zéro base de données locale, zéro UI exposée** : chaque worker s'authentifie en mTLS auprès du serveur central (port dédié `8444`), récupère sa configuration cible (*desired state*), ouvre localement ses Listeners/Connecteurs/CacheEx/UDP/CSPv2 et remonte ses statistiques et journaux.
- **Auto-guérison du bail (Lease Self-Healing)** : reprise automatique sans coupure des sessions clientes après un redémarrage de la centrale.

### 6. Console Web d'Administration React 19 & Observabilité Temps Réel
- **Interface Web moderne (`/admin`)** remplaçant intégralement l'ancien protocole RMI et les pages XML :
  - Tableau de bord (`Overview`), gestion complète à chaud des **Profils**, **Listeners**, **Connecteurs**, **Proxys SOCKS5**, **Catalogue**, **Cluster UDP**, **Relais**, **Qualité DCW**, **Stats CacheEx**, **Cycle CW** et **Utilisateurs**.
- **Édition groupée atomique (Bulk Edit / Reset)** :
  - Modification ou réinitialisation simultanée de dizaines d'utilisateurs, connecteurs, profils ou listeners avec prévisualisation JSON du diff avant application.
- **Visionneuse de Logs Temps Réel (`/admin/logs`) & Noms de chaînes (`oscam.srvid`)** :
  - Terminal temps réel agrégeant les logs du serveur central et des workers relais distants (mode direct ou pause, filtrage par source et niveau).
  - Résolution native des noms de chaînes et providers via `oscam.srvid` (formats classique, legacy et `srvid2`) et `oscam.provid` (dictionnaires KingOfSat / OSCam embarqués par défaut).
- **Sécurité par construction** :
  - Hachage `bcrypt` des comptes administrateurs, protection CSRF (`X-CSP-Requested-With`), `Content-Security-Policy` stricte, anonymisation optionnelle des IP clientes et gestion des secrets exclusivement par référence (`env:` / `file:`).

## Architecture des modules

Le projet est découpé en plusieurs modules Maven, chacun ayant une responsabilité précise :

- `csp-domain` : Value objects immuables (records), aucune dépendance Quarkus/Vert.x.
- `csp-codec` : Codecs binaires (UDP cluster, Newcamd, Radegast).
- `csp-cache` : CacheVerticle (single-writer), cache L1 Caffeine.
- `csp-cluster-udp` : Implémentation du protocole `ClusteredCache` UDP (découverte, ping, partage de CW).
- `csp-connectors` : Connecteurs CWS vers les serveurs de cartes.
- `csp-sessions` : Sessions entrantes des clients.
- `csp-users` : Gestion des utilisateurs (PostgreSQL, In-Memory).
- `csp-filters` : Filtres CW/DCW.
- `csp-config` : Configuration dynamique et rechargement.
- `csp-web` : Interface d'administration web et API REST et ressources d'administration statiques.
- `csp-infinispan-bridge` : Bridge optionnel pour Infinispan.
- `csp-app` : Assemblage Quarkus, wiring CDI, point d'entrée de l'application.
- `csp-testkit` : Outils de tests, injecteurs et harnais binaire.

Pour plus de détails, consultez [docs/architecture.md](docs/architecture.md).

## Prérequis

- **Java 21**
- **Maven 3.9+**
- **Docker** (pour les tests d'interopérabilité UDP et les bases de données via Testcontainers)
- **PostgreSQL 15+** (en production)

## Build global

À la racine du projet, vous pouvez compiler le projet complet et exécuter tous les tests (y compris les tests d'intégration avec Testcontainers) :

```bash
CSP_DB_HOST=localhost CSP_DB_PORT=5433 CSP_DB_NAME=testdb CSP_DB_USER=testuser CSP_DB_PASSWORD=testpass CSP_DB_SSL_MODE=disable mvn clean install
```

Les variables de base de données sont utilisées par certains tests d'intégration pour valider la configuration PostgreSQL.

## Lancement

Pour lancer l'application en mode développement (avec rechargement à chaud Quarkus) :

```bash
mvn -pl csp-app quarkus:dev
```

En production, vous pouvez utiliser le jar compilé généré dans `csp-app/target/quarkus-app/quarkus-run.jar`.

## Feuille de route et fonctionnalités avancées

Les fonctionnalités avancées sont implémentées dans l'application. Consultez la [feuille de route (Roadmap)](docs/roadmap.md) pour les détails sur :
- Le nouveau protocole inter-proxy **CSPv2** (`csp-cluster-tcp`).
- L'API d'événements temps réel **REST/SSE** (`AdminEventBroker` / `/api/admin/events`).
- Le plugin avancé de filtrage **DcwFilterPlugin** (`csp-filters` / `DcwFilter`).

## Résolution des chaînes, logs ECM enrichis et visionneuse de logs

- **Résolution des noms de chaînes et de providers** : Support natif des fichiers `oscam.srvid` (formats canonique, ancien et srvid2) et `oscam.provid` avec fallback catch-all `(caid, 0x000000)`. Des dictionnaires par défaut sont embarqués (`/srvid/`), et surchargeables via `CSP_SRVID_FILE` / `CSP_PROVIDERS_FILE`.
- **Logs ECM enrichis** : Journalisation sous la catégorie JUL `com.bowman.cspng.ecm` (configurable via `quarkus.log.category."com.bowman.cspng.ecm".level=INFO`) avec identification en clair des chaînes, providers, connecteurs, origines et durées de traitement.
- **Visionneuse de logs temps réel** : Page `/admin/logs` dans l'interface Web avec agrégation multi-sources (serveur principal et workers relais distants), mode direct ou figé, coloration par niveau et contrôle de débit. Filtrage des préfixes autorisés via `CSP_LOGS_CAPTURE_PREFIXES` (défaut `com.bowman.cspng`), capacité ring buffer worker via `CSP_LOGS_MAX_WORKER_BUFFERS` (défaut `64`), et borne body ack worker via `CSP_RELAY_ACK_MAX_BODY` (défaut `524288`).

## Configuration PostgreSQL

CardServProxy NG utilise PostgreSQL (via `quarkus-reactive-pg-client`) pour stocker les utilisateurs et leurs droits.
La configuration de la base de données s'effectue via des variables d'environnement (ne jamais commiter de mot de passe dans les fichiers de configuration) :

```bash
export CSP_DB_HOST="localhost"
export CSP_DB_PORT="5432"
export CSP_DB_NAME="csp"
export CSP_DB_USER="csp"
export CSP_DB_PASSWORD="votre_mot_de_passe"
export CSP_DB_SSL_MODE="require"
```

Les migrations de schéma sont gérées automatiquement par Flyway au démarrage.

## Import CCcam et reporting

L'import de comptes depuis un fichier `cccam.cfg` est supporté via l'API REST. Il s'effectue via l'endpoint `POST /api/admin/connectors/import?profile=...` puis par polling sur `GET /api/admin/connectors/import/jobs/{jobId}`. Si des entrées du fichier requièrent des correspondances CAID/Provider non résolubles automatiquement, la réponse JSON du job contiendra la liste des `missingCaidProviders` pour vous permettre d'ajuster manuellement la configuration.

## Infinispan, Multicast et Sécurité

Le mode Infinispan embedded est **désactivé par défaut**. S'il est activé via configuration (L3 optionnel), il réplique uniquement les CW replies dans l'implémentation actuelle (l'opération `onRequest` est un no-op). Le cache configuré est `REPL_ASYNC` avec un TTL de 30s et utilise un codec binaire natif.

La découverte UDP multicast de ce cluster utilise un keystore JCEKS partagé, protégé par le mot de passe JGroups `SYM_ENCRYPT`, pour sécuriser les communications. L'application échouera au démarrage si le bridge est activé mais que le keystore est manquant.

Configuration via variables d'environnement :
- `CSP_INFINISPAN_CLUSTER`
- `CSP_INFINISPAN_BIND_ADDR`
- `CSP_INFINISPAN_BIND_PORT`
- `CSP_INFINISPAN_MCAST_ADDR`
- `CSP_INFINISPAN_MCAST_PORT`
- `CSP_INFINISPAN_KEYSTORE`
- `CSP_INFINISPAN_KEY_ALIAS`
- `CSP_INFINISPAN_KEYSTORE_PASSWORD`

## Sécurité, Reverse Proxy et TLS

En production, la terminaison TLS est assurée en frontal par **nginx** sur le même hôte (loopback `127.0.0.1`) :
- **Reverse Proxy** : nginx termine le HTTPS et redirige les requêtes en HTTP vers l'application via `proxy_pass http://127.0.0.1:8080` (ou port configuré via `CSP_HTTP_PORT`).
- **En-têtes transmis** : nginx doit impérativement transmettre les en-têtes `X-Forwarded-Proto: https` et `X-Forwarded-Host`.
- **Confinement & Confiance** : En production, Quarkus écoute exclusivement sur le loopback (`%prod.quarkus.http.host=127.0.0.1`) et restreint strictement les proxys de confiance (`trusted-proxies=127.0.0.1,::1` — ne jamais élargir à des adresses distantes).
- **Observabilité interne** : Les endpoints `/q/health` et `/q/metrics` sont confinés au réseau local et ne doivent jamais être exposés publiquement par nginx.

Configuration HTTP locale :
- `CSP_HTTP_PORT` : Port d'écoute HTTP local de l'application (défaut `8080`).

## Interopérabilité UDP Legacy / OSCam

Le module `csp-cluster-udp` implémente le protocole historique `ClusteredCache`. Cette implémentation a été testée et validée bit-à-bit contre le CSP Java d'origine et contre OSCam (module `CS_CACHEEX`). Aucune modification du protocole n'a été apportée pour garantir une compatibilité totale avec les installations existantes.

## Produit compagnon : M3U Bridge

Pour l'orchestration et la distribution de vos flux M3U / Xtream, découvrez **[M3U Bridge](https://m3ubridge.com)** — la passerelle technique conçue pour stabiliser et enrichir votre infrastructure de streaming :

- **Un seul endpoint stable** : placez vos sources M3U et Xtream derrière un endpoint compatible Xtream unique et remplacez vos fournisseurs amont sans reconfigurer les clients.
- **Priorités multi-sources & Failover automatique** : combinez plusieurs sources avec déduplication canonique, monitoring de santé et bascule automatique en cas d'indisponibilité.
- **Normalisation EPG & Métadonnées VOD** : matching EPG automatique, enrichissement des films/séries (affiches, synopsis, genres, notes) et templates réutilisables multi-comptes.
- **Domaines personnalisés & Nœuds dédiés (BYO)** : servez le trafic via votre propre domaine CNAME ou déportez la bande passante sur vos propres serveurs relais avec isolation cryptographique JWE.

👉 **[Découvrir M3U Bridge et démarrer l'essai gratuit de 30 jours sur m3ubridge.com](https://m3ubridge.com)**

## Licence et Attributions

Ce projet est distribué sous licence **GNU General Public License v3.0 (GPLv3)** — voir le fichier [LICENSE](LICENSE).

### Crédits et projets de référence

- **CardServProxy (CSP) original** par **Bowman** (*GPLv2*) : inspiration architecturale, protocole UDP `ClusteredCache`, dialectes Newcamd/Radegast, concepts `DcwFilterPlugin` / `TvService` et namespace historique `com.bowman.*`.
- **OSCam (Open Source Conditional Access Module)** par la **Streamboard Team** et ses contributeurs ([git.streamboard.tv/common/oscam](https://git.streamboard.tv/common/oscam), *GPLv3*) : référence d'interopérabilité protocolaire (CCcam 2.1.x, CS378x / camd35, CacheEx modes 1/2/3, `CW_CYCLE_CHECK`, dialecte Newcamd `oscam-ncd525` et formats `oscam.srvid` / `oscam.provid`), réimplémentée en Java idiomatique sans copie de code C.
- **tsdecrypt** par **Georgi Chorbadzhiyski** ([github.com/gfto/tsdecrypt](https://github.com/gfto/tsdecrypt), *GPLv2*) : référence technique pour la vérification du cadrage CS378x / camd35 TCP.
- **Algorithme MD5-Crypt (`$1$`)** par **Poul-Henning Kamp** (*FreeBSD Project*) : algorithme standard réimplémenté en Java pur pour l'authentification Newcamd et la compatibilité des comptes historiques.
- **Dictionnaires embarqués (`csp-app/src/main/resources/srvid/`)** :
  - `oscam.srvid` : généré à partir des données de [KingOfSat](https://fr.kingofsat.net/).
  - `oscam.provid` : issu de [gianni8127/OScam-Collection](https://github.com/gianni8127/OScam-Collection) et du [Wiki Streamboard OSCam](https://wiki.streamboard.tv/wiki/OSCam/en/Config/oscam.provid).

Pour le détail complet des attributions et des bibliothèques open-source utilisées, consultez le fichier [NOTICE](NOTICE).
