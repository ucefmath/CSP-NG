# Guide de Déploiement CSP-NG sur `server.example.com` (`csp.example.com`)

Ce document est le guide opératoire pour le déploiement de **CSP-NG** (CardServProxy NG) en production sur l'hôte `server.example.com` sous le domaine `csp.example.com`.

---

## 1. Vue d'ensemble de l'architecture cible

```text
[ Client Web / Navigateur ]
           │
           ▼ HTTPS (443) / HTTP (80 -> redirect 301)
┌───────────────────────────────────────────────────────────┐
│ NGINX (Reverse Proxy TLS + Gatekeeper Auth)               │
│                                                           │
│  ├─ /.well-known/acme-challenge/  ──> /var/www/html       │
│  ├─ /gatekeeper/                  ──> 127.0.0.1:18095     │
│  ├─ /q et /q/*                    ──> 404 (Bloqué)        │
│  └─ /admin, /api/admin, /         ──> auth_request        │
│                                            │              │
│                 ┌──────────────────────────┘              │
│                 ▼                                         │
│        [ recaptcha-gatekeeper ]                           │
│        (127.0.0.1:18095)                                  │
│                 │ (200 OK)                                │
│                 ▼                                         │
│        proxy_pass 127.0.0.1:8080                          │
└─────────────────────┬─────────────────────────────────────┘
                      │ HTTP loopback uniquement
                      ▼
┌───────────────────────────────────────────────────────────┐
│ Service systemd `csp-app` (Java 21 / Quarkus 3.20 LTS)    │
│ Utilisateur système : `csp:csp`                           │
│ Répertoire racine  : `/opt/csp/current` (root:csp 0755)   │
│ Configuration      : `/etc/csp/csp.env` (root:csp 0640)   │
└─────────────────────┬─────────────────────────────────────┘
                      │ TCP loopback (127.0.0.1:5432, TLS require)
                      ▼
┌───────────────────────────────────────────────────────────┐
│ PostgreSQL (Local)                                        │
│ Base : `csp` | Utilisateur : `csp` (SCRAM-SHA-256)        │
└───────────────────────────────────────────────────────────┘
```

---

## 2. Préflight (Vérifications préalables)

Avant toute modification sur le serveur, exécuter les vérifications suivantes :

1. **Vérifier l'état des services dépendants existants** :
   ```bash
   systemctl is-active postgresql nginx recaptcha-gatekeeper
   ```
   *Tous les services doivent répondre `active`.*

2. **Vérifier les ressources mémoire disponibles (RAM)** :
   ```bash
   free -h
   ```
   *Vérifier qu'au moins 1.5 Go de RAM disponible (`available`) est libre avant de procéder. Si la mémoire est insuffisante sur l'hôte mutualisé, libérer des ressources ou ajuster à la baisse `-Xmx` (ex. `-Xmx256m`) et `MemoryMax` (ex. `MemoryMax=512M`) dans `csp-app.service`.*

3. **Vérifier l'occupation des ports locaux** :
   ```bash
   ss -lntp '( sport = :8080 or sport = :18095 or sport = :5432 )'
   ```
   *Attendu :*
   - Port `18095` : écouté par `recaptcha-gatekeeper` sur `127.0.0.1`.
   - Port `5432` : écouté par PostgreSQL.
   - Port `8080` : **libre** (ne doit pas être écouté avant le premier démarrage de `csp-app`).

4. **Vérifier la résolution DNS publique** :
   ```bash
   getent hosts csp.example.com
   ```
   *L'IP retournée doit pointer vers l'adresse publique du serveur server.example.com.*

5. **Sauvegarder la configuration Nginx existante** :
   ```bash
   cp -a /etc/nginx /etc/nginx.bak.$(date +%Y%m%d%H%M%S)
   ```

6. **Vérifier la présence du JRE 21** :
   ```bash
   apt-cache policy openjdk-21-jre-headless
   ```
   *Si le paquet est disponible :*
   ```bash
   apt-get install -y openjdk-21-jre-headless
   java -version
   ```
   *Option de repli GraalVM 25 (si JRE 21 indisponible dans les dépôts APT) :*
   - Télécharger et installer GraalVM JDK 21/25 dans `/opt/graalvm-25`.
   - Ajuster `ExecStart` dans `/etc/systemd/system/csp-app.service` avec le binaire `/opt/graalvm-25/bin/java` et ajouter les flags JVM :
     `--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED`

---

## 3. Build & Packaging

> **IMPORTANT :** Le build Maven est exécuté sur la machine de build / poste local, **JAMAIS sur le serveur de production**.

1. **Compiler et packager l'application** depuis un commit validé par la CI :
   ```bash
   mvn -B clean package -pl csp-app -am -DskipTests
   ```
   *Le packaging Quarkus génère le dossier complet `csp-app/target/quarkus-app/`.*

2. **Générer le timestamp de release et préparer l'archive/transfert** :
   ```bash
   RELEASE_TS=$(date +%Y%m%d%H%M%S)
   echo "Packaging release: ${RELEASE_TS}"
   ```

3. **Transférer l'arborescence complète vers le serveur** :
   ```bash
   # Sur le serveur distant :
   mkdir -p /opt/csp/releases/${RELEASE_TS}

   # Depuis la machine locale (rsync de l'intégralité du dossier quarkus-app) :
   rsync -avz --checksum csp-app/target/quarkus-app/ root@server.example.com:/opt/csp/releases/${RELEASE_TS}/quarkus-app/
   ```

4. **Contrôle d'intégrité (sha256sum)** :
   ```bash
   # En local :
   sha256sum csp-app/target/quarkus-app/quarkus-run.jar

   # Sur le serveur :
   sha256sum /opt/csp/releases/${RELEASE_TS}/quarkus-app/quarkus-run.jar
   ```
   *Les empreintes SHA-256 doivent correspondre exactement.*

5. **Appliquer les permissions durcies et mettre à jour le lien symbolique `current`** :
   ```bash
   # Binaire et release en lecture seule pour le service (root:csp 0755 / fichiers read-only groupe)
   chmod -R u=rwX,g=rX,o= /opt/csp/releases/${RELEASE_TS}
   chown -R root:csp /opt/csp/releases /opt/csp/current
   ln -sfn /opt/csp/releases/${RELEASE_TS} /opt/csp/current
   ```

---

## 4. Provisioning PostgreSQL

1. **Générer le mot de passe fort pour l'utilisateur `csp` (sans l'afficher à l'écran)** :
   ```bash
   DB_PASSWORD=$(openssl rand -hex 24)
   ```

2. **Créer le rôle et la base de données avec binding de variable psql** :
   ```bash
   sudo -u postgres psql -v ON_ERROR_STOP=1 -v pw="$DB_PASSWORD" <<'SQL'
   CREATE ROLE csp WITH LOGIN PASSWORD :'pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
   CREATE DATABASE csp WITH OWNER csp ENCODING 'UTF8';
   \c csp
   ALTER SCHEMA public OWNER TO csp;
   GRANT ALL ON SCHEMA public TO csp;
   SQL
   ```
   *(Note : `ALTER SCHEMA public OWNER TO csp;` est indispensable sous PostgreSQL 15+ pour autoriser l'exécution des migrations Flyway sans privilège superuser).*

3. **Vérifier et ajuster l'authentification `pg_hba.conf`** :
   Inspecter le fichier `pg_hba.conf` (généralement dans `/etc/postgresql/<version>/main/pg_hba.conf`) et s'assurer qu'une règle autorise la connexion locale de l'utilisateur `csp` avec SCRAM-SHA-256 :
   ```text
   # /etc/postgresql/<version>/main/pg_hba.conf
   host    csp             csp             127.0.0.1/32            scram-sha-256
   ```
   Si la ligne a été ajoutée, recharger PostgreSQL :
   ```bash
   systemctl reload postgresql
   ```

4. **Tester la connexion locale (sans afficher le mot de passe)** :
   ```bash
   PGPASSWORD="${DB_PASSWORD}" psql -h 127.0.0.1 -U csp -d csp -c "SELECT current_user, current_database();"
   ```

---

## 5. Provisioning Système

1. **Créer l'utilisateur et le groupe système `csp`** :
   ```bash
   useradd --system --user-group --no-create-home --home-dir /opt/csp --shell /usr/sbin/nologin csp
   ```

2. **Créer l'arborescence des répertoires et appliquer les permissions strictes** :
   ```bash
   mkdir -p /opt/csp/releases
   mkdir -p /etc/csp/secrets
   mkdir -p /var/lib/csp

   # /opt/csp appartient à root:csp (le service ne peut pas modifier ses propres binaires)
   chown -R root:csp /opt/csp
   chmod 0755 /opt/csp

   chown -R root:csp /etc/csp
   chmod 0750 /etc/csp

   chown -R csp:csp /etc/csp/secrets
   chmod 0700 /etc/csp/secrets

   chown -R csp:csp /var/lib/csp
   chmod 0750 /var/lib/csp
   ```

3. **Installer l'unité systemd `csp-app.service`** :
   ```bash
   cp deploy/csp-app.service /etc/systemd/system/csp-app.service
   chmod 0644 /etc/systemd/system/csp-app.service

   # Valider la syntaxe de l'unité
   systemd-analyze verify /etc/systemd/system/csp-app.service

   # Recharger la configuration de systemd
   systemctl daemon-reload
   ```

---

## 6. Séquence de Déploiement Complète

> ⚠️ **ATTENTION — PIÈGE DU LISTENER NEWCAMD :**
> La migration Flyway V1 sème un listener `newcamd` sur `0.0.0.0:15000` avec `des_key_ref='env:CSP_PROFILE_DES_KEY'`.
> Si `CSP_RUNTIME_ENABLED=true` au 1er démarrage, l'application fait un fail-fast immédiat car `CSP_PROFILE_DES_KEY` n'est pas définie (et si elle était définie, le port 15000 serait exposé publiquement).
> Il est **IMPÉRATIF** de démarrer d'abord avec `CSP_RUNTIME_ENABLED=false`, de désactiver ce listener en base, puis de réactiver le runtime métier.

### Étape 1 : Préflight
Exécuter toutes les commandes de la section [2. Préflight](#2-préflight-vérifications-préalables).

### Étape 2 : Provisioning PostgreSQL
Exécuter les scripts de création d'utilisateur et base de la section [4. Provisioning PostgreSQL](#4-provisioning-postgresql).

### Étape 3 : Provisioning Système
Créer l'utilisateur, les dossiers et installer l'unité systemd selon la section [5. Provisioning Système](#5-provisioning-système).

### Étape 4 : Build, Transfert et Symlink
Déployer la release et pointer `/opt/csp/current` selon la section [3. Build & Packaging](#3-build--packaging).

### Étape 5 : Configuration de l'environnement `/etc/csp/csp.env`
1. Copier le modèle :
   ```bash
   cp deploy/csp.env.example /etc/csp/csp.env
   chown root:csp /etc/csp/csp.env
   chmod 0640 /etc/csp/csp.env
   ```
2. Injecter les secrets sans les exposer dans le terminal :
   ```bash
   # Mot de passe DB (généré à l'étape 2)
   sed -i "s/CSP_DB_PASSWORD=<GENERATE_ME>/CSP_DB_PASSWORD=${DB_PASSWORD}/" /etc/csp/csp.env
   unset DB_PASSWORD

   # Mot de passe administrateur bootstrap initial
   BOOTSTRAP_ADMIN_PW=$(openssl rand -hex 12)
   sed -i "s/CSP_BOOTSTRAP_ADMIN_PASSWORD=<GENERATE_ME>/CSP_BOOTSTRAP_ADMIN_PASSWORD=${BOOTSTRAP_ADMIN_PW}/" /etc/csp/csp.env

   echo "⚠️ Notez ce mot de passe administrateur immédiatement, il ne sera plus affiché : ${BOOTSTRAP_ADMIN_PW}"
   unset BOOTSTRAP_ADMIN_PW
   ```
   *(Recommandation : exécuter `history -c` après la session si des commandes sensibles ont été tapées).*
3. Vérifier que **`CSP_RUNTIME_ENABLED=false`** est bien positionné dans `/etc/csp/csp.env`.

### Étape 6 : Premier démarrage et Bootstrap Admin
1. Démarrer le service :
   ```bash
   systemctl enable --now csp-app
   ```
2. Suivre les logs de démarrage :
   ```bash
   journalctl -u csp-app -f
   ```
   *Vérifier :*
   - Application des migrations Flyway (création des tables).
   - Création du compte administrateur bootstrap initial.
   - Démarrage de Quarkus sur `127.0.0.1:8080`.
3. Valider la santé locale de l'application :
   ```bash
   curl -f http://127.0.0.1:8080/q/health/ready
   ```
4. **Nettoyage du bootstrap :**
   Éditer `/etc/csp/csp.env` et **supprimer** les lignes `CSP_BOOTSTRAP_ADMIN_USER` et `CSP_BOOTSTRAP_ADMIN_PASSWORD`.

### Étape 7 : Neutralisation du listener newcamd et activation du runtime
1. Désactiver le listener semé dans la base de données :
   ```bash
   sudo -u postgres psql -d csp -c "UPDATE csp_profile_listener SET enabled = false WHERE profile_name = 'default' AND protocol = 'newcamd' AND port = 15000;"
   ```
2. **Vérifier formellement l'état de désactivation en base (Rowcount check)** :
   ```bash
   sudo -u postgres psql -d csp -t -c "SELECT enabled FROM csp_profile_listener WHERE profile_name='default' AND protocol='newcamd' AND port=15000;"
   ```
   *La commande **DOIT** retourner `f` (false). Si la sortie est vide ou égale à `t`, **NE PAS** activer `CSP_RUNTIME_ENABLED`, investiguer d'abord.*

3. Dans `/etc/csp/csp.env`, modifier la ligne :
   ```bash
   CSP_RUNTIME_ENABLED=true
   ```
4. Redémarrer le service applicatif :
   ```bash
   systemctl restart csp-app
   ```
5. Vérifier que l'application est opérationnelle et que le port 15000 n'est **PAS** ouvert :
   ```bash
   curl -f http://127.0.0.1:8080/q/health/live
   ss -lntp '( sport = :15000 )'
   ```
   *La commande `ss` ne doit retourner aucune ligne d'écoute.*

### Étape 8 : Déploiement du vhost Nginx ACME temporaire
1. Copier la configuration ACME bootstrap :
   ```bash
   cp deploy/nginx/csp.example.com-acme-bootstrap.conf /etc/nginx/sites-available/csp.example.com.conf
   ln -sf /etc/nginx/sites-available/csp.example.com.conf /etc/nginx/sites-enabled/csp.example.com.conf
   ```
2. Tester et recharger Nginx :
   ```bash
   nginx -t && systemctl reload nginx
   ```
3. Vérifier la réponse HTTP 503 :
   ```bash
   curl -I http://csp.example.com/
   ```
   *Doit retourner `HTTP/1.1 503 Service Temporarily Unavailable`.*

### Étape 9 : Génération du certificat TLS avec Certbot
1. Tester d'abord en mode simulation (`--dry-run`) :
   ```bash
   certbot certonly --dry-run --webroot -w /var/www/html -d csp.example.com
   ```
2. Exécuter l'émission réelle du certificat :
   ```bash
   certbot certonly --webroot -w /var/www/html -d csp.example.com \
     --non-interactive --agree-tos -m admin@example.com \
     --keep-until-expiring \
     --deploy-hook 'systemctl reload nginx'
   ```

### Étape 10 : Déploiement du vhost Nginx final
1. Remplacer par la configuration reverse proxy TLS définitive :
   ```bash
   cp deploy/nginx/csp.example.com.conf /etc/nginx/sites-available/csp.example.com.conf
   ```
2. Tester et recharger Nginx :
   ```bash
   nginx -t && systemctl reload nginx
   ```

### Étape 11 : Tests de fumée
Exécuter l'ensemble des validations décrites dans la section [7. Tests de Fumée](#7-tests-de-fumée).

---

## 7. Tests de Fumée

Exécuter les vérifications exhaustives suivantes :

1. **Santé applicative locale (Loopback)** :
   ```bash
   curl -s http://127.0.0.1:8080/q/health/live | grep -oE '"status"\s*:\s*"UP"'
   curl -s http://127.0.0.1:8080/q/health/ready | grep -oE '"status"\s*:\s*"UP"'
   ```

2. **Vérification du confinement réseau (Ports non exposés à l'extérieur)** :
   ```bash
   # Sur le serveur :
   ss -lntp '( sport = :8080 or sport = :15000 )'
   # Doit être lié uniquement à 127.0.0.1:8080 pour csp-app, aucun listener sur 15000
   ```
   *Depuis un poste externe :*
   ```bash
   curl -m 3 http://csp.example.com:8080/ || echo "Port 8080 inaccessible publiquement : OK"
   curl -m 3 http://csp.example.com:15000/ || echo "Port 15000 inaccessible publiquement : OK"
   ```

3. **Comportement HTTPS & Nginx externe** :
   - **Redirection HTTP vers HTTPS** :
     ```bash
     curl -sI http://csp.example.com/ | grep -i 'Location: https://csp.example.com/'
     ```
   - **Blocage des endpoints techniques Quarkus (`/q` et `/q/*`)** :
     ```bash
     curl -sI https://csp.example.com/q/health | grep '404 Not Found'
     curl -sI https://csp.example.com/q | grep '404 Not Found'
     ```
   - **Challenge reCAPTCHA Gatekeeper sur interface Web sans cookie (302 vers challenge)** :
     ```bash
     curl -sI https://csp.example.com/admin/index.html | grep -E '^HTTP/.* 302'
     curl -sI https://csp.example.com/admin/index.html | grep -i '^Location:.*gatekeeper/challenge'
     ```
   - **Rejet JSON sur l'API sans cookie Gatekeeper (403 Forbidden)** :
     ```bash
     curl -sI https://csp.example.com/api/admin/me | grep '403 Forbidden'
     curl -s https://csp.example.com/api/admin/me | grep 'GATEKEEPER_REQUIRED'
     ```

4. **Vérification du parcours complet au navigateur** :
   - Naviguer sur `https://csp.example.com/`.
   - Résoudre le challenge reCAPTCHA Gatekeeper.
   - Accéder à la page de connexion de la console admin.
   - S'authentifier avec l'administrateur bootstrap.
   - Vérifier dans les DevTools que le cookie de session est bien positionné avec les attributs `Secure`, `HttpOnly`, `SameSite=Strict`.

5. **Non-régression des autres hôtes virtuels colocalisés** :
   ```bash
   curl -sI https://other-vhost.example.com/ | grep 'HTTP/'
   ```

6. **Test de résilience / Redémarrage du service** :
   ```bash
   systemctl restart csp-app
   # Vérifier le temps de démarrage (< 45s) et le statut UP
   sleep 5
   systemctl is-active csp-app
   curl -f http://127.0.0.1:8080/q/health/ready
   ```

7. **Vérification de l'absence de fuite de secrets dans les journaux** :
   ```bash
   journalctl -u csp-app | grep -iE 'password=|encryption-key' || echo "Aucun secret dans les logs : OK"
   ```

---

## 8. Plan de Rollback & Cas d'Erreurs

### Cas A : Le service `csp-app` ne démarre pas lors du 1er déploiement
- **Diagnostic :** `journalctl -u csp-app -e --no-pager`
- **Impact externe :** Nul (Nginx n'a pas encore été configuré pour relayer le trafic public).
- **Actions :** Corriger `/etc/csp/csp.env` (droits, format `KEY=VALUE`, syntaxe JDBC, permissions PostgreSQL).

### Cas B : Régression fonctionnelle après une mise à jour applicative
- **Actions :** Re-pointer le symlink `current` vers la release précédente et redémarrer :
  ```bash
  ln -sfn /opt/csp/releases/<PREVIOUS_TIMESTAMP> /opt/csp/current
  systemctl restart csp-app
  ```

### Cas C : Erreur de configuration Nginx
- **Comportement :** `nginx -t` prévient tout rechargement d'une configuration invalide ; les vhosts existants continuent de fonctionner avec l'ancienne configuration en mémoire.
- **Restauration d'urgence :**
  ```bash
  rm -f /etc/nginx/sites-enabled/csp.example.com.conf
  nginx -t && systemctl reload nginx
  # Ou restauration complète de la sauvegarde préflight :
  # cp -a /etc/nginx.bak.<TIMESTAMP>/* /etc/nginx/ && nginx -t && systemctl reload nginx
  ```

### Cas D : Échec d'émission Certbot
- **Actions :** Ne **JAMAIS** activer `csp.example.com.conf` sans certificat valide sous peine de bloquer le démarrage global de Nginx. Laisser le vhost temporaire `csp.example.com-acme-bootstrap.conf` actif (retourne 503) et investiguer (DNS, connectivité HTTP port 80, règles pare-feu).

### Cas E : Procédure de désinstallation complète (Abandon)
Exécuter les commandes suivantes dans l'ordre pour nettoyer intégralement l'hôte sans impacter les autres applications :
```bash
# 1. Arrêter et désactiver le service
systemctl disable --now csp-app
rm -f /etc/systemd/system/csp-app.service
systemctl daemon-reload

# 2. Supprimer la configuration Nginx
rm -f /etc/nginx/sites-enabled/csp.example.com.conf /etc/nginx/sites-available/csp.example.com.conf
nginx -t && systemctl reload nginx

# 3. Supprimer le certificat Let's Encrypt
certbot delete --cert-name csp.example.com

# 4. Supprimer la base et l'utilisateur PostgreSQL (ATTENTION : cibler STRICTEMENT la base `csp`)
sudo -u postgres psql -c "DROP DATABASE IF EXISTS csp;"
sudo -u postgres psql -c "DROP ROLE IF EXISTS csp;"

# 5. Nettoyer les fichiers applicatifs et l'utilisateur système
rm -rf /opt/csp /etc/csp /var/lib/csp
userdel csp
```

---

## 9. Notes et Limitations Connues

1. **Mode SSL PostgreSQL (`CSP_DB_SSL_MODE=require`)** :
   - Valeur par défaut configurée à `require` pour garantir le chiffrement du transport TLS entre l'application et la base locale.
   - Si le premier démarrage échoue en raison de certificats auto-signés système non validés par le client réactif Vert.x, ajouter `QUARKUS_DATASOURCE_REACTIVE_TRUST_ALL=true` dans `/etc/csp/csp.env`.
   - En dernier recours uniquement (trafic 100% loopback colocalisé), basculer vers `CSP_DB_SSL_MODE=disable`.
   - *Piste de durcissement futur :* Déploiement d'une autorité de certification locale interne (mTLS) ou socket Unix dédié.

2. **Dépendance au service `recaptcha-gatekeeper`** :
   - Le service `recaptcha-gatekeeper.service` (écoutant sur `127.0.0.1:18095`) agit comme gatekeeper d'authentification pour toutes les requêtes frontales Nginx via la directive `auth_request /gatekeeper-verify`.
   - Il constitue un point de passage obligatoire pour accéder à l'application. Ce service est managé et monitoré séparément sur le serveur `server.example.com`.
