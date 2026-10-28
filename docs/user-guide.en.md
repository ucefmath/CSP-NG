# Complete User Guide — CardServProxy NG (CSP-NG)

> 🇫🇷 **Version française disponible ici :** [docs/user-guide.md](user-guide.md)

Welcome to the step-by-step user guide for **CSP-NG**. This guide is written so that any user — even with zero prior knowledge of Java or Quarkus — can install, start, and fully configure a **CSP-NG** central server and **Native Relay Workers** through the Web Admin Console.

---

## Table of Contents

1. [Understanding CSP-NG in 1 Minute](#1-understanding-csp-ng-in-1-minute)
2. [5-Minute Quick Start (Central Server)](#2-5-minute-quick-start-central-server)
3. [First Login to the Web Admin Console (`/admin`)](#3-first-login-to-the-web-admin-console-admin)
4. [Step-by-Step Configuration in the Web UI](#4-step-by-step-configuration-in-the-web-ui)
   - [Step A: Create or Check a Profile (CAID / Providers)](#step-a-create-or-check-a-profile-caid--providers)
   - [Step B: Add Upstream Sources (CWS Connectors or `cccam.cfg` Import)](#step-b-add-upstream-sources-cws-connectors-or-cccamcfg-import)
   - [Step C: Open Client Ports (Inbound Listeners)](#step-c-open-client-ports-inbound-listeners)
   - [Step D: Create Client Accounts (Users)](#step-d-create-client-accounts-users)
   - [Step E: Publish Cards in the Catalog (if using manual mode)](#step-e-publish-cards-in-the-catalog-if-using-manual-mode)
5. [Cache Exchange (CacheEx & Legacy UDP Cluster)](#5-cache-exchange-cacheex--legacy-udp-cluster)
6. [Anti-Poisoning Protection: DCW Quality, Quorum & CW Cycle](#6-anti-poisoning-protection-dcw-quality-quorum--cw-cycle)
7. [Routing Connectors Through a SOCKS5 Proxy](#7-routing-connectors-through-a-socks5-proxy)
8. [Deploying a Native Relay Worker on a Remote VPS (No Java Needed)](#8-deploying-a-native-relay-worker-on-a-remote-vps-no-java-needed)
9. [Real-Time Log Viewer & Channel Name Resolution (`oscam.srvid`)](#9-real-time-log-viewer--channel-name-resolution-oscamsrvid)
10. [Production Hardening (systemd + Nginx HTTPS)](#10-production-hardening-systemd--nginx-https)
11. [Troubleshooting & FAQ](#11-troubleshooting--faq)

---

## 1. Understanding CSP-NG in 1 Minute

**CSP-NG** sits between your **upstream sources** (OSCam, MultiCS, CSP servers, local readers) and your **clients / cache peers**:

```text
[ Upstream Sources: OSCam / CCcam / CS378x / Newcamd / Radegast ]
                             │
                             ▼ (Outbound Connectors + Optional SOCKS5 Proxy)
                ┌──────────────────────────┐
                │         CSP-NG           │◄───► [ CacheEx / UDP Cluster Peers ]
                │  L1 Cache + DCW Filters  │
                │  + Web Console (/admin)  │◄───► [ Native Relay Workers (mTLS) ]
                └──────────────────────────┘
                             │
                             ▼ (Inbound Listeners: Newcamd, CCcam, CS378x, Radegast)
                      [ Your Clients ]
```

- **100% Database-Driven Configuration (PostgreSQL)**: No more manual XML editing or full proxy restarts when adding users, profiles, or connectors.
- **Built-in Secret Protection**: Passwords and DES keys are never stored in cleartext config files. When you enter a password or DES key in the Web UI, CSP-NG safely materializes it in `/etc/csp/secrets/...` (or reads it from an `env:VAR` environment variable).

---

## 2. 5-Minute Quick Start (Central Server)

### Option A — 1-Command Automated Installer (Recommended)

On a fresh Linux server (Debian 11/12/13, Ubuntu 20.04/22.04/24.04+, RHEL/Rocky 9+), run:

```bash
curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash
```

The interactive installer supports **all deployment variants**:
1. **Full Central Server**: installs PostgreSQL + Java 21, provisions the `csp` database, verifies SHA-256 checksums, runs the 2-phase bootstrap automatically (disabling the sample port `15000` listener), configures `systemd`, **Nginx HTTPS** (Self-Signed IP, Let's Encrypt Domain, or Loopback only) and **Fail2ban**, and prints your generated `admin` credentials.
2. **Native Relay Worker (No JVM)**: auto-detects CPU architecture (`x86_64` or `arm64`) and host `glibc` (`2.31`, `2.36`, or `2.41`), and installs the `csp-worker` service.
3. **JVM Relay Worker**: installs Java 21 and `csp-worker-v1.0.1-shaded.jar`.
4. **mTLS PKI Generator**: generates the Cluster Root CA (`ca.pem`), central certificate, and ready-to-copy worker certificate bundles.
5. **Zero-Downtime Upgrade (`--mode upgrade`)**:
   Upgrades your Central Server (`/opt/csp/current` + Nginx config) or Relay Worker to the latest release without touching your PostgreSQL database or secrets:
   ```bash
   curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode upgrade
   ```
6. **Recover or Reset Web Admin Password (`--mode reset-admin`)**:
   Initial credentials are automatically saved in `/root/csp-admin-credentials.txt`. To reset the `admin` password at any time:
   ```bash
   curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode reset-admin
   ```
7. **Clean Uninstall (`--mode uninstall`)**.

---

### Option B — Manual Step-by-Step Installation

#### Prerequisites on Linux (Debian 12/13 or Ubuntu 22.04/24.04)
You only need **Java 21** and **PostgreSQL**:

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless postgresql curl tar
```

### 1. Create the PostgreSQL Database
Run the following command to create the `csp` user and database (replace `MyStrongDbPass` with your own password):

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE csp WITH LOGIN PASSWORD 'MyStrongDbPass';
CREATE DATABASE csp WITH OWNER csp ENCODING 'UTF8';
\c csp
ALTER SCHEMA public OWNER TO csp;
GRANT ALL ON SCHEMA public TO csp;
SQL
```

### 2. Download the Pre-Built CSP-NG Release
You **do not need to build from source** — download the ready-to-run release bundle directly from GitHub:

```bash
sudo mkdir -p /opt/csp /etc/csp/secrets/imported /var/lib/csp
cd /opt/csp

curl -LO https://github.com/ucefmath/CSP-NG/releases/download/v1.0.1/csp-app-v1.0.1-quarkus-app.tar.gz
tar -xzf csp-app-v1.0.1-quarkus-app.tar.gz
```

### 3. First Launch (Quick Evaluation Mode)
Start CSP-NG and automatically create your initial Web Admin account:

```bash
export CSP_DB_HOST="127.0.0.1"
export CSP_DB_PORT="5432"
export CSP_DB_NAME="csp"
export CSP_DB_USER="csp"
export CSP_DB_PASSWORD="MyStrongDbPass"
export CSP_DB_SSL_MODE="disable"

# Initial Web Admin account created on first boot:
export CSP_BOOTSTRAP_ADMIN_USER="admin"
export CSP_BOOTSTRAP_ADMIN_PASSWORD="ChangeMe123!"

# Start with runtime disabled on first boot so you can configure listeners in the Web UI first:
export CSP_RUNTIME_ENABLED="false"

# Allow plain HTTP for local testing (in production, use Nginx HTTPS):
java -Dcsp.admin.require-https=false -Dquarkus.http.host=0.0.0.0 \
     -jar /opt/csp/csp-app-v1.0.1/quarkus-app/quarkus-run.jar
```

> 💡 **Why set `CSP_RUNTIME_ENABLED="false"` on the very first boot?**
> On a fresh database, database migration V1 seeds a sample `default` profile with a Newcamd listener on port `15000`. Starting with `CSP_RUNTIME_ENABLED="false"` lets you log into the Web UI, disable or customize that default listener, and then set `CSP_RUNTIME_ENABLED="true"`.

---

## 3. First Login to the Web Admin Console (`/admin`)

1. Open your browser at:
   ```text
   http://<YOUR_SERVER_IP>:8080/admin/login
   ```
2. Log in with username **`admin`** and the password you set in `CSP_BOOTSTRAP_ADMIN_PASSWORD` (`ChangeMe123!`).
3. You will land on the **Overview (`/admin/overview`)** dashboard.

> 🔒 **Note**: Once the `admin` user has been created in PostgreSQL on first boot, you can remove `CSP_BOOTSTRAP_ADMIN_USER` and `CSP_BOOTSTRAP_ADMIN_PASSWORD` from your environment.

---

## 4. Step-by-Step Configuration in the Web UI

To set up your proxy from scratch, follow these **5 steps** in the left sidebar:

### Step A: Create or Check a Profile (CAID / Providers)
Menu: **Configuration → Profils** (`/admin/profiles`)
- A **Profile** represents a CAID / network (e.g. `viaccess-0500`, `nagra-1810`, `seca-0100`).
- Click **Nouveau profil**:
  - **Name**: e.g. `viaccess`
  - **CAID**: e.g. `0500` (hexadecimal)
  - **Providers**: e.g. `030B00,042820`
  - **Enabled**: Check the box and save.

### Step B: Add Upstream Sources (CWS Connectors or `cccam.cfg` Import)
Menu: **Configuration → Connecteurs** (`/admin/connectors`)
You can add upstream readers in two ways:

1. **Manual Connector Creation**:
   - Click **Nouveau connecteur**.
   - Select the **Protocol**:
     - `newcamd` (requires a 14-byte / 28-hex-character DES key, e.g. `0102030405060708091011121314`).
     - `cccam` (C-line to an OSCam / CCcam / MultiCS server).
     - `cs378x` (camd35 TCP to OSCam / MultiCS).
     - `radegast`.
   - Enter **Host**, **Port**, **Username**, and **Password** (click the *Matérialiser* button on the secret field to safely store the password in `/etc/csp/secrets`).
   - Assign it to your **Profile** (or enable automatic card discovery for CCcam).

2. **Bulk `cccam.cfg` Import**:
   - Paste or upload `C:` lines to automatically parse, probe, and create upstream connectors.

### Step C: Open Client Ports (Inbound Listeners)
Menu: **Configuration → Listeners** (`/admin/listeners`)
A **Listener** is a TCP port opened on CSP-NG for your downstream clients (STB receivers, OSCam, MultiCS):

- Click **Nouveau listener**:
  - **Protocol**:
    - `newcamd`: bound to a specific profile (or Extended Newcamd for multi-CAID). Supports both `legacy` CSP DES and `oscam-ncd525` dialect.
    - `cccam`: global CCcam 2.1.x listener.
    - `cs378x`: global camd35 TCP listener (ECM and/or CacheEx).
    - `radegast`: bound to a profile.
  - **Port**: e.g. `15000` (Newcamd), `12000` (CCcam), `13780` (CS378x).
  - **Bind Address**: `0.0.0.0`.

### Step D: Create Client Accounts (Users)
Menu: **Relais & accès → Utilisateurs** (`/admin/users`)
- Click **Nouvel utilisateur**:
  - Set **Username** and **Password**.
  - Set **Max Connections** (e.g. `1`).
  - Select allowed **Profiles** and enable the account.

### Step E: Publish Cards in the Catalog (if using manual mode)
Menu: **Configuration → Catalogue** (`/admin/catalog`)
- For multi-CAID listeners (CCcam / CS378x), make sure the `(CAID / Provider)` capabilities you want to advertise to downstream clients are set to **`PUBLISHED`** (or configure the listener in automatic discovery mode).

---

## 5. Cache Exchange (CacheEx & Legacy UDP Cluster)

CSP-NG supports both modern CacheEx and legacy CSP UDP clustering:

### A. CacheEx (CCcam & CS378x — Modes 1, 2 & 3)
- Configured directly on **Connectors** (outbound) or **Users / Listeners** (inbound).
- Monitor real-time hit rates, push counts, and peer health in **Pilotage → Stats CacheEx** (`/admin/cacheex`).

### B. Legacy UDP Cluster (`ClusteredCache`)
Menu: **Configuration → Cluster UDP** (`/admin/cluster`)
- 100% wire-compatible with legacy Java **CSP** and **OSCam** (`[csp]` / `CS_CACHEEX`).
- Add your local UDP port and your remote static UDP peers (`IP:port`).

---

## 6. Anti-Poisoning Protection: DCW Quality, Quorum & CW Cycle

To eliminate freezes caused by fake/corrupted CWs (cache poisoning):

1. **DCW Quality (`Pilotage → Qualité DCW` — `/admin/dcw`)**:
   - Validates all **4 DVB checksum triplets** on every CW.
   - Supports scoped checksum repair (`off`, `observe`, `repair`), zero-half controls (`allowZeroHalf`), and bad-CW blacklisting rules per `(Profile, CAID, Provider, SID)`.
2. **Peer Quality & Quorum (`Pilotage → Stats CacheEx`)**:
   - Configure a **Quorum** (e.g. requiring 2 distinct origin `node_id` peers) before accepting contested CacheEx pushes.
   - Automatically quarantines peers sending bad checksums or flooding (with one-click manual release in the UI).
3. **CW Cycle Check (`Relais & accès → Cycle CW` — `/admin/cw-cycle`)**:
   - Mirrors OSCam's `CW_CYCLE_CHECK`: enforces even/odd (`0x80` / `0x81`) CW cycle progression per CAID.

---

## 7. Routing Connectors Through a SOCKS5 Proxy

To hide your central server's IP address when connecting to an upstream provider:

1. Go to **Configuration → Proxys** (`/admin/proxys`) and create a named SOCKS5 proxy (Host, Port, optional credentials).
2. Edit any connector in **Configuration → Connecteurs** (`/admin/connectors`) and select that SOCKS5 proxy.
3. **Strict Fail-Closed Guarantee**: DNS resolution is delegated to the SOCKS5 proxy, and if the proxy goes down, CSP-NG will **never** fall back to a direct connection (your real server IP never leaks).

---

## 8. Deploying a Native Relay Worker on a Remote VPS (No Java Needed)

You can deploy lightweight headless edge relays (`csp-worker`) on remote VPS nodes to terminate client connections closer to users or distribute load — **without installing Java or PostgreSQL** on the edge VPS:

1. On the remote VPS, download the native binary matching your OS architecture from [GitHub Releases](https://github.com/ucefmath/CSP-NG/releases/tag/v1.0.1):
   ```bash
   # Universal Linux x86_64 binary (compatible with Debian 11/12/13 & Ubuntu 20.04/22.04/24.04+):
   curl -LO https://github.com/ucefmath/CSP-NG/releases/download/v1.0.1/csp-worker-v1.0.1-linux-x86_64-glibc2.31.tar.gz
   tar -xzf csp-worker-v1.0.1-linux-x86_64-glibc2.31.tar.gz
   sudo install -m 0755 csp-worker-v1.0.1-linux-x86_64-glibc2.31/csp-worker /usr/local/bin/csp-worker
   ```
2. Place your mTLS certificates (`cert.pem`, `key.pem`, `ca.pem`) in `/etc/csp/worker/` and enable the included `csp-worker.service` systemd unit.
3. In the Central Server Web UI (**Relais & accès → Relais** — `/admin/relais`), register the worker `nodeId` and assign Listeners and Connectors to it. The worker pulls its desired state over mTLS every 10 seconds automatically.

---

## 9. Real-Time Log Viewer & Channel Name Resolution (`oscam.srvid`)

Menu: **Pilotage → Logs** (`/admin/logs`)
- Streams live logs from both the central server and remote relay workers, with level filtering and pause/freeze.
- **Built-in Channel Name Resolution**: CSP-NG ships with default `oscam.srvid` (KingOfSat) and `oscam.provid` dictionaries.
- To use your own custom `oscam.srvid` (classic or `oscam.srvid2` format), set:
  ```bash
  export CSP_SRVID_FILE="/etc/csp/oscam.srvid"
  export CSP_PROVIDERS_FILE="/etc/csp/oscam.provid"
  ```

---

## 10. Production Hardening (systemd + Nginx HTTPS)

For a full production setup (systemd service, Nginx TLS reverse proxy with Let's Encrypt, and Fail2ban jails), follow the production deployment runbook in `deploy/`:
👉 **[Production Deployment Runbook (`deploy/README.md`)](../deploy/README.md)**

---

## 11. Troubleshooting & FAQ

- **I get `TLS requis — utilisez HTTPS (426)` on `/admin/login`:**
  In production profile (`QUARKUS_PROFILE=prod`), the Admin UI requires HTTPS. Either access it through Nginx HTTPS (which sets `X-Forwarded-Proto: https`) or, for local LAN testing only, pass `-Dcsp.admin.require-https=false` to Java.
- **The server exits on first boot complaining about `CSP_PROFILE_DES_KEY`:**
  On the first boot with a fresh database, start with `CSP_RUNTIME_ENABLED=false`, log into `/admin/listeners` to disable or update the default port `15000` Newcamd listener, then restart with `CSP_RUNTIME_ENABLED=true`.
- **Which native `csp-worker` archive should I download for my VPS?**
  When in doubt on 64-bit Intel/AMD Linux, choose **`csp-worker-v1.0.1-linux-x86_64-glibc2.31.tar.gz`**: built against `glibc 2.31` (Debian 11), it runs on all modern distributions (Debian 11/12/13, Ubuntu 20.04/22.04/24.04, RHEL/Rocky/Alma 9+).
- **My browser shows a native Basic Auth popup on `/admin/login`:**
  If you use a custom Nginx configuration, add `proxy_hide_header WWW-Authenticate;` inside your Nginx `server` block (or run `install.sh --mode upgrade` which adds it automatically) so the browser does not trigger a native popup on the initial session-check `401`.
- **I lost my Web Admin `admin` password:**
  Check `/root/csp-admin-credentials.txt` on the server, or generate a new password immediately with:
  ```bash
  curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash -s -- --mode reset-admin
  ```
