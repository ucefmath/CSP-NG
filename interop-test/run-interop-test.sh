#!/usr/bin/env bash
# ---------------------------------------------------------------------------------------------
# run-interop-test.sh — Rejoue le test d'interopérabilité L3b (plan §5.3) en une commande.
#
#   1. (re)build du jar runner csp-app si absent (Maven hors-ligne, uber-jar)
#   2. staging du contexte de build OSCam (copie de $OSCAM_SRC — lecture seule respectée)
#   3. docker compose build + up des 3 nœuds (csp-legacy, csp-new, oscam)
#   4. exécution du harness (injecteur + assertions) dans le conteneur `harness`
#   5. capture des logs et des résultats (results/)
#   6. teardown (down) — le verdict est porté par le code de sortie du harness
#
# Usage : ./run-interop-test.sh [--keep-up] [--no-build]
#   --keep-up   : ne fait pas de `docker compose down` en fin de run (debug)
#   --no-build  : ne relance pas `docker compose build` (images déjà construites)
# ---------------------------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")"

KEEP_UP=0
NO_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --keep-up) KEEP_UP=1 ;;
    --no-build) NO_BUILD=1 ;;
    *) echo "Option inconnue: $arg"; exit 2 ;;
  esac
done

INTEROP_DIR="$(pwd)"
CSP_NEW="$(cd "$INTEROP_DIR/.." && pwd)"
OSCAM_SRC="${OSCAM_SRC:-$CSP_NEW/../oscam}"
RESULTS_DIR="$INTEROP_DIR/results"

echo "=== [1/6] Vérifications environnement ==="
command -v docker >/dev/null || { echo "FATAL: docker absent"; exit 1; }
docker info >/dev/null 2>&1 || { echo "FATAL: daemon docker injoignable"; exit 1; }
[ -d "$OSCAM_SRC" ] || { echo "FATAL: $OSCAM_SRC absent"; exit 1; }

echo "=== [2/6] Build du jar csp-app (uber-jar) si nécessaire ==="
JAR="$CSP_NEW/csp-app/target/csp-app-1.0.2-runner.jar"
if [ ! -f "$JAR" ]; then
  echo "Jar absent -> build Maven (hors-ligne) :"
  mvn -o -f "$CSP_NEW/pom.xml" -pl csp-app -am install -DskipTests \
      -Dquarkus.package.type=uber-jar
fi
[ -f "$JAR" ] || { echo "FATAL: jar introuvable après build ($JAR)"; exit 1; }

echo "=== [3/6] Staging du contexte de build OSCam ==="
mkdir -p "$INTEROP_DIR/context-oscam"
find "$INTEROP_DIR/context-oscam" -mindepth 1 -delete 2>/dev/null || true
# Copie des sources OSCam (hors .git) — $OSCAM_SRC n'est jamais modifié
tar -C "$OSCAM_SRC" --exclude=.git -cf - . | tar -C "$INTEROP_DIR/context-oscam" -xf -
# Dockerfile + configs runtime du test
cp "$INTEROP_DIR/Dockerfile.oscam" "$INTEROP_DIR/context-oscam/Dockerfile.oscam"
mkdir -p "$INTEROP_DIR/context-oscam/config"
cp "$INTEROP_DIR/config/oscam.conf" "$INTEROP_DIR/context-oscam/config/"
cp "$INTEROP_DIR/config/oscam.server" "$INTEROP_DIR/context-oscam/config/"
cp "$INTEROP_DIR/config/constant.cw" "$INTEROP_DIR/context-oscam/config/"

echo "=== [4/6] Build et démarrage des nœuds ==="
if [ "$NO_BUILD" -eq 0 ]; then
  docker compose build
fi
docker compose up -d csp-legacy csp-new oscam

echo "=== [4b/6] Redémarrage de csp-new (registre de pairs + cache vierges) ==="
# Nécessaire pour le scénario négatif « RESENDREQ depuis une IP non enregistrée comme
# pair » : csp-new accumule les pairs dynamiques (y compris les conteneurs harness des
# runs précédents) sans expiration ; un redémarrage garantit un registre propre.
docker compose restart csp-new
echo "Attente du redémarrage de csp-new (12 s)..."
sleep 12

echo "=== [5/6] Exécution du harness ==="
mkdir -p "$RESULTS_DIR"
set +e
docker compose run --rm harness python3 -u /harness/harness.py 2>&1 | tee "$RESULTS_DIR/harness.out"
RC=${PIPESTATUS[0]}
set -e

echo "=== [6/6] Capture des logs ==="
docker compose logs csp-legacy > "$RESULTS_DIR/csp-legacy.log" 2>&1 || true
docker compose logs csp-new    > "$RESULTS_DIR/csp-new.log" 2>&1 || true
docker compose logs oscam      > "$RESULTS_DIR/oscam.log" 2>&1 || true
# OSCam écrit dans son fichier de log interne (/oscam/log/oscam.log) — le copier
docker cp interop-oscam:/oscam/log/oscam.log "$RESULTS_DIR/oscam-internal.log" 2>/dev/null || true
docker compose ps > "$RESULTS_DIR/ps.txt" 2>&1 || true

if [ "$KEEP_UP" -eq 0 ]; then
  docker compose down
else
  echo "(--keep-up : conteneurs laissés en place)"
fi

echo ""
echo "==================================================================="
echo " Harness terminé : code de sortie $RC"
echo " Résultats détaillés : $RESULTS_DIR/harness.out"
echo " Logs : $RESULTS_DIR/{csp-legacy,csp-new,oscam}.log"
echo "==================================================================="
exit "$RC"
