#!/bin/sh
# Attends que les pairs interop soient résolubles (DNS du réseau docker-compose)
# avant de démarrer CardServProxy — le ClusteredCache échoue au démarrage si le
# fetch de la liste de pairs (peers.txt) ne peut pas résoudre les hôtes.
set -e

for host in csp-new oscam; do
  n=0
  until getent hosts "$host" >/dev/null 2>&1; do
    n=$((n + 1))
    if [ "$n" -ge 60 ]; then
      echo "FATAL: hôte '$host' injoignable par DNS après 60 tentatives"
      exit 1
    fi
    sleep 2
  done
  echo "hôte '$host' résolu — OK"
done

exec java -Dcom.bowman.cardserv.allowanyjvm=true -cp "build/classes:lib/*" com.bowman.cardserv.CardServProxy
