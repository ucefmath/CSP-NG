# Test d'interopérabilité Docker 3 nœuds (lot L3b) — plan §5.3 ⭐

Jalon **go/no-go** du programme : validation de la compatibilité bit-à-bit du protocole UDP
`ClusteredCache` réimplémenté dans `csp-cluster-udp` (lots L0–L3a) avec le parc
existant — CSP legacy Java et OSCam (`module-csp` / `CS_CACHEEX`).

## Lancement

```bash
./run-interop-test.sh          # build + up + harness + logs + teardown
./run-interop-test.sh --no-build   # si les images sont déjà construites
./run-interop-test.sh --keep-up    # laisse les conteneurs en place (debug)
```

Code de sortie : `0` = toutes les assertions passent, `1` = échec(s).
Résultats : `results/harness.out` (détail de chaque assertion), `results/*.log`.

## Architecture

| Service | Rôle | Implémentation | UDP |
|---|---|---|---|
| `csp-legacy` | CSP Java 17 actuel (référence) | `build/classes` pré-compilés + `proxy.xml` minimal | 9009 |
| `csp-new` | Réécriture Quarkus/Vert.x (lots L0–L3a) | jar runner `csp-app` + bootstrap `InteropClusterBootstrap` | 9009 |
| `oscam` | OSCam compilé avec `CS_CACHEEX` + `module-csp` | `./config.sh --enable CS_CACHEEX ... && make` (dans le conteneur) | 12346 |
| `harness` | Injecteur + assertions (Python, UDP brut) | `harness/harness.py` | 19000 |

Réseau bridge Docker (unicast uniquement) ; la découverte des pairs est unicast
(`remote-host`/`remote-port` pour `csp-new`, `tracker-url` local + `auto-add-peers` pour
le legacy, « clients » appris par OSCam à la réception de paquets).

## Choix documentés

1. **Le legacy utilise ses classes pré-compilées commitées** (`build/classes`) : le build
   `javac` documenté dans `AGENTS.md` échoue de zéro sur `rmi/LocalHandler.java` (imports
   `User`/`ProxyLogger`/`SessionManager` manquants dans ce snapshot source — le code legacy
   est en lecture seule, interdit de le corriger). Les classes commitées sont exactement
   la référence « parc existant » que le test doit valider (vérifié : `CardServProxy 0.9.0`
   démarre et le `ClusteredCache` écoute en UDP).
2. **OSCam est compilé réellement dans le conteneur** (jamais simulé) : `config.h` est
   régénéré via `./config.sh --enable CS_CACHEEX CS_CACHEEX_AIO MODULE_CONSTCW CW_CYCLE_CHECK`
   (configuration de build, aucune source C modifiée) puis `make`. Le répertoire source OSCam (`$OSCAM_SRC`)
   reste en lecture seule : le contexte de build est une copie de staging (`context-oscam/`).
3. **`csp-app` a été enrichi** (modification explicitement autorisée par la tâche L3b) :
   `InteropClusterBootstrap` déploie `CacheVerticle` + `UdpClusterVerticle` quand
   `CSP_UDP_CLUSTER_ENABLED=true` (jamais par défaut) et expose un endpoint d'observation
   temporaire `GET /cluster-stats` (compteurs de rejet §2.6) — cf. la javadoc de la classe.
4. **Harness en Python** (stdlib, UDP brut) : le plus rapide à itérer, et il encode/décode
   lui-même les trames du protocole gelé (§2) — indépendant de `csp-codec`, ce qui renforce
   la valeur du test (double implémentation croisée du format binaire).
5. **Le redémarrage de `csp-new` avant le harness** (`docker compose restart csp-new`) est
   nécessaire au scénario négatif « RESENDREQ depuis une IP non enregistrée comme pair » :
   csp-new accumule les pairs dynamiques sans expiration, un registre vierge garantit que
   l'IP du harness est inconnue au moment du test anti-amplification.

## Résultats clés (résumé — détail dans `INTEROP_RESULTS.md`)

- Matrice d'interop : les 3 nœuds servent leur CW injectée ; `csp-new` et OSCam propagent
  (broadcast/reforward) aux deux autres ; le legacy ne re-forward pas les CW reçues du
  réseau (comportement réel documenté) — la visibilité y est à la demande (RESENDREQ).
- Verrous distribués : 6/6 (le legacy comme source requiert le relais par le 3e nœud).
- Scénario négatif : 8/8 compteurs de rejet corrects, aucun crash, RTT inchangé (~0,7 ms).
- OSCam : arbitrage 20 o ignoré, réponse vide 13 o ignorée, CW servie via RESENDREQ ;
  les REQUEST(12) entrants sont ignorés par ce build d'OSCam (`E_UNHANDLED` ≥ `E_NOTFOUND`
  dans `cacheex_add_to_cache_int` — comportement de référence du parc, pas un défaut du
  nouveau nœud).
