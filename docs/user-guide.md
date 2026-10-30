# Guide Utilisateur Complet — CardServProxy NG (CSP-NG)

> 🇬🇧 **English version available here:** [docs/user-guide.en.md](user-guide.en.md)

Bienvenue dans le guide pas-à-pas de **CSP-NG**. Ce document est conçu pour permettre à n'importe quel utilisateur — même sans connaissance de Java ou de Quarkus — d'installer, démarrer et configurer entièrement son serveur **CSP-NG** et ses **Workers Relais** via l'interface Web d'administration.

---

## Sommaire

1. [Comprendre CSP-NG en 1 minute](#1-comprendre-csp-ng-en-1-minute)
2. [Installation rapide en 5 minutes (Serveur Central)](#2-installation-rapide-en-5-minutes-serveur-central)
3. [Première connexion à la Console Web (`/admin`)](#3-première-connexion-à-la-console-web-admin)
4. [Configuration pas-à-pas dans l'interface Web](#4-configuration-pas-à-pas-dans-linterface-web)
   - [Étape A : Créer ou vérifier un Profil (CAID / Providers)](#étape-a--créer-ou-vérifier-un-profil-caid--providers)
   - [Étape B : Ajouter vos sources amont (Connecteurs CWS ou Import `cccam.cfg`)](#étape-b--ajouter-vos-sources-amont-connecteurs-cws-ou-import-cccamcfg)
   - [Étape C : Ouvrir des ports pour vos clients (Listeners entrants)](#étape-c--ouvrir-des-ports-pour-vos-clients-listeners-entrants)
   - [Étape D : Créer vos comptes clients (Utilisateurs)](#étape-d--créer-vos-comptes-clients-utilisateurs)
   - [Étape E : Publier les cartes dans le Catalogue (si mode manuel)](#étape-e--publier-les-cartes-dans-le-catalogue-si-mode-manuel)
5. [Échange de Cache (CacheEx & Cluster UDP)](#5-échange-de-cache-cacheex--cluster-udp)
6. [Protection Anti-Poisoning : Qualité DCW, Quorum & Cycle CW](#6-protection-anti-poisoning--qualité-dcw-quorum--cycle-cw)
7. [Utiliser un Proxy SOCKS5 sur vos connecteurs](#7-utiliser-un-proxy-socks5-sur-vos-connecteurs)
8. [Installer un Worker Relais Natif sur un VPS distant (sans Java)](#8-installer-un-worker-relais-natif-sur-un-vps-distant-sans-java)
9. [Visionneuse de Logs en temps réel & Noms de chaînes (`oscam.srvid`)](#9-visionneuse-de-logs-en-temps-réel--noms-de-chaînes-oscamsrvid)
10. [Mise en production sécurisée (systemd + Nginx HTTPS)](#10-mise-en-production-sécurisée-systemd--nginx-https)
11. [Questions fréquentes & Dépannage (FAQ)](#11-questions-fréquentes--dépannage-faq)

---

## 1. Comprendre CSP-NG en 1 minute

**CSP-NG** se place entre vos **sources amont** (serveurs OSCam, MultiCS, CSP, cartes locales) et vos **clients / pairs** :

```text
[ Sources Amont : OSCam / CCcam / CS378x / Newcamd / Radegast ]
                             │
                             ▼ (Connecteurs sortants + Proxy SOCKS5 optionnel)
                ┌──────────────────────────┐
                │         CSP-NG           │◄───► [ Pairs CacheEx / Cluster UDP ]
                │  Cache L1 + Filtres DCW  │
                │  + Console Web (/admin)  │◄───► [ Workers Relais Natifs (mTLS) ]
                └──────────────────────────┘
                             │
                             ▼ (Listeners entrants : Newcamd, CCcam, CS378x, Radegast)
                    [ Vos Utilisateurs ]
```

- **Toute la configuration est stockée dans PostgreSQL** : plus besoin d'éditer des fichiers XML à la main ni de redémarrer le serveur à chaque ajout d'utilisateur ou de connecteur.
- **Sécurité des mots de passe et clés DES** : CSP-NG ne stocke jamais vos secrets critiques en clair dans des fichiers versionnés. Depuis l'interface Web, lorsque vous saisissez un mot de passe ou une clé DES, CSP-NG le matérialise automatiquement dans un fichier protégé (`/etc/csp/secrets/...`) ou lit une variable d'environnement (`env:MA_VARIABLE`).

---

## 2. Installation rapide en 5 minutes (Serveur Central)

### Option A — Installation 100 % automatique en 1 commande (Recommandé)

Sur un serveur Linux vierge (Debian 11/12/13, Ubuntu 20.04/22.04/24.04+, RHEL/Rocky 9+), exécutez simplement :

```bash
curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash
```

Le script affiche un menu interactif prenant en charge **toutes les variantes** :
1. **Serveur Central Complet** : installe PostgreSQL + Java 21, crée la base `csp`, vérifie les sommes SHA-256, exécute le bootstrap initial en 2 phases (désactivation automatique du listener d'exemple sur le port `15000`), configure `systemd`, **Nginx HTTPS** (au choix : certificat auto-signé sur IP, certificat Let's Encrypt sur domaine, ou loopback seul) et **Fail2ban**, puis affiche vos identifiants `admin` à l'écran.
2. **Worker Relais Natif (sans Java)** : détecte automatiquement votre architecture (`x86_64` ou `arm64`) et votre version `glibc` (`2.31`, `2.36` ou `2.41`), installe le binaire et le service `csp-worker`.
3. **Worker Relais JVM** : installe Java 21 et le `csp-worker-v1.0.3-shaded.jar`.
4. **Générateur PKI mTLS** : génère l'Autorité de Certification (`ca.pem`), le certificat central et l'archive cliente prête à copier sur vos workers.
5. **Mise à jour sans coupure (`--mode upgrade`)** :
   Met à jour votre Serveur Central (`/opt/csp/current` + configuration Nginx) ou votre Worker Relais vers la dernière version sans toucher à votre base PostgreSQL ni à vos secrets :
   ```bash
   curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode upgrade
   ```
6. **Récupération / Réinitialisation du mot de passe `admin` (`--mode reset-admin`)** :
   Lors de l'installation, vos identifiants sont automatiquement sauvegardés dans `/root/csp-admin-credentials.txt`. Si vous souhaitez réinitialiser le mot de passe `admin` à tout moment :
   ```bash
   curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode reset-admin
   ```
7. **Désinstallation propre (`--mode uninstall`)**.

---

### Option B — Installation manuelle pas-à-pas

#### Prérequis sur votre serveur Linux (Debian 12/13 ou Ubuntu 22.04/24.04)
Vous avez uniquement besoin de **Java 21** et **PostgreSQL** :

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless postgresql curl tar
```

### 1. Créer la base de données PostgreSQL
Exécutez cette commande pour créer l'utilisateur `csp` et la base `csp` (remplacez `MonMotDePasseDB` par le mot de passe de votre choix) :

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE csp WITH LOGIN PASSWORD 'MonMotDePasseDB';
CREATE DATABASE csp WITH OWNER csp ENCODING 'UTF8';
\c csp
ALTER SCHEMA public OWNER TO csp;
GRANT ALL ON SCHEMA public TO csp;
SQL
```

### 2. Télécharger la dernière Release de CSP-NG
Vous n'avez **pas besoin de compiler le code source** : téléchargez directement l'archive pré-compilée depuis GitHub Releases :

```bash
sudo mkdir -p /opt/csp /etc/csp/secrets/imported /var/lib/csp
cd /opt/csp

curl -LO https://github.com/ucefmath/CSP-NG/releases/download/v1.0.3/csp-app-v1.0.3-quarkus-app.tar.gz
tar -xzf csp-app-v1.0.3-quarkus-app.tar.gz
```

### 3. Premier lancement (Mode Test / Rapide)
Pour un premier démarrage immédiat et la création automatique de votre compte administrateur Web :

```bash
export CSP_DB_HOST="127.0.0.1"
export CSP_DB_PORT="5432"
export CSP_DB_NAME="csp"
export CSP_DB_USER="csp"
export CSP_DB_PASSWORD="MonMotDePasseDB"
export CSP_DB_SSL_MODE="disable"

# Compte administrateur Web créé au 1er démarrage :
export CSP_BOOTSTRAP_ADMIN_USER="admin"
export CSP_BOOTSTRAP_ADMIN_PASSWORD="ChangeMe123!"

# On démarre d'abord avec le runtime métier désactivé le temps de configurer dans l'interface Web :
export CSP_RUNTIME_ENABLED="false"

# Autoriser l'accès HTTP local sans HTTPS pour ce premier test (sinon utilisez un reverse-proxy HTTPS) :
java -Dcsp.admin.require-https=false -Dquarkus.http.host=0.0.0.0 \
     -jar /opt/csp/csp-app-v1.0.3/quarkus-app/quarkus-run.jar
```

> 💡 **Pourquoi `CSP_RUNTIME_ENABLED="false"` au tout premier démarrage ?**
> Au premier lancement, CSP-NG crée les tables et ajoute un profil d'exemple `default` avec un listener Newcamd sur le port `15000`. Démarrer avec `CSP_RUNTIME_ENABLED="false"` vous permet d'ouvrir tranquillement l'interface Web, de supprimer ou modifier ce listener d'exemple, puis d'activer le runtime (`CSP_RUNTIME_ENABLED="true"`).

---

## 3. Première connexion à la Console Web (`/admin`)

1. Ouvrez votre navigateur sur :
   ```text
   http://<IP_DE_VOTRE_SERVEUR>:8080/admin/login
   ```
2. Connectez-vous avec l'identifiant **`admin`** et le mot de passe défini dans `CSP_BOOTSTRAP_ADMIN_PASSWORD` (`ChangeMe123!`).
3. Vous arrivez sur la **Vue d'ensemble (`/admin/overview`)**.

> 🔒 **Important** : Une fois le compte `admin` créé en base au premier démarrage, vous pouvez retirer les variables `CSP_BOOTSTRAP_ADMIN_USER` et `CSP_BOOTSTRAP_ADMIN_PASSWORD`.

---

## 4. Configuration pas-à-pas dans l'interface Web

Pour faire fonctionner votre proxy de bout en bout, suivez ces **5 étapes simples** dans le menu de gauche :

### Étape A : Créer ou vérifier un Profil (CAID / Providers)
Menu : **Configuration → Profils** (`/admin/profiles`)
- Un **Profil** représente un bouquet ou un système de cryptage (par exemple `srg-0500`, `nagra-1810`, `seca-0100`).
- Cliquez sur **Nouveau profil** :
  - **Nom** : ex. `viaccess`
  - **CAID** : ex. `0500` (en hexadécimal)
  - **Providers** : ex. `030B00,042820` (ou laissez vide / `000000` selon le système)
  - **Activé** : Cochez la case puis enregistrez.

### Étape B : Ajouter vos sources amont (Connecteurs CWS ou Import `cccam.cfg`)
Menu : **Configuration → Connecteurs** (`/admin/connectors`)
Vous avez deux façons d'ajouter vos lignes amont (readers) :

1. **Ajout manuel d'un connecteur** :
   - Cliquez sur **Nouveau connecteur**.
   - Choisissez le **Protocole** :
     - `newcamd` (serveur Newcamd / OSCam : nécessite une clé DES de 14 octets / 28 caractères hexadécimaux, ex. `0102030405060708091011121314`).
     - `cccam` (ligne C: vers un serveur CCcam / OSCam / MultiCS).
     - `cs378x` (camd35 TCP vers OSCam / MultiCS).
     - `radegast` (serveur Radegast).
   - Renseignez l'**Hôte**, le **Port**, l'**Utilisateur** et le **Mot de passe** (utilisez le bouton *Matérialiser* dans le champ secret pour que CSP-NG enregistre le mot de passe en toute sécurité dans `/etc/csp/secrets`).
   - Associez le connecteur à votre **Profil** (ou activez la découverte automatique des cartes pour CCcam).

2. **Import automatique d'un fichier `cccam.cfg`** :
   - Vous pouvez importer un bloc de lignes `C:` directement pour créer et tester automatiquement les connecteurs correspondants.

### Étape C : Ouvrir des ports pour vos clients (Listeners entrants)
Menu : **Configuration → Listeners** (`/admin/listeners`)
Un **Listener** est un port d'écoute ouvert sur votre serveur CSP-NG sur lequel vos clients (récepteurs DVB, OSCam, MultiCS) vont se connecter :

- Cliquez sur **Nouveau listener** :
  - **Protocole** :
    - `newcamd` : lié à un profil précis. Nécessite une clé DES.
      - *Dialecte Newcamd* : choisissez `legacy` pour les clients CSP classiques, ou `oscam-ncd525` pour une compatibilité native avec les serveurs/clients OSCam NCD525.
    - `cccam` : port CCcam 2.1.x global (peut servir plusieurs profils).
    - `cs378x` : port camd35 TCP global (ECM et/ou CacheEx).
    - `radegast` : port Radegast lié à un profil.
  - **Port** : ex. `15000` (Newcamd), `12000` (CCcam), `13780` (CS378x).
  - **Adresse d'écoute (Bind)** : `0.0.0.0` (toutes les interfaces).

### Étape D : Créer vos comptes clients (Utilisateurs)
Menu : **Relais & accès → Utilisateurs** (`/admin/users`)
- Cliquez sur **Nouvel utilisateur** :
  - **Nom d'utilisateur** et **Mot de passe**.
  - **Max connexions** : nombre de connexions simultanées autorisées (ex. `1`).
  - **Profils autorisés** : sélectionnez les profils auxquels cet utilisateur a accès (ou laissez tout autorisé selon votre politique).
  - **Activé** : cochez la case et validez.

### Étape E : Publier les cartes dans le Catalogue (si mode manuel)
Menu : **Configuration → Catalogue** (`/admin/catalog`)
- Pour les listeners globaux (CCcam / CS378x), vérifiez que les capacités `(CAID / Provider)` que vous souhaitez présenter à vos clients sont bien à l'état **`PUBLISHED` (Publié)** ou que votre listener est configuré en découverte automatique.

---

## 5. Échange de Cache (CacheEx & Cluster UDP)

CSP-NG supporte deux grandes familles d'échange de cache :

### A. CacheEx (CCcam & CS378x — Modes 1, 2 et 3)
- Se configure directement sur vos **Connecteurs** (sortants) ou sur vos **Utilisateurs / Listeners** (entrants).
- Suivez vos performances et le nombre de hits/push en temps réel dans **Pilotage → Stats CacheEx** (`/admin/cacheex`).

### B. Cluster UDP Legacy (`ClusteredCache`)
Menu : **Configuration → Cluster UDP** (`/admin/cluster`)
- Permet d'échanger le cache en UDP avec d'anciens serveurs **CSP** ou avec **OSCam** (section `[csp]` / `CS_CACHEEX`).
- Ajoutez simplement l'IP et le port UDP de vos pairs statiques.

---

## 6. Protection Anti-Poisoning : Qualité DCW, Quorum & Cycle CW

Pour éviter les freezes causés par de fausses clés (faux CW / cache poisoning) envoyées par des pairs douteux :

1. **Qualité DCW (`Pilotage → Qualité DCW` — `/admin/dcw`)** :
   - Vérifie automatiquement les **4 octets de checksum DVB** de chaque CW.
   - Permet de bloquer des CW spécifiques corrompus ou d'activer la réparation de checksum par `(Profil, CAID, Provider, SID)`.
2. **Qualité des Pairs & Quorum (`Pilotage → Stats CacheEx`)** :
   - Permet d'exiger un **Quorum** (ex. 2 pairs d'origine distincts ayant le même CW) lorsqu'un CW est contesté.
   - Met automatiquement en **quarantaine** les pairs qui envoient des CW invalides ou qui floodent (avec bouton de libération manuelle).
3. **Cycle CW (`Relais & accès → Cycle CW` — `/admin/cw-cycle`)** :
   - Équivalent du `CW_CYCLE_CHECK` d'OSCam : vérifie la continuité d'alternance des parités pair/impair (`0x80` / `0x81`) sur les CAIDs configurés.

---

## 7. Utiliser un Proxy SOCKS5 sur vos connecteurs

Si vous souhaitez masquer l'adresse IP de votre serveur CSP-NG vis-à-vis d'un fournisseur amont :

1. Allez dans **Configuration → Proxys** (`/admin/proxys`) et créez un proxy SOCKS5 (Hôte, Port, et identifiants optionnels).
2. Allez dans **Configuration → Connecteurs** (`/admin/connectors`), éditez le connecteur souhaité et sélectionnez votre proxy dans le champ **Proxy SOCKS5**.
3. **Sécurité Fail-Closed** : si le proxy SOCKS5 tombe en panne, CSP-NG **ne se connectera jamais en direct** (votre vraie IP ne fuitera jamais).

---

## 8. Installer un Worker Relais Natif sur un VPS distant (sans Java)

Vous pouvez installer de petits serveurs relais (`csp-worker`) sur des VPS distants pour rapprocher les ports d'écoute de vos clients ou répartir la charge, **sans installer ni Java ni PostgreSQL** sur ces VPS :

1. Sur le VPS distant, téléchargez le binaire natif correspondant à votre système depuis la page [GitHub Releases](https://github.com/ucefmath/CSP-NG/releases/tag/v1.0.3) :
   ```bash
   # Exemple pour un VPS Linux x86_64 (Debian 11, 12, 13 ou Ubuntu 20.04, 22.04, 24.04) :
   curl -LO https://github.com/ucefmath/CSP-NG/releases/download/v1.0.3/csp-worker-v1.0.3-linux-x86_64-glibc2.31.tar.gz
   tar -xzf csp-worker-v1.0.3-linux-x86_64-glibc2.31.tar.gz
   sudo install -m 0755 csp-worker-v1.0.3-linux-x86_64-glibc2.31/csp-worker /usr/local/bin/csp-worker
   ```
2. Copiez vos certificats mTLS dans `/etc/csp/worker/` (`cert.pem`, `key.pem`, `ca.pem`) et installez le service systemd fourni (`csp-worker.service`).
3. Dans l'interface Web du serveur central (**Relais & accès → Relais** — `/admin/relais`), déclarez le nœud worker (`nodeId`) et assignez-lui les Listeners et Connecteurs qu'il doit gérer. Le worker récupère automatiquement sa configuration toutes les 10 secondes !

---

## 9. Visionneuse de Logs en temps réel & Noms de chaînes (`oscam.srvid`)

Menu : **Pilotage → Logs** (`/admin/logs`)
- Affiche en direct les logs ECM et système du serveur central **ainsi que ceux de vos workers relais distants**.
- **Noms des chaînes en clair** : CSP-NG embarque par défaut un dictionnaire `oscam.srvid` (KingOfSat) et `oscam.provid`.
- Si vous souhaitez utiliser votre propre fichier `oscam.srvid` (formats classique ou `oscam.srvid2` supportés), définissez simplement la variable d'environnement :
  ```bash
  export CSP_SRVID_FILE="/etc/csp/oscam.srvid"
  export CSP_PROVIDERS_FILE="/etc/csp/oscam.provid"
  ```

---

## 10. Mise en production sécurisée (systemd + Nginx HTTPS)

Pour une installation de production permanente (démarrage automatique au boot, reverse-proxy HTTPS Let's Encrypt avec Nginx, et protection Fail2ban), suivez le guide opératoire complet inclus dans le dossier `deploy/` :
👉 **[Guide de déploiement en production (`deploy/README.md`)](../deploy/README.md)**

---

## 11. Questions fréquentes & Dépannage (FAQ)

- **J'obtiens l'erreur `TLS requis — utilisez HTTPS (426)` sur `/admin/login` :**
  En mode production (`QUARKUS_PROFILE=prod`), la console d'administration exige HTTPS par sécurité. Soit passez par Nginx en HTTPS (qui transmet l'en-tête `X-Forwarded-Proto: https`), soit pour un test en réseau local uniquement, ajoutez `-Dcsp.admin.require-https=false` au lancement de Java.
- **L'application s'arrête au tout premier lancement avec une erreur sur `CSP_PROFILE_DES_KEY` :**
  Au 1er lancement sur une base vierge, démarrez avec `CSP_RUNTIME_ENABLED=false`, connectez-vous à `/admin/listeners` pour désactiver ou reconfigurer le listener Newcamd d'exemple (port `15000`), puis repassez `CSP_RUNTIME_ENABLED=true`.
- **Quelle version de `csp-worker` choisir pour mon VPS ?**
  En cas de doute sur un serveur Intel/AMD 64 bits, prenez **`csp-worker-v1.0.3-linux-x86_64-glibc2.31.tar.gz`** : compilé sur Debian 11 (`glibc 2.31`), il fonctionne sur toutes les distributions récentes (Debian 11/12/13, Ubuntu 20.04/22.04/24.04, AlmaLinux/Rocky 9).
- **Une boîte de dialogue Basic Auth du navigateur apparaît sur `/admin/login` :**
  Si vous utilisez votre propre configuration Nginx personnalisée, ajoutez `proxy_hide_header WWW-Authenticate;` dans le bloc `server` Nginx (ou lancez `install.sh --mode upgrade` qui l'ajoute automatiquement) afin que le navigateur n'intercepte pas le `401` initial de vérification de session.
- **J'ai oublié le mot de passe `admin` de la console Web :**
  Consultez `/root/csp-admin-credentials.txt` sur votre serveur, ou régénérez un nouveau mot de passe immédiatement avec :
  ```bash
  curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode reset-admin
  ```
