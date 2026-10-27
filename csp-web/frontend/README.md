# CSP-NG — Console d'administration (frontend)

SPA React 19 + React Router 7 (data mode `createBrowserRouter`, `basename: "/admin"`),
TypeScript strict, Vite 7, CSS statique sobre (`src/app.css`). Aucune dépendance
serveur de rendu : le build produit un bundle statique servi par Quarkus sous `/admin/`.

## Prérequis

- Node 24 sur le `PATH` (build Maven via `exec-maven-plugin`, skippable).
- Aucun secret réel : l'UI ne lit/transmet jamais de `.env`; seules des références
  `env:`/`file:` sont persistées, ou des valeurs matérialisées serveur.

## Développement

```bash
cd csp-web/frontend
npm ci
npm run dev        # Vite dev (proxy /api vers le backend : voir vite.config.ts)
npm run typecheck  # tsc --noEmit
npm run test       # vitest run (29 tests : helpers, composants, parcours routes)
npm run build      # bundle déterministe -> ../target/frontend-dist/
npm run verify     # typecheck + tests + build (utilisé par Maven)
npm run e2e        # Playwright 41 tests sur le bundle packagé (build requis avant)
```

## Build Maven (intégration)

`csp-web/pom.xml`, phase `generate-resources` (sauf `-Dskip.frontend=true`) :

1. `npm ci --no-audit --no-fund`
2. `npm run verify` (échoue le build si typecheck/tests/build échouent)
3. `npm run e2e` — 41 tests Playwright Chromium sur le bundle packagé
   (`target/frontend-dist` servi par `e2e/server.mjs` avec fallback SPA limité +
   CSP stricte ; API mockée alignée DTO, corps de requêtes assertés)
4. `maven-resources-plugin` recopie `target/frontend-dist/` vers
   `target/classes/META-INF/resources/admin/` (jamais commité : `target/`
   gitignoré, tout comme `frontend/node_modules/` et `frontend/dist/`).

CI : installer Node 24 sur le `PATH` et un Chromium système
(`CSP_CHROMIUM_BIN`, défaut `/usr/bin/chromium` — aucun téléchargement
de navigateur Playwright requis).

Fichiers produits (noms déterministes, reproductibles) :

- `index.html` — shell `<div id="root">`, aucun script/style inline
- `assets/app.js`, `assets/app.css`

## Routage et fallback

- 15 routes : `overview general cache cache-etat cacheex cluster profiles listeners
  catalog connectors proxys relais dcw cw-cycle users` (+ `login`, miroir
  `ADMIN_ROUTES` dans `src/router.tsx`).
- Deep links : `AdminSpaFallbackResource` (JAX-RS, ajout seul — aucune API
  existante modifiée) renvoie `index.html` pour les routes HTML connues,
  `Cache-Control: no-store` + CSP `self` sans `unsafe-inline`. Assets absents,
  API et routes inconnues restent en 404.

## Conventions UI (français, accessible)

- Auth/session : cookie même origine (`credentials: "same-origin"`), restauration
  au rechargement via `GET /api/admin/me` (deep links), CSRF
  `X-CSP-Requested-With: CSP-WebUI` sur mutations, mapping 409/426/403 explicite,
  version optimiste par ligne (`version`).
- Secrets : `SecretField` (valeur directe matérialisée via
  `POST /api/admin/secrets/materialize` → `file:`, ou référence `env:`/`file:`
  conservée) ; révélation volontaire ; rien en `localStorage`/`sessionStorage` ;
  aucune valeur retournée par GET.
- Bulk : `BulkBar` (connectors/profiles/listeners/users) — 3 modes explicites :
  « Inchangé » (absent du patch), « Définir une valeur », « Réinitialiser »
  (null explicite, **uniquement sur colonnes NULLABLE** backend sinon 400 ;
  libellé du défaut : NULL / legacy / 0 / manual / direct). Ensembles users
  (`profiles`, `allowedConnectors`) : case « Remplacer l'ensemble » + chips
  (vide = vide l'ensemble). Résumé (items `{id, version}` + patch JSON, légende
  valeur/null/tableau), `PUT …/bulk` atomique 1..100, jamais
  secrets/admin/nodeId/identité.
- États cache : `cache-etat` affiche la fraîcheur (`statsAgeMs`/`statsFresh`) ;
  absent/périmé = « inconnu », jamais de zéros fabriqués. Idem CacheEx
  (`cacheex`) : paires sans trafic absentes ; modes asymétriques
  (sortant 2 = réception / 3 = émission ; entrant 2 = nous émettons / 3 = nous
  recevons).
- Formulaires en sections Essentiel/Avancé, tables filtrables, sélection
  labellisée, erreurs inline + toasts `aria-live`, skip link, focus clavier et
  dialogs `<dialog>`, états loading/empty/error, mobile (tiroir), contraste AA.

## Tests

| Niveau | Commande | Couverture |
|---|---|---|
| Unitaires/composants | `npm run test` (vitest, 31) | helpers API, BulkBar (3 modes, reset nullable→null, multiselect replace), SecretField (matérialisation, DES), 15 routes (titres h1), shell/nav |
| E2E Chromium | `npm run e2e` (Playwright, 44) | voir ci-dessous |
| Contrat Java | `AdminUi*Test` (csp-web) | sources, bundle, fallback `/admin/*`, contrat E2E |

E2E (`e2e/`, mocks `fixtures.ts` alignés sur les vrais DTO backend) :

- `auth` : login OK/logout, 401 invalide, expiration 401 → login, garde sans session
- `navigation` : 15 liens (URL + titre), deep link + reload (restauration session
  via `GET /me`), 404 serveur non masqué
- `crud` : corps PUT/POST exacts profils/listeners/users/général/cache/cw-cycle
  (caids triés), suppression `?version`, catalogue `{state,version}` + sources
- `connectors` : édition préservant tous les champs + CSRF, création cs378x,
  import C-line (materialize puis ref, valeur jamais réutilisée), bulk
  (résumé versions+patch, reset nullable→null, non-nullable sans reset,
  inchangé sans envoi, 409 rollback dialogue conservé)
- `secrets` : inline write-only, mode référence sans matérialisation, DES
  invalide sans réseau, secret user `?version` + validUntil/enabled, protocoles
  supportés cccam/cs378x uniquement
- `users` (bulk ensembles) : remplacement `profiles` (chips, résumé tableau,
  PUT items+patch), reset scalaire nullable (`displayName: null`)
- `relay` : tables + TTL, création lien (selects labellisés, PUT `can*`),
  validation sens, `AssignmentDto{enabled,version}`, pairs UDP
- `dcw` : onglets clavier (`aria-selected`), politique 8 clés + alerte modes
  dangereux (enforce/reject_valid), règles (validation digest, POST exact),
  mappings (source≠cible, `expiresAtMs`), XSS (payload rendu en texte, aucun
  dialogue JS ; aucun `dangerouslySetInnerHTML` dans les sources), observabilité
- `a11y` : axe-core wcag2a/wcag2aa (0 critique), `lang="fr"`, skip link, focus
  dialogue + Escape, labels/noms sur tous les champs/boutons, CSP stricte
  fonctionnelle, mobile 375 px (tiroir)

## Contrats backend

Voir `docs/api-contracts-ui-refonte.md` : bulk, materialize,
`GET /api/admin/cache/status`, `GET/POST /api/admin/cacheex/stats[/reset]`,
`GET/PUT /api/admin/config/cw-cycle`, `cacheexMaxHop`, `cacheexMaxSend` (file),
politique cacheex entrante user, connecteur sortant `cs378x`. L'UI dégrade
gracieusement en 404 tant qu'un endpoint n'est pas livré (message explicite,
pas de données inventées).
