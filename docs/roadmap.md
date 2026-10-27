# Feuille de route d'implémentation (Roadmap)

> **Information :** L'ensemble des fonctionnalités décrites dans cette feuille de route (**DcwFilterPlugin**, **REST/SSE**, **CSPv2**) est désormais intégralement **IMPLÉMENTÉ** et fusionné dans `main`.

## Fonctionnalités réalisées

1. **[Filtre avancé DCW (DcwFilterPlugin)](implementation-dcw-filter-plugin.md)** (Implémenté)
   Remplacement de l'ancien système de détection de liens, de forçage de continuité et de filtrage dynamique avec persistance JSON atomique.

2. **[Interface Web : Télémétrie temps réel (REST/SSE)](implementation-rest-sse.md)** (Implémenté)
   Ajout de Server-Sent Events (SSE) et d'endpoints d'administration statiques REST paginés pour diffuser les événements et états système en temps réel.

3. **[Nouveau Protocole Inter-Proxy (CSPv2)](implementation-cspv2.md)** (Implémenté)
   Un protocole TCP modernisé (framing borné, mTLS obligatoire, multiplexé) exclusif aux communications entre nœuds `csp-ng`, en complément du protocole UDP de compatibilité.

## Ordre de réalisation

L'implémentation de ces fonctionnalités a suivi cet ordre logique :
1. DcwFilterPlugin (parité fonctionnelle directe - Implémenté).
2. REST/SSE (observabilité - Implémenté).
3. CSPv2 (évolution réseau majeure - Implémenté).

## Dépendances et Statut

| Fonctionnalité | Statut | Dépendances (Gates) |
|---|---|---|
| DcwFilterPlugin | **IMPLÉMENTÉ** | Fan-out multi-connecteurs + annuaire services + contrat filtre réponse |
| REST/SSE | **IMPLÉMENTÉ** | Snapshots runtime sessions/connecteurs/cache + auth navigateur cookie (si WebUI) |
| CSPv2 | **IMPLÉMENTÉ** | ADR wire + PKI/secret refs |
