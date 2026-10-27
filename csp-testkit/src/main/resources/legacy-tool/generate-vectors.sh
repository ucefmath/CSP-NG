#!/bin/bash
set -euo pipefail

# Répertoire contenant ce script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Racine du dépôt legacy (worktree courant)
# SCRIPT_DIR = .../csp_new/csp-testkit/src/main/resources/legacy-tool
# 6 niveaux au-dessus = racine du worktree
LEGACY_ROOT="$SCRIPT_DIR"
for _ in {1..6}; do
  LEGACY_ROOT="$(dirname "$LEGACY_ROOT")"
done

# Classes compilées du legacy et librairies
LEGACY_CP="$LEGACY_ROOT/build/classes:$LEGACY_ROOT/lib/*"

# Destination par défaut : ressources de test du module csp-testkit
TARGET="${1:-$SCRIPT_DIR/../../../test/resources/vectors/cluster-udp.json}"

cd "$SCRIPT_DIR"

# Compile l'outil autonome contre le legacy Java 17
/usr/lib/jvm/temurin-17-jdk-amd64/bin/javac -cp "$LEGACY_CP" LegacyVectorGenerator.java

# Exécute le générateur
/usr/lib/jvm/temurin-17-jdk-amd64/bin/java -cp ".:$LEGACY_CP" LegacyVectorGenerator "$TARGET"

echo "Vecteurs golden disponibles dans : $TARGET"
