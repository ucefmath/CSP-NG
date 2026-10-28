#!/usr/bin/env bash
# =============================================================================
# CSP-NG (CardServProxy NG) — Universal Automated Installer
# Repository : https://github.com/ucefmath/CSP-NG
# License    : GNU General Public License v3.0 (GPLv3)
#
# Supported Modes & Variants:
#   1) central        : Central Server (PostgreSQL + Java 21 + systemd + Nginx)
#      - Packaging    : quarkus-app (standard) | runner-jar (standalone uber-jar)
#      - Web Exposure : letsencrypt (domain)   | self-signed (IP) | loopback
#      - Optional     : Fail2ban jail + mTLS CA initialization for relay workers
#   2) worker-native  : Headless Native Relay Worker (No Java, GraalVM binary)
#      - Arch         : linux-x86_64 (amd64) | linux-arm64 (aarch64)
#      - glibc target : auto-detected (glibc2.31 | glibc2.36 | glibc2.41) or forced
#   3) worker-jvm     : Headless JVM Relay Worker (Java 21 + shaded fat-jar)
#   4) pki            : Generate mTLS Cluster CA, Central cert & Worker bundle
#   5) upgrade        : Zero-downtime binary/release upgrade (Central or Worker)
#   6) uninstall      : Clean removal of services (with optional DB/data purge)
#
# Usage (Interactive Menu):
#   curl -fsSL https://raw.githubusercontent.com/ucefmath/CSP-NG/main/install.sh | sudo bash
#
# Usage (Non-Interactive CLI):
#   sudo ./install.sh --mode central --exposure self-signed
#   sudo ./install.sh --mode central --exposure letsencrypt --domain csp.example.com --email admin@example.com
#   sudo ./install.sh --mode worker-native --central-url https://1.2.3.4:8444 --node-id worker-eu1
#   sudo ./install.sh --mode worker-jvm --central-url https://1.2.3.4:8444 --node-id worker-eu1
#   sudo ./install.sh --mode pki --node-id worker-eu1
# =============================================================================
set -euo pipefail

# Avoid perl/apt locale warnings when SSH client forwards an uninstalled locale (e.g. fr_FR.utf8)
if locale -a 2>/dev/null | grep -qi '^C\.utf-\?8$'; then
  export LC_ALL=C.UTF-8 LANG=C.UTF-8 LANGUAGE=C.UTF-8
else
  export LC_ALL=C LANG=C LANGUAGE=C
fi

TMP_DL=""
cleanup_tmp() {
  if [ -n "${TMP_DL:-}" ] && [ -d "${TMP_DL:-}" ]; then
    rm -rf "$TMP_DL"
  fi
}
trap cleanup_tmp EXIT

GITHUB_REPO="ucefmath/CSP-NG"
CSP_VERSION="${CSP_VERSION:-v1.0.2}"

# Defaults for CLI flags
MODE=""
APP_VARIANT="quarkus-app"      # quarkus-app | runner-jar
EXPOSURE="self-signed"         # self-signed | letsencrypt | loopback
DOMAIN=""
EMAIL=""
ADMIN_USER="admin"
ADMIN_PASS=""
WITH_FAIL2BAN="ask"            # yes | no | ask
GLIBC_OVERRIDE=""              # glibc2.31 | glibc2.36 | glibc2.41 | "" (auto)
CENTRAL_URL=""
NODE_ID=""
CSPV2_PORT="0"
WORKER_CERT_BUNDLE=""
NON_INTERACTIVE=0

# Colors
if [ -t 1 ]; then
  C_RESET="\033[0m"
  C_BOLD="\033[1m"
  C_CYAN="\033[36m"
  C_GREEN="\033[32m"
  C_YELLOW="\033[33m"
  C_RED="\033[31m"
else
  C_RESET="" C_BOLD="" C_CYAN="" C_GREEN="" C_YELLOW="" C_RED=""
fi

info()  { printf "${C_CYAN}[INFO]${C_RESET} %s\n" "$*"; }
ok()    { printf "${C_GREEN}[ OK ]${C_RESET} %s\n" "$*"; }
warn()  { printf "${C_YELLOW}[WARN]${C_RESET} %s\n" "$*"; }
err()   { printf "${C_RED}[ERR ]${C_RESET} %s\n" "$*" >&2; }
fatal() { err "$*"; exit 1; }

prompt_input() {
  local prompt_text="$1"
  local default_val="${2:-}"
  local reply=""
  if [ "$NON_INTERACTIVE" -eq 1 ]; then
    printf "%s" "$default_val"
    return 0
  fi
  if [ -e /dev/tty ]; then
    if [ -n "$default_val" ]; then
      printf "${C_BOLD}%s${C_RESET} [${default_val}]: " "$prompt_text" >/dev/tty
    else
      printf "${C_BOLD}%s${C_RESET}: " "$prompt_text" >/dev/tty
    fi
    IFS= read -r reply </dev/tty || true
  fi
  if [ -z "$reply" ]; then
    printf "%s" "$default_val"
  else
    printf "%s" "$reply"
  fi
}

usage() {
  cat <<'EOF'
CSP-NG (CardServProxy NG) — Universal Installer

Usage:
  sudo ./install.sh [OPTIONS]

Modes (--mode):
  central          Install Central Server (PostgreSQL + Java 21 + Web UI + systemd)
  worker-native    Install Native Relay Worker (No JVM, GraalVM ELF binary)
  worker-jvm       Install JVM Relay Worker (Java 21 + shaded JAR)
  pki              Generate mTLS CA + Central certificate + Worker client bundle
  reset-admin      Reset Web Admin password and print new credentials
  upgrade          Upgrade existing Central Server or Worker to target version
  uninstall        Uninstall Central Server and/or Worker services

Options for --mode central:
  --app-variant <quarkus-app|runner-jar>          (default: quarkus-app)
  --exposure <self-signed|letsencrypt|loopback>   (default: self-signed)
  --domain <fqdn>                                 Required for letsencrypt
  --email <email>                                 Email for Let's Encrypt
  --admin-user <username>                         (default: admin)
  --admin-pass <password>                         (default: auto-generated)
  --fail2ban / --no-fail2ban                      Enable/skip Fail2ban jail

Options for --mode worker-native / worker-jvm:
  --central-url <https://host:8444>               Central mTLS control URL
  --node-id <id>                                  Worker node ID (e.g. peer-a)
  --glibc <glibc2.31|glibc2.36|glibc2.41>         Override auto-detected glibc
  --cspv2-port <port>                             Inter-node CSPv2 port (default: 0)
  --cert-bundle </path/to/worker-certs.tar.gz>    Optional pre-generated mTLS bundle

General Options:
  --version <tag>                                 Release tag (default: v1.0.2)
  -y, --yes                                       Non-interactive mode (accept defaults)
  -h, --help                                      Show this help message
EOF
}

# Parse CLI arguments
while [ $# -gt 0 ]; do
  case "$1" in
    --mode)          MODE="$2"; shift 2 ;;
    --mode=*)        MODE="${1#*=}"; shift ;;
    --app-variant)   APP_VARIANT="$2"; shift 2 ;;
    --app-variant=*) APP_VARIANT="${1#*=}"; shift ;;
    --exposure)      EXPOSURE="$2"; shift 2 ;;
    --exposure=*)    EXPOSURE="${1#*=}"; shift ;;
    --domain)        DOMAIN="$2"; shift 2 ;;
    --domain=*)      DOMAIN="${1#*=}"; shift ;;
    --email)         EMAIL="$2"; shift 2 ;;
    --email=*)       EMAIL="${1#*=}"; shift ;;
    --admin-user)    ADMIN_USER="$2"; shift 2 ;;
    --admin-pass)    ADMIN_PASS="$2"; shift 2 ;;
    --fail2ban)      WITH_FAIL2BAN="yes"; shift ;;
    --no-fail2ban)   WITH_FAIL2BAN="no"; shift ;;
    --glibc)         GLIBC_OVERRIDE="$2"; shift 2 ;;
    --glibc=*)       GLIBC_OVERRIDE="${1#*=}"; shift ;;
    --central-url)   CENTRAL_URL="$2"; shift 2 ;;
    --central-url=*) CENTRAL_URL="${1#*=}"; shift ;;
    --node-id)       NODE_ID="$2"; shift 2 ;;
    --node-id=*)     NODE_ID="${1#*=}"; shift ;;
    --cspv2-port)    CSPV2_PORT="$2"; shift 2 ;;
    --cert-bundle)   WORKER_CERT_BUNDLE="$2"; shift 2 ;;
    --version)       CSP_VERSION="$2"; shift 2 ;;
    --version=*)     CSP_VERSION="${1#*=}"; shift ;;
    -y|--yes)        NON_INTERACTIVE=1; shift ;;
    -h|--help)       usage; exit 0 ;;
    *)               fatal "Unknown option: $1 (run with --help)" ;;
  esac
done

# ---------- System & Environment Detection ----------

detect_arch() {
  local machine
  machine="$(uname -m)"
  case "$machine" in
    x86_64|amd64)   printf "linux-x86_64" ;;
    aarch64|arm64)  printf "linux-arm64" ;;
    *)              fatal "Unsupported CPU architecture: $machine (supported: x86_64, aarch64/arm64)" ;;
  esac
}

detect_glibc_num() {
  local ver
  ver="$(ldd --version 2>/dev/null | head -n 1 | grep -oE '[0-9]+\.[0-9]+$' || true)"
  if [ -z "$ver" ]; then
    ver="2.31"
  fi
  printf "%s" "$ver"
}

version_ge() {
  # Returns 0 if $1 >= $2 using sort -V
  [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n 1)" = "$2" ]
}

select_glibc_flavor() {
  if [ -n "$GLIBC_OVERRIDE" ]; then
    case "$GLIBC_OVERRIDE" in
      2.31|glibc2.31) printf "glibc2.31" ;;
      2.36|glibc2.36) printf "glibc2.36" ;;
      2.41|glibc2.41) printf "glibc2.41" ;;
      *) fatal "Invalid --glibc value '$GLIBC_OVERRIDE' (allowed: glibc2.31, glibc2.36, glibc2.41)" ;;
    esac
    return 0
  fi

  local host_glibc
  host_glibc="$(detect_glibc_num)"
  if version_ge "$host_glibc" "2.41"; then
    printf "glibc2.41"
  elif version_ge "$host_glibc" "2.36"; then
    printf "glibc2.36"
  elif version_ge "$host_glibc" "2.31"; then
    printf "glibc2.31"
  else
    fatal "Host glibc ($host_glibc) is older than minimum required 2.31 (Debian 11 / Ubuntu 20.04+)."
  fi
}

detect_os_family() {
  if [ -f /etc/debian_version ]; then
    printf "debian"
  elif [ -f /etc/redhat-release ]; then
    printf "rhel"
  else
    printf "unknown"
  fi
}

require_root() {
  if [ "$(id -u)" -ne 0 ]; then
    fatal "This script must be run as root (use sudo)."
  fi
}

install_base_tools() {
  local os_fam
  os_fam="$(detect_os_family)"
  if [ "$os_fam" = "debian" ]; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq curl tar ca-certificates openssl gnupg coreutils iproute2
  elif [ "$os_fam" = "rhel" ]; then
    dnf install -y -q curl tar ca-certificates openssl coreutils iproute
  else
    command -v curl >/dev/null || fatal "curl is required."
    command -v openssl >/dev/null || fatal "openssl is required."
  fi
}

install_java21() {
  if command -v java >/dev/null 2>&1; then
    local jver
    jver="$(java -version 2>&1 | head -n 1 | grep -oE '"[0-9]+' | tr -d '"' || true)"
    if [ -n "$jver" ] && [ "$jver" -ge 21 ] 2>/dev/null; then
      ok "Java $jver is already installed ($(command -v java))"
      return 0
    fi
  fi

  info "Installing Java 21 JRE..."
  local os_fam
  os_fam="$(detect_os_family)"
  if [ "$os_fam" = "debian" ]; then
    if apt-cache show openjdk-21-jre-headless >/dev/null 2>&1; then
      apt-get install -y -qq openjdk-21-jre-headless
    else
      info "openjdk-21-jre-headless not in default APT repo; adding Adoptium Temurin repository..."
      mkdir -p /etc/apt/keyrings
      curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg --yes
      local codename
      codename="$(. /etc/os-release && printf "%s" "${VERSION_CODENAME:-bullseye}")"
      echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb ${codename} main" > /etc/apt/sources.list.d/adoptium.list
      apt-get update -qq
      apt-get install -y -qq temurin-21-jre || apt-get install -y -qq temurin-21-jdk
    fi
  elif [ "$os_fam" = "rhel" ]; then
    dnf install -y -q java-21-openjdk-headless
  else
    fatal "Could not automatically install Java 21 on this OS. Please install Java 21 manually."
  fi
  ok "Java 21 installed: $(java -version 2>&1 | head -n 1)"
}

resolve_java_bin() {
  local jbin=""
  if [ -x /opt/csp-java-21/bin/java ]; then
    jbin="/opt/csp-java-21/bin/java"
  elif [ -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java ]; then
    jbin="/usr/lib/jvm/java-21-openjdk-amd64/bin/java"
  elif [ -x /usr/lib/jvm/java-21-openjdk-arm64/bin/java ]; then
    jbin="/usr/lib/jvm/java-21-openjdk-arm64/bin/java"
  elif command -v java >/dev/null 2>&1; then
    jbin="$(readlink -f "$(command -v java)")"
  else
    # Search common manual install locations (/opt, /usr/lib/jvm, /usr/java, /root/.sdkman)
    jbin="$(find /usr/lib/jvm /opt /usr/local /root/.sdkman -maxdepth 5 -type f -name java -executable 2>/dev/null | grep -E '21|graal|jdk' | head -n 1 || true)"
  fi

  [ -n "$jbin" ] && [ -x "$jbin" ] || fatal "Java 21 binary not found."

  # If Java was installed manually inside /root or /home, systemd User=csp (with ProtectHome=true)
  # cannot execute it (203/EXEC). Copy the JDK into /opt/csp-java-21 with world-readable permissions.
  case "$jbin" in
    /root/*|/home/*)
      local jhome
      jhome="$(dirname "$(dirname "$jbin")")"
      info "Java is installed under ${jhome} (inaccessible to systemd User=csp); copying to /opt/csp-java-21..." >&2
      rm -rf /opt/csp-java-21
      cp -a "$jhome" /opt/csp-java-21
      chown -R root:root /opt/csp-java-21
      chmod -R u=rwX,go=rX /opt/csp-java-21
      jbin="/opt/csp-java-21/bin/java"
      ;;
  esac

  printf "%s" "$jbin"
}

download_and_verify_asset() {
  local asset_name="$1"
  local dest_dir="$2"
  local base_url="https://github.com/${GITHUB_REPO}/releases/download/${CSP_VERSION}"

  mkdir -p "$dest_dir"
  info "Downloading ${asset_name} (${CSP_VERSION})..."
  curl -fL --progress-bar -o "${dest_dir}/${asset_name}" "${base_url}/${asset_name}"
  curl -fsSL -o "${dest_dir}/SHA256SUMS.txt" "${base_url}/SHA256SUMS.txt"

  info "Verifying SHA-256 checksum for ${asset_name}..."
  (
    cd "$dest_dir"
    grep " ${asset_name}\$" SHA256SUMS.txt | sha256sum -c - >/dev/null
  ) || fatal "SHA-256 checksum verification FAILED for ${asset_name}!"
  ok "SHA-256 checksum verified for ${asset_name}"
}

# ---------- Mode 4: PKI Helper (mTLS CA + Central + Worker certs) ----------

generate_mtls_cert() {
  local pki_dir="$1"
  local node_id="$2"
  local out_dir="$3"

  mkdir -p "$pki_dir" "$out_dir"
  if [ ! -f "${pki_dir}/ca.key" ] || [ ! -f "${pki_dir}/ca.pem" ]; then
    info "Generating Cluster Root CA in ${pki_dir}..."
    openssl req -x509 -newkey rsa:4096 -days 3650 -nodes \
      -subj "/CN=CSP-NG Cluster CA" \
      -keyout "${pki_dir}/ca.key" -out "${pki_dir}/ca.pem" 2>/dev/null
    chmod 0600 "${pki_dir}/ca.key"
    chmod 0644 "${pki_dir}/ca.pem"
  fi

  info "Generating mTLS certificate for node '${node_id}' (SAN URI:urn:csp:node:${node_id})..."
  local ext_file="${out_dir}/${node_id}.ext"
  cat > "$ext_file" <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=URI:urn:csp:node:${node_id}
EOF

  openssl req -newkey rsa:2048 -nodes \
    -subj "/CN=${node_id}" \
    -keyout "${out_dir}/key.pem" -out "${out_dir}/${node_id}.csr" 2>/dev/null

  openssl x509 -req -in "${out_dir}/${node_id}.csr" \
    -CA "${pki_dir}/ca.pem" -CAkey "${pki_dir}/ca.key" -CAcreateserial \
    -out "${out_dir}/cert.pem" -days 1825 -extfile "$ext_file" 2>/dev/null

  cp "${pki_dir}/ca.pem" "${out_dir}/ca.pem"
  rm -f "${out_dir}/${node_id}.csr" "$ext_file"
  chmod 0600 "${out_dir}/key.pem"
  chmod 0644 "${out_dir}/cert.pem" "${out_dir}/ca.pem"
}

run_mode_pki() {
  require_root
  install_base_tools

  local pki_root="/etc/csp/pki"
  local central_dir="/etc/csp/central"
  if [ -z "$NODE_ID" ]; then
    NODE_ID="$(prompt_input "Enter Worker Node ID to generate certificate for (e.g. peer-a)" "peer-a")"
  fi
  [[ "$NODE_ID" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]] || fatal "Invalid node ID '${NODE_ID}' (must be lowercase alphanumeric/dash/underscore)."

  # Ensure central1 cert exists
  if [ ! -f "${central_dir}/cert.pem" ]; then
    generate_mtls_cert "$pki_root" "central1" "$central_dir"
    if id csp >/dev/null 2>&1; then
      chown -R root:csp "$central_dir"
      chmod 0750 "$central_dir"
      chmod 0640 "${central_dir}/key.pem"
    fi
    ok "Central mTLS certificates ready in ${central_dir} (node: central1)"
  fi

  local worker_out="${pki_root}/workers/${NODE_ID}"
  generate_mtls_cert "$pki_root" "$NODE_ID" "$worker_out"
  local bundle_path="/root/csp-worker-${NODE_ID}-certs.tar.gz"
  tar -C "$worker_out" -czf "$bundle_path" cert.pem key.pem ca.pem
  chmod 0600 "$bundle_path"

  echo ""
  ok "Worker mTLS bundle created: ${bundle_path}"
  echo "  Copy this bundle to your remote worker VPS and run:"
  echo "  sudo ./install.sh --mode worker-native --node-id ${NODE_ID} --cert-bundle ${bundle_path} --central-url https://<CENTRAL_IP>:8444"
}

# ---------- Mode 1: Central Server Installation ----------

run_mode_central() {
  require_root
  install_base_tools

  if [ "$NON_INTERACTIVE" -eq 0 ]; then
    echo ""
    printf "${C_BOLD}--- Central Server Configuration ---${C_RESET}\n"
    local v_choice
    v_choice="$(prompt_input "Application packaging [1=quarkus-app (Recommended), 2=runner-jar]" "1")"
    [ "$v_choice" = "2" ] && APP_VARIANT="runner-jar" || APP_VARIANT="quarkus-app"

    local exp_choice
    exp_choice="$(prompt_input "Web Console HTTPS exposure [1=Nginx Self-Signed HTTPS (IP), 2=Nginx Let's Encrypt (Domain), 3=Loopback 127.0.0.1:8080 only]" "1")"
    case "$exp_choice" in
      2)
        EXPOSURE="letsencrypt"
        [ -z "$DOMAIN" ] && DOMAIN="$(prompt_input "Enter your domain name (e.g. csp.example.com)" "")"
        [ -z "$EMAIL" ]  && EMAIL="$(prompt_input "Enter email for Let's Encrypt notifications" "admin@${DOMAIN}")"
        ;;
      3) EXPOSURE="loopback" ;;
      *) EXPOSURE="self-signed" ;;
    esac

    ADMIN_USER="$(prompt_input "Initial Web Admin username" "$ADMIN_USER")"
    if [ "$WITH_FAIL2BAN" = "ask" ]; then
      local f2b_ans
      f2b_ans="$(prompt_input "Install and configure Fail2ban protection? [Y/n]" "Y")"
      [[ "$f2b_ans" =~ ^[Nn] ]] && WITH_FAIL2BAN="no" || WITH_FAIL2BAN="yes"
    fi
  else
    [ "$WITH_FAIL2BAN" = "ask" ] && WITH_FAIL2BAN="yes"
  fi

  if [ "$EXPOSURE" = "letsencrypt" ] && [ -z "$DOMAIN" ]; then
    fatal "--domain is required when --exposure=letsencrypt"
  fi

  install_java21

  # 1. Install & start PostgreSQL
  info "Installing and starting PostgreSQL..."
  local os_fam
  os_fam="$(detect_os_family)"
  if [ "$os_fam" = "debian" ]; then
    apt-get install -y -qq postgresql
  elif [ "$os_fam" = "rhel" ]; then
    dnf install -y -q postgresql-server
    postgresql-setup --initdb || true
  fi
  systemctl enable --now postgresql

  # 2. Provision PostgreSQL role & database
  local db_pass
  db_pass="$(openssl rand -hex 24)"
  if [ -z "$ADMIN_PASS" ]; then
    ADMIN_PASS="$(openssl rand -hex 10)"
  fi

  info "Provisioning PostgreSQL database 'csp' and role 'csp'..."
  su - postgres -c "psql -v ON_ERROR_STOP=1 -v pw='${db_pass}'" <<'SQL' >/dev/null
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'csp') THEN
    CREATE ROLE csp WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
  END IF;
END
$$;
ALTER ROLE csp WITH PASSWORD :'pw';
SELECT 'CREATE DATABASE csp WITH OWNER csp ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'csp')\gexec
\c csp
ALTER SCHEMA public OWNER TO csp;
GRANT ALL ON SCHEMA public TO csp;
SQL
  ok "PostgreSQL database 'csp' ready"

  # Determine local PG SSL support
  local db_ssl_mode="disable"
  if PGPASSWORD="${db_pass}" psql "host=127.0.0.1 port=5432 dbname=csp user=csp sslmode=require" -c "SELECT 1;" >/dev/null 2>&1; then
    db_ssl_mode="require"
  fi

  # 3. Create system user & directories
  info "Creating system user 'csp' and directory tree..."
  if ! id csp >/dev/null 2>&1; then
    useradd --system --user-group --no-create-home --home-dir /opt/csp --shell /usr/sbin/nologin csp
  fi

  local release_ts
  release_ts="$(date +%Y%m%d%H%M%S)"
  local release_dir="/opt/csp/releases/${release_ts}"
  mkdir -p "$release_dir" /etc/csp/secrets/imported /var/lib/csp

  # 4. Download & unpack release
  TMP_DL="$(mktemp -d)"

  download_and_verify_asset "csp-app-${CSP_VERSION}-quarkus-app.tar.gz" "$TMP_DL"
  tar -xzf "${TMP_DL}/csp-app-${CSP_VERSION}-quarkus-app.tar.gz" -C "$TMP_DL"

  local extracted_dir="${TMP_DL}/csp-app-${CSP_VERSION}"
  cp -r "${extracted_dir}/quarkus-app" "${release_dir}/"
  cp -r "${extracted_dir}/deploy" "${release_dir}/"

  local exec_jar="/opt/csp/current/quarkus-app/quarkus-run.jar"
  if [ "$APP_VARIANT" = "runner-jar" ]; then
    download_and_verify_asset "csp-app-${CSP_VERSION}-runner.jar" "$TMP_DL"
    cp "${TMP_DL}/csp-app-${CSP_VERSION}-runner.jar" "${release_dir}/csp-app-runner.jar"
    exec_jar="/opt/csp/current/csp-app-runner.jar"
  fi

  chmod -R u=rwX,g=rX,o= "$release_dir"
  ln -sfn "$release_dir" /opt/csp/current
  chown -R root:csp /opt/csp
  chmod 0755 /opt/csp

  chown -R root:csp /etc/csp
  chmod 0750 /etc/csp
  chown -R csp:csp /etc/csp/secrets /var/lib/csp
  chmod 0700 /etc/csp/secrets
  chmod 0750 /var/lib/csp

  # 5. Write /etc/csp/csp.env (Phase 1: CSP_RUNTIME_ENABLED=false)
  cat > /etc/csp/csp.env <<EOF
CSP_DB_HOST=127.0.0.1
CSP_DB_PORT=5432
CSP_DB_NAME=csp
CSP_DB_USER=csp
CSP_DB_PASSWORD=${db_pass}
CSP_DB_SSL_MODE=${db_ssl_mode}
QUARKUS_DATASOURCE_REACTIVE_TRUST_ALL=true
CSP_BOOTSTRAP_ADMIN_USER=${ADMIN_USER}
CSP_BOOTSTRAP_ADMIN_PASSWORD=${ADMIN_PASS}
CSP_RUNTIME_ENABLED=false
CSP_DCW_LINKS_FILE=/var/lib/csp/links.json
EOF
  chown root:csp /etc/csp/csp.env
  chmod 0640 /etc/csp/csp.env

  # Save admin credentials to /root/csp-admin-credentials.txt immediately so they are never lost
  cat > /root/csp-admin-credentials.txt <<EOF
CSP-NG Web Admin Credentials (generated $(date -Is))
Username: ${ADMIN_USER}
Password: ${ADMIN_PASS}
EOF
  chmod 0600 /root/csp-admin-credentials.txt

  # If re-running installer on an existing DB, remove previous bootstrap admin so new password is applied
  su - postgres -c "psql -d csp -c \"DELETE FROM csp_user WHERE lower(name) = lower('${ADMIN_USER}') AND admin = true;\"" >/dev/null 2>&1 || true

  # 6. Install systemd service
  local java_bin
  java_bin="$(resolve_java_bin)"
  cp "${release_dir}/deploy/csp-app.service" /etc/systemd/system/csp-app.service
  sed -i '/^[[:space:]]*-Xms128m/d; /^[[:space:]]*-XX:+ExitOnOutOfMemoryError/d; /^[[:space:]]*-Djava\.awt\.headless/d; /^[[:space:]]*-jar \/opt\/csp/d' /etc/systemd/system/csp-app.service
  sed -i "s|^ExecStart=.*|ExecStart=${java_bin} -Xms128m -Xmx512m -XX:MaxDirectMemorySize=128m -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true -Dfile.encoding=UTF-8 -jar ${exec_jar}|" /etc/systemd/system/csp-app.service
  chmod 0644 /etc/systemd/system/csp-app.service
  systemctl daemon-reload

  # 7. Phase 1 start: Flyway migrations + Admin bootstrap
  info "Starting csp-app (Phase 1: database migrations & admin bootstrap)..."
  systemctl enable csp-app
  systemctl restart csp-app

  local ready=0
  for _ in $(seq 1 45); do
    if curl -fsS http://127.0.0.1:8080/q/health/ready >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 2
  done
  [ "$ready" -eq 1 ] || fatal "csp-app did not become ready within 90s. Check: journalctl -u csp-app -e"
  ok "Database migrations applied and bootstrap admin user '${ADMIN_USER}' created"

  # 8. Phase 2: Disable seeded port 15000 listener, remove bootstrap password, enable runtime
  info "Disabling default sample listener (port 15000) and enabling runtime..."
  su - postgres -c "psql -d csp -c \"UPDATE csp_profile_listener SET enabled = false WHERE profile_name = 'default' AND protocol = 'newcamd' AND port = 15000;\"" >/dev/null
  sed -i '/^CSP_BOOTSTRAP_ADMIN_/d' /etc/csp/csp.env
  sed -i 's/^CSP_RUNTIME_ENABLED=false/CSP_RUNTIME_ENABLED=true/' /etc/csp/csp.env
  systemctl restart csp-app

  ready=0
  for _ in $(seq 1 30); do
    if curl -fsS http://127.0.0.1:8080/q/health/live >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 2
  done
  [ "$ready" -eq 1 ] || fatal "csp-app failed to restart in runtime mode. Check: journalctl -u csp-app -e"
  ok "csp-app service is active and healthy on 127.0.0.1:8080"

  # 9. Configure Reverse Proxy (Nginx) if requested
  local access_url="http://127.0.0.1:8080/admin/login (via SSH tunnel)"
  if [ "$EXPOSURE" = "self-signed" ] || [ "$EXPOSURE" = "letsencrypt" ]; then
    configure_nginx "$EXPOSURE" "$DOMAIN" "$EMAIL"
    if [ "$EXPOSURE" = "letsencrypt" ]; then
      access_url="https://${DOMAIN}/admin/login"
    else
      local pub_ip
      pub_ip="$(hostname -I 2>/dev/null | awk '{print $1}' || printf "YOUR_SERVER_IP")"
      access_url="https://${pub_ip}/admin/login"
    fi
  fi

  # 10. Optional Fail2ban
  if [ "$WITH_FAIL2BAN" = "yes" ]; then
    configure_fail2ban "$release_dir"
  fi

  echo ""
  printf "${C_GREEN}=====================================================================${C_RESET}\n"
  printf "${C_BOLD} CSP-NG Central Server (${CSP_VERSION}) Installed Successfully!${C_RESET}\n"
  printf "${C_GREEN}=====================================================================${C_RESET}\n"
  printf "  Web Admin URL      : ${C_BOLD}%s${C_RESET}\n" "$access_url"
  printf "  Admin Username     : ${C_BOLD}%s${C_RESET}\n" "$ADMIN_USER"
  printf "  Admin Password     : ${C_BOLD}%s${C_RESET}  (Saved in /root/csp-admin-credentials.txt)\n" "$ADMIN_PASS"
  printf "  Service Status     : systemctl status csp-app\n"
  printf "  Service Logs       : journalctl -u csp-app -f\n"
  printf "  Config File        : /etc/csp/csp.env\n"
  printf "${C_GREEN}=====================================================================${C_RESET}\n"
}

configure_nginx() {
  local mode="$1"
  local domain="$2"
  local email="$3"

  info "Installing and configuring Nginx reverse proxy (${mode})..."
  local os_fam
  os_fam="$(detect_os_family)"
  if [ "$os_fam" = "debian" ]; then
    apt-get install -y -qq nginx
  elif [ "$os_fam" = "rhel" ]; then
    dnf install -y -q nginx
  fi

  local cert_path="/etc/ssl/csp-ng/fullchain.pem"
  local key_path="/etc/ssl/csp-ng/privkey.pem"
  local server_name="_"

  if [ "$mode" = "letsencrypt" ]; then
    server_name="$domain"
    if [ "$os_fam" = "debian" ]; then
      apt-get install -y -qq certbot
    elif [ "$os_fam" = "rhel" ]; then
      dnf install -y -q certbot
    fi
    mkdir -p /var/www/html
    cat > /etc/nginx/conf.d/csp-ng.conf <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name ${domain};
    location /.well-known/acme-challenge/ {
        root /var/www/html;
        allow all;
    }
    location / { return 503; }
}
EOF
    systemctl enable --now nginx
    systemctl reload nginx
    certbot certonly --webroot -w /var/www/html -d "$domain" \
      --non-interactive --agree-tos -m "$email" --keep-until-expiring
    cert_path="/etc/letsencrypt/live/${domain}/fullchain.pem"
    key_path="/etc/letsencrypt/live/${domain}/privkey.pem"
  else
    mkdir -p /etc/ssl/csp-ng
    local cn
    cn="$(hostname -I 2>/dev/null | awk '{print $1}' || printf "localhost")"
    openssl req -x509 -newkey rsa:2048 -days 3650 -nodes \
      -subj "/CN=${cn}" \
      -keyout "$key_path" -out "$cert_path" 2>/dev/null
    chmod 0600 "$key_path"
  fi

  rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true

  # Nginx < 1.25.1 (e.g. Debian 12 Bookworm Nginx 1.22.1) requires 'listen 443 ssl http2;'
  # instead of the standalone 'http2 on;' directive.
  local nginx_ver
  nginx_ver="$(nginx -v 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n 1 || printf "1.22.1")"
  local listen_ssl_v4="listen 443 ssl http2;"
  local listen_ssl_v6="listen [::]:443 ssl http2;"
  local http2_directive=""
  if version_ge "$nginx_ver" "1.25.1"; then
    listen_ssl_v4="listen 443 ssl;"
    listen_ssl_v6="listen [::]:443 ssl;"
    http2_directive="http2 on;"
  fi

  cat > /etc/nginx/conf.d/csp-ng.conf <<EOF
server {
    ${listen_ssl_v4}
    ${listen_ssl_v6}
    ${http2_directive}
    server_name ${server_name};

    ssl_certificate     ${cert_path};
    ssl_certificate_key ${key_path};

    client_max_body_size 256k;
    server_tokens off;
    proxy_hide_header WWW-Authenticate;
    add_header Strict-Transport-Security "max-age=31536000" always;

    location ^~ /q/ { access_log off; return 404; }
    location = /q   { access_log off; return 404; }

    location = /      { return 302 https://\$host/admin/index.html; }
    location = /admin { return 301 https://\$host/admin/index.html; }

    location = /api/admin/events {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Forwarded-Port \$server_port;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Forwarded-Port \$server_port;
    }
}

server {
    listen 80;
    listen [::]:80;
    server_name ${server_name};
    location /.well-known/acme-challenge/ {
        root /var/www/html;
        allow all;
    }
    location / { return 301 https://\$host\$request_uri; }
}
EOF

  nginx -t
  systemctl enable --now nginx
  systemctl reload nginx
  ok "Nginx reverse proxy configured and active"
}

configure_fail2ban() {
  local release_dir="$1"
  info "Installing and configuring Fail2ban for CSP-NG..."
  local os_fam
  os_fam="$(detect_os_family)"
  if [ "$os_fam" = "debian" ]; then
    apt-get install -y -qq fail2ban
  elif [ "$os_fam" = "rhel" ]; then
    dnf install -y -q fail2ban
  fi
  if [ -d "${release_dir}/deploy/fail2ban" ]; then
    cp -r "${release_dir}/deploy/fail2ban/filter.d/"* /etc/fail2ban/filter.d/ 2>/dev/null || true
    cp -r "${release_dir}/deploy/fail2ban/jail.d/"* /etc/fail2ban/jail.d/ 2>/dev/null || true
    systemctl enable --now fail2ban
    systemctl restart fail2ban || true
    ok "Fail2ban jail installed"
  fi
}

# ---------- Mode 2 & 3: Relay Worker Installation (Native & JVM) ----------

run_mode_worker() {
  local worker_kind="$1" # native | jvm
  require_root
  install_base_tools

  local arch=""
  local glibc_flavor=""
  if [ "$worker_kind" = "native" ]; then
    arch="$(detect_arch)"
    glibc_flavor="$(select_glibc_flavor)"
    info "Detected architecture: ${arch}, glibc target: ${glibc_flavor} (host glibc $(detect_glibc_num))"
  else
    install_java21
  fi

  if [ -z "$CENTRAL_URL" ]; then
    CENTRAL_URL="$(prompt_input "Central Server Control URL (e.g. https://central.example.com:8444)" "https://127.0.0.1:8444")"
  fi
  if [ -z "$NODE_ID" ]; then
    NODE_ID="$(prompt_input "Worker Node ID (must match SAN urn:csp:node:<id>)" "peer-a")"
  fi
  if [ "$NON_INTERACTIVE" -eq 0 ]; then
    CSPV2_PORT="$(prompt_input "CSPv2 inter-node TCP port (0 to disable)" "$CSPV2_PORT")"
  fi

  if ! id csp-worker >/dev/null 2>&1; then
    useradd --system --user-group --no-create-home --shell /usr/sbin/nologin csp-worker
  fi
  install -d -m 0750 -o csp-worker -g csp-worker /etc/csp/worker

  TMP_DL="$(mktemp -d)"

  local exec_start=""
  if [ "$worker_kind" = "native" ]; then
    local asset="csp-worker-${CSP_VERSION}-${arch}-${glibc_flavor}.tar.gz"
    download_and_verify_asset "$asset" "$TMP_DL"
    tar -xzf "${TMP_DL}/${asset}" -C "$TMP_DL"
    local pkg_dir="${TMP_DL}/csp-worker-${CSP_VERSION}-${arch}-${glibc_flavor}"
    install -m 0755 "${pkg_dir}/csp-worker" /usr/local/bin/csp-worker
    cp "${pkg_dir}/csp-worker.service" /etc/systemd/system/csp-worker.service
    exec_start="/usr/local/bin/csp-worker"
  else
    local asset="csp-worker-${CSP_VERSION}-shaded.jar"
    download_and_verify_asset "$asset" "$TMP_DL"
    mkdir -p /usr/local/lib/csp-ng
    install -m 0644 "${TMP_DL}/${asset}" /usr/local/lib/csp-ng/csp-worker.jar
    local java_bin
    java_bin="$(resolve_java_bin)"
    exec_start="${java_bin} -Xms64m -Xmx256m -jar /usr/local/lib/csp-ng/csp-worker.jar"
  fi

  # Extract certificate bundle if provided
  if [ -n "$WORKER_CERT_BUNDLE" ] && [ -f "$WORKER_CERT_BUNDLE" ]; then
    info "Extracting mTLS certificate bundle ${WORKER_CERT_BUNDLE} to /etc/csp/worker..."
    tar -xzf "$WORKER_CERT_BUNDLE" -C /etc/csp/worker
    chown csp-worker:csp-worker /etc/csp/worker/*.pem
    chmod 0600 /etc/csp/worker/key.pem
    chmod 0644 /etc/csp/worker/cert.pem /etc/csp/worker/ca.pem
  fi

  cat > /etc/systemd/system/csp-worker.service <<EOF
[Unit]
Description=CSP-NG relay worker (${worker_kind})
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=csp-worker
Group=csp-worker
ExecStart=${exec_start}
Environment=CSP_WORKER_CENTRAL_URL=${CENTRAL_URL}
Environment=CSP_WORKER_NODE_ID=${NODE_ID}
Environment=CSP_WORKER_TLS_CERT_FILE=/etc/csp/worker/cert.pem
Environment=CSP_WORKER_TLS_KEY_FILE=/etc/csp/worker/key.pem
Environment=CSP_WORKER_TLS_TRUST_FILE=/etc/csp/worker/ca.pem
Environment=CSP_WORKER_TTL_MS=30000
Environment=CSP_WORKER_POLL_MS=10000
Environment=CSP_WORKER_CSPV2_PORT=${CSPV2_PORT}
EnvironmentFile=-/etc/csp/worker/secrets.env
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6 AF_NETLINK
RestrictNamespaces=true
LockPersonality=true
MemoryDenyWriteExecute=false
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF
  chmod 0644 /etc/systemd/system/csp-worker.service
  systemctl daemon-reload

  if [ -f /etc/csp/worker/cert.pem ] && [ -f /etc/csp/worker/key.pem ] && [ -f /etc/csp/worker/ca.pem ]; then
    systemctl enable --now csp-worker
    ok "csp-worker service started and enabled!"
  else
    systemctl enable csp-worker
    warn "mTLS certificates (/etc/csp/worker/{cert,key,ca}.pem) are not placed yet."
    warn "Generate them on your Central Server with: sudo ./install.sh --mode pki --node-id ${NODE_ID}"
    warn "Then copy cert.pem, key.pem, ca.pem to /etc/csp/worker/ and run: systemctl start csp-worker"
  fi

  echo ""
  printf "${C_GREEN}=====================================================================${C_RESET}\n"
  printf "${C_BOLD} CSP-NG Relay Worker (${worker_kind}, ${CSP_VERSION}) Installed!${C_RESET}\n"
  printf "${C_GREEN}=====================================================================${C_RESET}\n"
  printf "  Node ID            : ${C_BOLD}%s${C_RESET}\n" "$NODE_ID"
  printf "  Central URL        : ${C_BOLD}%s${C_RESET}\n" "$CENTRAL_URL"
  [ "$worker_kind" = "native" ] && printf "  Native Variant     : ${C_BOLD}%s (%s)${C_RESET}\n" "$arch" "$glibc_flavor"
  printf "  Certificates Dir   : /etc/csp/worker/{cert.pem,key.pem,ca.pem}\n"
  printf "  Service Commands   : systemctl status csp-worker | journalctl -u csp-worker -f\n"
  printf "${C_GREEN}=====================================================================${C_RESET}\n"
}

# ---------- Mode 5: Upgrade ----------

run_mode_upgrade() {
  require_root
  install_base_tools

  TMP_DL="$(mktemp -d)"

  if [ -f /etc/systemd/system/csp-app.service ]; then
    info "Upgrading Central Server (csp-app) to ${CSP_VERSION}..."
    local release_ts
    release_ts="$(date +%Y%m%d%H%M%S)"
    local release_dir="/opt/csp/releases/${release_ts}"
    mkdir -p "$release_dir"

    download_and_verify_asset "csp-app-${CSP_VERSION}-quarkus-app.tar.gz" "$TMP_DL"
    tar -xzf "${TMP_DL}/csp-app-${CSP_VERSION}-quarkus-app.tar.gz" -C "$TMP_DL"
    cp -r "${TMP_DL}/csp-app-${CSP_VERSION}/quarkus-app" "${release_dir}/"
    cp -r "${TMP_DL}/csp-app-${CSP_VERSION}/deploy" "${release_dir}/"

    if grep -q "csp-app-runner.jar" /etc/systemd/system/csp-app.service; then
      download_and_verify_asset "csp-app-${CSP_VERSION}-runner.jar" "$TMP_DL"
      cp "${TMP_DL}/csp-app-${CSP_VERSION}-runner.jar" "${release_dir}/csp-app-runner.jar"
    fi

    chmod -R u=rwX,g=rX,o= "$release_dir"
    chown -R root:csp "$release_dir"
    ln -sfn "$release_dir" /opt/csp/current

    # Ensure ExecStart in csp-app.service points to a valid, accessible Java 21 binary
    local java_bin exec_jar
    java_bin="$(resolve_java_bin)"
    exec_jar="/opt/csp/current/quarkus-app/quarkus-run.jar"
    if grep -q "csp-app-runner.jar" /etc/systemd/system/csp-app.service; then
      exec_jar="/opt/csp/current/csp-app-runner.jar"
    fi
    sed -i '/^[[:space:]]*-Xms128m/d; /^[[:space:]]*-XX:+ExitOnOutOfMemoryError/d; /^[[:space:]]*-Djava\.awt\.headless/d; /^[[:space:]]*-jar \/opt\/csp/d' /etc/systemd/system/csp-app.service
    sed -i "s|^ExecStart=.*|ExecStart=${java_bin} -Xms128m -Xmx512m -XX:MaxDirectMemorySize=128m -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true -Dfile.encoding=UTF-8 -jar ${exec_jar}|" /etc/systemd/system/csp-app.service
    systemctl daemon-reload

    # If a previous installation was interrupted during Phase 1, complete Phase 2
    if [ -f /etc/csp/csp.env ] && grep -q '^CSP_RUNTIME_ENABLED=false' /etc/csp/csp.env; then
      su - postgres -c "psql -d csp -c \"UPDATE csp_profile_listener SET enabled = false WHERE profile_name = 'default' AND protocol = 'newcamd' AND port = 15000;\"" >/dev/null 2>&1 || true
      sed -i '/^CSP_BOOTSTRAP_ADMIN_/d' /etc/csp/csp.env
      sed -i 's/^CSP_RUNTIME_ENABLED=false/CSP_RUNTIME_ENABLED=true/' /etc/csp/csp.env
    fi

    systemctl restart csp-app
    info "Waiting for csp-app to become ready on 127.0.0.1:8080..."
    local ready=0
    for _ in $(seq 1 30); do
      if curl -fsS http://127.0.0.1:8080/q/health/live >/dev/null 2>&1; then
        ready=1
        break
      fi
      sleep 2
    done
    if [ "$ready" -ne 1 ]; then
      err "csp-app did not respond on 127.0.0.1:8080. Recent logs:"
      journalctl -u csp-app -n 30 --no-pager || true
      exit 1
    fi

    # Repair or upgrade Nginx configuration if present
    if [ -f /etc/nginx/conf.d/csp-ng.conf ]; then
      local nginx_ver
      nginx_ver="$(nginx -v 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n 1 || printf "1.22.1")"
      if ! version_ge "$nginx_ver" "1.25.1" && grep -q "http2 on;" /etc/nginx/conf.d/csp-ng.conf; then
        sed -i 's/listen 443 ssl;/listen 443 ssl http2;/g' /etc/nginx/conf.d/csp-ng.conf
        sed -i 's/listen \[::\]:443 ssl;/listen [::]:443 ssl http2;/g' /etc/nginx/conf.d/csp-ng.conf
        sed -i '/http2 on;/d' /etc/nginx/conf.d/csp-ng.conf
      fi
      if ! grep -q "proxy_hide_header WWW-Authenticate;" /etc/nginx/conf.d/csp-ng.conf; then
        sed -i '/server_tokens off;/a \    proxy_hide_header WWW-Authenticate;' /etc/nginx/conf.d/csp-ng.conf
      fi
      nginx -t && systemctl enable --now nginx && systemctl reload nginx || true
    elif command -v nginx >/dev/null 2>&1; then
      configure_nginx "self-signed" "" ""
    fi
    ok "Central Server upgraded to ${CSP_VERSION} and verified active!"
  fi

  if [ -f /etc/systemd/system/csp-worker.service ]; then
    info "Upgrading Relay Worker (csp-worker) to ${CSP_VERSION}..."
    if grep -q "/usr/local/bin/csp-worker" /etc/systemd/system/csp-worker.service; then
      local arch glibc_flavor
      arch="$(detect_arch)"
      glibc_flavor="$(select_glibc_flavor)"
      local asset="csp-worker-${CSP_VERSION}-${arch}-${glibc_flavor}.tar.gz"
      download_and_verify_asset "$asset" "$TMP_DL"
      tar -xzf "${TMP_DL}/${asset}" -C "$TMP_DL"
      install -m 0755 "${TMP_DL}/csp-worker-${CSP_VERSION}-${arch}-${glibc_flavor}/csp-worker" /usr/local/bin/csp-worker
    else
      local asset="csp-worker-${CSP_VERSION}-shaded.jar"
      download_and_verify_asset "$asset" "$TMP_DL"
      install -m 0644 "${TMP_DL}/${asset}" /usr/local/lib/csp-ng/csp-worker.jar
    fi
    systemctl restart csp-worker || true
    ok "Relay Worker upgraded to ${CSP_VERSION}"
  fi
}

# ---------- Mode 7: Reset Admin Password ----------

run_mode_reset_admin() {
  require_root
  [ -f /etc/csp/csp.env ] || fatal "/etc/csp/csp.env not found (is Central Server installed?)"

  if [ -z "$ADMIN_PASS" ]; then
    ADMIN_PASS="$(openssl rand -hex 10)"
  fi
  info "Resetting Web Admin password for user '${ADMIN_USER}'..."

  su - postgres -c "psql -d csp -c \"DELETE FROM csp_user WHERE admin = true;\"" >/dev/null
  sed -i '/^CSP_BOOTSTRAP_ADMIN_/d' /etc/csp/csp.env
  cat >> /etc/csp/csp.env <<EOF
CSP_BOOTSTRAP_ADMIN_USER=${ADMIN_USER}
CSP_BOOTSTRAP_ADMIN_PASSWORD=${ADMIN_PASS}
EOF

  systemctl restart csp-app
  for _ in $(seq 1 30); do
    if curl -fsS http://127.0.0.1:8080/q/health/live >/dev/null 2>&1; then
      break
    fi
    sleep 2
  done
  sed -i '/^CSP_BOOTSTRAP_ADMIN_/d' /etc/csp/csp.env

  cat > /root/csp-admin-credentials.txt <<EOF
CSP-NG Web Admin Credentials (reset $(date -Is))
Username: ${ADMIN_USER}
Password: ${ADMIN_PASS}
EOF
  chmod 0600 /root/csp-admin-credentials.txt

  echo ""
  ok "Web Admin password reset successfully!"
  printf "  Admin Username : ${C_BOLD}%s${C_RESET}\n" "$ADMIN_USER"
  printf "  Admin Password : ${C_BOLD}%s${C_RESET}  (Saved in /root/csp-admin-credentials.txt)\n" "$ADMIN_PASS"
}

# ---------- Mode 6: Uninstall ----------

run_mode_uninstall() {
  require_root
  local confirm
  confirm="$(prompt_input "Stop and remove CSP-NG services and binaries? [y/N]" "N")"
  [[ "$confirm" =~ ^[Yy] ]] || { info "Aborted."; exit 0; }

  if systemctl list-unit-files | grep -q '^csp-app\.service'; then
    systemctl disable --now csp-app || true
    rm -f /etc/systemd/system/csp-app.service /etc/nginx/conf.d/csp-ng.conf
    systemctl reload nginx 2>/dev/null || true
    rm -rf /opt/csp
    ok "Removed csp-app service and /opt/csp"
  fi

  if systemctl list-unit-files | grep -q '^csp-worker\.service'; then
    systemctl disable --now csp-worker || true
    rm -f /etc/systemd/system/csp-worker.service /usr/local/bin/csp-worker
    rm -rf /usr/local/lib/csp-ng
    ok "Removed csp-worker service and binary"
  fi

  systemctl daemon-reload
  local purge_db
  purge_db="$(prompt_input "Also DROP PostgreSQL database 'csp' and delete /etc/csp & /var/lib/csp? (IRREVERSIBLE) [y/N]" "N")"
  if [[ "$purge_db" =~ ^[Yy] ]]; then
    su - postgres -c "psql -c 'DROP DATABASE IF EXISTS csp; DROP ROLE IF EXISTS csp;'" 2>/dev/null || true
    rm -rf /etc/csp /var/lib/csp
    userdel csp 2>/dev/null || true
    userdel csp-worker 2>/dev/null || true
    ok "Database and configuration directories purged"
  fi
}

# ---------- Main Entrypoint / Interactive Menu ----------

if [ -z "$MODE" ]; then
  echo ""
  printf "${C_BOLD}=====================================================================${C_RESET}\n"
  printf "${C_BOLD}   CSP-NG (CardServProxy NG) ${CSP_VERSION} — Automated Installer${C_RESET}\n"
  printf "   Host: $(uname -s) $(uname -m) | glibc $(detect_glibc_num) -> $(select_glibc_flavor)\n"
  printf "${C_BOLD}=====================================================================${C_RESET}\n"
  echo "  1) Install Central Server (PostgreSQL + Java 21 + Web UI + Nginx)"
  echo "  2) Install Native Relay Worker (No JVM — auto-selects glibc 2.31/2.36/2.41)"
  echo "  3) Install JVM Relay Worker (Java 21 + shaded JAR)"
  echo "  4) Generate mTLS Cluster Certificates (CA + Central + Worker bundle)"
  echo "  5) Upgrade existing installation to ${CSP_VERSION}"
  echo "  6) Uninstall CSP-NG"
  echo "  7) Reset Web Admin Password"
  echo ""
  choice="$(prompt_input "Select an option [1-7]" "1")"
  case "$choice" in
    1) MODE="central" ;;
    2) MODE="worker-native" ;;
    3) MODE="worker-jvm" ;;
    4) MODE="pki" ;;
    5) MODE="upgrade" ;;
    6) MODE="uninstall" ;;
    7) MODE="reset-admin" ;;
    *) fatal "Invalid choice: $choice" ;;
  esac
fi

case "$MODE" in
  central)        run_mode_central ;;
  worker-native)  run_mode_worker "native" ;;
  worker-jvm)     run_mode_worker "jvm" ;;
  pki)            run_mode_pki ;;
  reset-admin)    run_mode_reset_admin ;;
  upgrade)        run_mode_upgrade ;;
  uninstall)      run_mode_uninstall ;;
  *)              fatal "Unknown mode '$MODE' (run with --help)" ;;
esac
