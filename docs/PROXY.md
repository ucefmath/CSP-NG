# Proxys SOCKS5 — Gestion et routage des connecteurs (Plan P2, V5)

## 1. Vue d'ensemble

CSP-NG supporte l'acheminement des connexions des connecteurs CWS (Newcamd, CCcam,
Radegast) via des proxys **SOCKS5 nommés**, gérés comme entités de premier ordre
(table `csp_proxy`, migration Flyway `V5__csp_proxy.sql`).

Caractéristiques principales :

| Aspect | Comportement |
|---|---|
| Type de proxy | `socks5` uniquement (CHECK SQL + validation Java) |
| Association | Optionnelle par connecteur : `csp_connector.proxy_name` (FK nullable, `ON DELETE RESTRICT`) |
| Sans proxy | Connexion directe, comportement legacy inchangé (`proxy_name = NULL`) |
| DNS | Avec proxy : résolution de la **destination déléguée au proxy** (ATYP domain), **aucune résolution DNS locale**. Le host du proxy est résolu localement (normal). |
| Secrets | Uniquement des références `env:` ou `file:` — jamais de mot de passe en clair en base, dans l'API, l'UI ou les logs |
| Fail-closed | Proxy absent, désactivé ou secret non résoluble → le connecteur **ne démarre pas** ; jamais de repli en connexion directe |
| Kill switch | Désactiver un proxy coupe les sessions des connecteurs attachés et bloque leur reconnexion ; réactiver relance les connecteurs |

## 2. Modèle de données (V5)

```sql
CREATE TABLE csp_proxy (
    name            text PRIMARY KEY CHECK (name ~ '^[A-Za-z0-9_.\-]{1,64}$'),
    enabled         boolean NOT NULL DEFAULT true,
    type            text NOT NULL DEFAULT 'socks5' CHECK (type IN ('socks5')),
    host            text NOT NULL,          -- IP ou DNS, sans schéma ni userinfo
    port            integer NOT NULL CHECK (port BETWEEN 1 AND 65535),
    username        text,                   -- appariés : tous deux NULL ou non-NULL
    password_ref    text,                   -- env:VAR ou file:/path UNIQUEMENT
    -- PAS de colonne timeout : le budget T est porté par le CONNECTEUR
    -- (csp_connector.connect_timeout_ms, validé 1000..300000 sur le chemin proxy)
    description     text,
    version         bigint NOT NULL DEFAULT 1,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text
);

ALTER TABLE csp_connector
    ADD COLUMN proxy_name text REFERENCES csp_proxy(name) ON DELETE RESTRICT;

CREATE INDEX idx_connector_proxy_name ON csp_connector(proxy_name)
    WHERE proxy_name IS NOT NULL;
```

Règles :

- **Nom immuable** (PK). Un PUT avec un nom différent du chemin → 400.
- **Auth appariée** : `username` et `password_ref` fournis ensemble ou aucun (CHECK SQL + Java).
- **`password_ref`** : doit commencer par `env:` ou `file:` (CHECK SQL + Java). La valeur du
  secret est résolue uniquement au runtime, sur worker thread, avant enregistrement du
  connecteur dans le manager.
- **Aucun seed, aucune adresse hardcodée** dans la migration.
- Trigger `csp_config_notify` : toute modification de `csp_proxy` déclenche un
  `NOTIFY csp_config_changed` → recharge de la config par `ConfigWatcher` →
  réconciliation du lifecycle.
- Versionnement optimistic-locking : création attendue avec `version=1` ;
  mismatch → 409 ; absent avec autre version → 404 ; audit `csp_audit_log`.

## 3. API REST admin

`/api/admin/config/proxies` — `@RolesAllowed("admin")`, mêmes filtres HTTPS/CSRF que
le reste de `/api/admin`.

| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/api/admin/config/proxies` | Liste avec compteur d'usage (`connectorCount`) |
| `GET` | `/api/admin/config/proxies/{name}` | Détail + usage |
| `PUT` | `/api/admin/config/proxies/{name}` | Création (`version=1`) ou update (optimistic lock) |
| `DELETE` | `/api/admin/config/proxies/{name}?version=N` | Suppression — **409 `PROXY_IN_USE`** si référencé |
| `GET` | `/api/admin/config/proxies/usage/{name}` | Compteur de connecteurs attachés |

L'association proxy↔connecteur se fait via le connecteur
(`PUT /api/admin/config/connectors/{name}` avec le champ `proxyName` — `null` = direct).
Le DTO connecteur expose `proxyName` ; un proxy inconnu est rejeté par le validateur
au reload ; un proxy **désactivé** référencé est **accepté** (kill switch intentionnel).

Il n'existe **pas** d'endpoint de "test de connexion arbitraire" — le probe d'import
cccam.cfg reste le seul chemin de test réseau, avec proxy optionnel.

## 4. UI admin

Onglet **Proxys** (route `#/proxys`) :

- Table : nom, état (actif/désactivé), host:port, auth, compteur de connecteurs
  attachés, version, actions.
- Dialogue CRUD : nom immutable en édition, `passwordRef` uniquement (env:/file:),
  auth appariée. Pas de champ timeout (porté par le connecteur).
- **Désactivation** : confirmation explicite — "coupe leurs sessions et bloque leur
  reconnexion — AUCUN repli en connexion directe", avec saisie du nom.
- **Suppression** : refusée si `connectorCount > 0` (détachez les connecteurs d'abord) ;
  le 409 `PROXY_IN_USE` du backend est également géré.
- Dialogue connecteur : sélecteur **« — Connexion directe — »** vs liste des proxys ;
  colonne **Proxy** dans la table connecteurs (`direct` si aucun).

## 5. Runtime

### 5.1 Timeouts

**Aucun timeout sur le proxy** (pas de colonne `connect_timeout_ms` sur `csp_proxy`,
pas de champ dans l'API ni l'UI). Le budget de connexion **T est celui du
CONNECTEUR** — `csp_connector.connect_timeout_ms` — validé borné
**1 000..300 000 ms** sur le chemin proxy (clampé par `ConnectorLifecycleManager`),
appliqué aux DEUX phases :

1. TCP connect **vers le proxy** : `NetClientOptions.setConnectTimeout(T)`
2. Handshake SOCKS5 (+ auth) : `ProxyOptions.setConnectTimeout(Duration.ofMillis(T))`

Chaque phase a son propre budget — **la durée totale peut atteindre 2×T**.
Les options proxy runtime (`NetworkProxyOptions`) ne portent PAS de timeout :
elles n'écrasent jamais le T du connecteur. **Sans proxy (connexion directe),
les options restent les DÉFAUTS historiques de Vert.x** — y compris le connect
timeout par défaut de 60 s : le champ `connectTimeoutMs` du connecteur est
IGNORÉ sur le chemin direct (aucun override), comportement identique au
`createNetClient()` d'avant P2. Pour le probe d'import, la durée dédiée est
`csp.import.probe-timeout-ms` (défaut 15 000, clampée 1 000..300 000) appliquée
aux deux phases.

Vérifié par lecture de source Vert.x 4.5.25 (`ChannelProvider.handleProxyConnect` :
`resolveAddress(proxyHost)` local + `bootstrap.resolver(NoopAddressResolverGroup)`)
et Netty 4.1.130 (`Socks5ProxyHandler.sendConnectCommand` : `isUnresolved()` →
`Socks5AddressType.DOMAIN`).

### 5.2 DNS

- **Host du proxy** : résolution DNS locale normale (Vert.x `resolveAddress`).
- **Destination (cardserver)** : envoyée en ATYP domain au proxy — le **proxy** fait la
  résolution DNS. Aucune résolution locale sur l'hôte CSP (testé :
  `ConnectorSocks5ProxyTest.radegastTunnelAtypDomain` avec `upstream.invalid`).
- `nonProxyHosts` : **non supporté volontairement** (exigence du plan) — tous les
  connecteurs avec `proxyName` passent par leur proxy, point.

### 5.3 Fail-closed strict

Un connecteur avec `proxyName` ne démarre **que si** :

1. le proxy existe dans le snapshot ;
2. le proxy est `enabled` ;
3. ses secrets (`passwordRef`) se résolvent.

Sinon : `IllegalStateException` dans `ConnectorLifecycleManager.resolveProxyOptions`,
le connecteur n'est pas enregistré (et l'ancien est retiré) — **jamais de direct**.
La résolution des secrets proxy se fait sur worker thread **avant** l'enregistrement
dans `CwsConnectorManager` (comme pour les secrets connecteur).

### 5.4 Lifecycle (diff et réconciliation)

`ConnectorLifecycleManager` diff sur **deux axes** :

1. `ConnectorConfig` inchangé (égalité record) ;
2. **signature proxy** (version, enabled, host, port, auth) inchangée.

Conséquences :

- Changement du connecteur → restart du connecteur (comportement existant).
- Changement du proxy (config/version/enabled) → restart **des seuls connecteurs
  attachés** ; les connecteurs directs et ceux attachés à d'autres proxys ne sont pas
  touchés.
- Proxy désactivé → sessions fermées, reconnexion bloquée (kill switch) ; réactivé →
  relance au reconcile suivant.
- Fermeture de l'ancien `NetClient` avant remplacement ; `stopped` bloque le reconnect
  (vérifié par `ConnectorSocks5ProxyTest.reconnectViaProxyThenStopBlocksReconnect`).

### 5.5 Rotation des secrets

Le mot de passe proxy est résolu depuis `password_ref` à **chaque** réconciliation.
Une rotation du secret (changement de la valeur `env:`/`file:`) est prise en compte
au reload suivant — **sans bump de version du proxy**. En revanche, si vous changez
la **référence** (`password_ref` elle-même), le bump de version du proxy déclenche le
restart des connecteurs attachés. **Attention** : une rotation de valeur sans reload ne
reconnecte pas les sessions existantes — planifier un save proxy (bump version) ou un
reload pour forcer la reprise avec le nouveau secret.

## 6. Import cccam.cfg avec proxy

`POST /api/admin/connectors/import?profile=P&proxy=NAME` (le paramètre `proxy` est
optionnel) :

- **Sans proxy** : comportement legacy inchangé — résolution DNS locale, digest de
  dédup **v1** (`csp-import-dedup-v1:proto:IPs:port:user:secret`, SHA-256).
- **Avec proxy** (validé : doit exister et être activé, sinon 400) :
  - **Aucune résolution DNS locale** (délégée au proxy, ATYP domain) ;
  - digest de dédup **v2** : `csp-import-dedup-v2:proto:proxyName:hostnameNormalisé:
    port:user:secret` (SHA-256) — le digest n'expose ni le secret ni lui-même ;
  - **dédup différente direct/proxy** : la même ligne importée via proxy n'est PAS un
    doublon d'un import direct précédent (et réciproquement) ;
  - probe via le proxy (auth proxy résolue une fois, sur worker, avant les probes) ;
  - persist avec `proxyName` sur le connecteur créé ;
  - **fail-closed** : proxy absent/désactivé/secret KO → toutes les lignes
    `PROBE_FAILED`, rien n'est persisté, jamais de direct.
- Concurrence RÉELLE bornée : N lanes = `csp.import.max-concurrent-probes` (défaut 4,
  clampé 1..64) — probes simultanés jusqu'à N, jamais plus ; résultats triés par ligne,
  collections thread-safe, noms uniques garantis sous concurrence ;
  limites existantes (taille fichier, cooldown, `max-lines` défaut 100, configurable
  via env `CSP_IMPORT_MAX_LINES`) inchangées.
- Parser : commentaires inline `#` acceptés **après** les champs obligatoires C/N et
  **hors accolades** ; les mots de passe `#foo` et `pa#ss` sont préservés.

## 7. Exemple de configuration

Aucun proxy n'est pré-configuré (pas de seed). Exemple manuel (réseau Docker hôte
`172.20.0.1:1080` — exemple **non** inclus en base) :

```bash
# 1. Créer le proxy (auth par référence d'environnement, jamais de mot de passe clair)
curl -u admin:... -X PUT https://csp.example.com/api/admin/config/proxies/lan-socks5 \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "lan-socks5",
    "enabled": true,
    "host": "172.20.0.1",
    "port": 1080,
    "username": "pxuser",
    "passwordRef": "env:PROXY_PASSWORD",
    "version": 1
  }'

# 2. Exporter le secret (référencé, jamais en base)
export PROXY_PASSWORD='...'

# 3. Associer un connecteur existant
curl -u admin:... -X PUT .../api/admin/config/connectors/mon-connecteur \
  -d '{ ..., "proxyName": "lan-socks5", "version": N }'
```

Pour un connecteur : `"proxyName": null` (ou absent) = connexion directe.

## 8. Limitations connues

- Type `socks5` seul (HTTP/SOCKS4 rejetés) ; extension future = `ALTER` du CHECK.
- `nonProxyHosts` non supporté (choix du plan P2).
- Le budget de timeout est **par phase** (TCP + handshake, T = connecteur), total ≤ 2×T.
- La rotation de la **valeur** d'un secret ne redémarre pas les connecteurs tant que
  le proxy n'est pas re-sauvé (bump version) ou que la config n'est pas rechargée.
- Le cluster CspV2 (`CspV2Manager`) est explicitement **hors périmètre** du plan P2.
- Import : le choix du proxy est un paramètre de requête REST ; il n'y a pas (encore)
  de sélecteur proxy dans une UI d'import (l'UI d'import n'existe pas dans l'admin).

## 9. Déploiement futur (hors périmètre P2)

- Endpoints REST "test de connexion proxy" dédiés (aujourd'hui couverts par l'import
  avec proxy et par le lifecycle).
- Sélecteur proxy dans une future UI d'import.
- `nonProxyHosts` si un besoin réel apparaît.
- Rotation sans coupure : double proxy + bascule atomique de `proxy_name`.
