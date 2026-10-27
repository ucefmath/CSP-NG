package com.bowman.cspng.config;

import java.util.List;

/**
 * Patches bulk (contrat v2 §2.1, sémantique C3) — options communes patchables de
 * plusieurs éléments.
 *
 * <p>Distinction ABSENT vs NULL EXPLICITE (décidée au niveau transport : l'appelant
 * transmet l'ensemble des champs présents) :
 * <ul>
 *   <li>champ ABSENT du patch ⇒ INCHANGÉ ;</li>
 *   <li>champ présent non-null ⇒ nouvelle valeur (bornes validées fail-closed) ;</li>
 *   <li>champ présent {@code null} + colonne NULLABLE ⇒ RESET (NULL, ou défaut
 *       unitaire documenté : {@code reshareCap→0}, {@code cardMode→'manual'},
 *       {@code newcamdDialect→'legacy'}, {@code newcamdPasswordEncoding→'plaintext'},
 *       {@code cardDiscovery→false}, {@code proxyName ""→NULL}) ;</li>
 *   <li>champ présent {@code null} + colonne NOT NULL ⇒ 400 explicite nommant le
 *       champ (jamais de succès silencieux sans modification) ;</li>
 *   <li>sets ({@code profiles}, {@code allowedConnectors}, services) : {@code []}
 *       vide le set (remplacement), {@code null} explicite ⇒ 400 (omettre pour
 *       inchangé).</li>
 * </ul>
 * Les champs identité, secrets, nodeId, admin et password sont structurellement
 * ABSENTS des records (impossibles à patcher en masse) — contrainte de sécurité.
 *
 * <p>Validation fail-closed identique aux PUT unitaires (bornes) à la construction.
 * Ordre d'application : croissant par id, déterministe (repository).
 */
public final class BulkPatches {

    private BulkPatches() {}

    /** Item visé : id (nom ou identifiant numérique en chaîne) + version COURANTE de la ligne. */
    public record BulkItem(String id, long version) {
        public BulkItem {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("item id required");
            if (version < 1) throw new IllegalArgumentException("item version must be >= 1");
        }
    }

    /** Résultat par item : nouvelle version de la ligne. */
    public record BulkItemResult(String id, long version) {}

    private static final List<String> CONNECTOR_FORBIDDEN = List.of(
        "name", "profileName", "protocol", "host", "port", "username", "passwordRef", "desKeyRef",
        "newcamdClientIdRef", "caidProfileMap", "cccamNodeId", "version");

    /**
     * Patch connecteur (23 champs autorisés — aucun secret, aucun nodeId, aucune identité).
     */
    public record ConnectorPatch(
        Boolean enabled, Boolean debug, Integer connectTimeoutMs, Integer reconnectDelayMs,
        Integer keepAliveSec, Integer minDelayMs, Integer maxQueue, Integer newcamdMaxMsgSize,
        Integer radegastResponseTimeoutMs, Boolean cccamEnabled, String cccamVersion,
        Integer priority, Integer maxCapacity, String proxyName, Boolean cardDiscovery,
        Integer reshareGrant, String newcamdDialect, Integer reshareCap,
        String newcamdPasswordEncoding, Integer cacheexMode, String cacheexFilter,
        Integer cacheexMaxSend, Integer cacheexMaxHop) {

        public ConnectorPatch {
            if (priority != null && (priority < 0 || priority > 100)) {
                throw new IllegalArgumentException("priority must be 0-100");
            }
            if (cacheexMode != null && cacheexMode != 0 && cacheexMode != 2 && cacheexMode != 3) {
                throw new IllegalArgumentException("cacheexMode must be 0, 2, or 3");
            }
            if (cacheexMaxSend != null && (cacheexMaxSend < 0 || cacheexMaxSend > 10000)) {
                throw new IllegalArgumentException("cacheexMaxSend must be 0..10000");
            }
            if (cacheexMaxHop != null && (cacheexMaxHop < 0 || cacheexMaxHop > 10)) {
                throw new IllegalArgumentException("cacheexMaxHop must be 0..10");
            }
            if (reshareGrant != null && (reshareGrant < 0 || reshareGrant > 10)) {
                throw new IllegalArgumentException("reshareGrant must be 0-10");
            }
            if (reshareCap != null && (reshareCap < 0 || reshareCap > 10)) {
                throw new IllegalArgumentException("reshareCap must be 0-10");
            }
            if (newcamdDialect != null && !newcamdDialect.equalsIgnoreCase("legacy")
                && !newcamdDialect.equalsIgnoreCase("oscam_ncd525")) {
                throw new IllegalArgumentException("newcamdDialect must be legacy or oscam_ncd525");
            }
            if (newcamdPasswordEncoding != null && !newcamdPasswordEncoding.equalsIgnoreCase("plaintext")
                && !newcamdPasswordEncoding.equalsIgnoreCase("md5crypt")) {
                throw new IllegalArgumentException("newcamdPasswordEncoding must be plaintext or md5crypt");
            }
        }

        public boolean isEmpty() {
            return enabled == null && debug == null && connectTimeoutMs == null && reconnectDelayMs == null
                && keepAliveSec == null && minDelayMs == null && maxQueue == null && newcamdMaxMsgSize == null
                && radegastResponseTimeoutMs == null && cccamEnabled == null && cccamVersion == null
                && priority == null && maxCapacity == null && proxyName == null && cardDiscovery == null
                && reshareGrant == null && newcamdDialect == null && reshareCap == null
                && newcamdPasswordEncoding == null && cacheexMode == null && cacheexFilter == null
                && cacheexMaxSend == null && cacheexMaxHop == null;
        }
    }

    /** Patch profil (13 champs autorisés). */
    public record ProfilePatch(
        Boolean enabled, Boolean debug, Boolean cacheOnly, Boolean requireProviderMatch,
        Boolean checkEcmLength, Long maxCwWaitMs, Long congestionLimitMs, Boolean mismatchedCards,
        Integer serviceConflicts, Boolean blockCaidMismatch, String providerFilter,
        String servicesFile, String servicesFileFormat) {

        public ProfilePatch {
            if (maxCwWaitMs != null && maxCwWaitMs < 0) {
                throw new IllegalArgumentException("maxCwWaitMs must be >= 0");
            }
            if (congestionLimitMs != null && congestionLimitMs < 0) {
                throw new IllegalArgumentException("congestionLimitMs must be >= 0");
            }
            if (serviceConflicts != null && (serviceConflicts < 0 || serviceConflicts > 100)) {
                throw new IllegalArgumentException("serviceConflicts must be 0-100");
            }
        }

        public boolean isEmpty() {
            return enabled == null && debug == null && cacheOnly == null && requireProviderMatch == null
                && checkEcmLength == null && maxCwWaitMs == null && congestionLimitMs == null
                && mismatchedCards == null && serviceConflicts == null && blockCaidMismatch == null
                && providerFilter == null && servicesFile == null && servicesFileFormat == null;
        }
    }

    /** Patch listener (10 champs autorisés — ni desKeyRef ni nodeId ni identité). */
    public record ListenerPatch(
        Boolean enabled, String cardMode, Boolean strictPolicy, Integer reshareCap,
        Integer maxCards, Integer maxInflight, Integer maxPerIp, Integer idleTimeoutSec,
        String allowList, String denyList) {

        public ListenerPatch {
            if (cardMode != null && !cardMode.equals("manual") && !cardMode.equals("wildcard")) {
                throw new IllegalArgumentException("cardMode must be manual or wildcard");
            }
            if (reshareCap != null && (reshareCap < 0 || reshareCap > 10)) {
                throw new IllegalArgumentException("reshareCap must be 0-10");
            }
            if (maxCards != null && (maxCards < 1 || maxCards > 4096)) {
                throw new IllegalArgumentException("maxCards must be 1-4096");
            }
            if (maxInflight != null && (maxInflight < 1 || maxInflight > 64)) {
                throw new IllegalArgumentException("maxInflight must be 1-64");
            }
            if (maxPerIp != null && (maxPerIp < 1 || maxPerIp > 100)) {
                throw new IllegalArgumentException("maxPerIp must be 1-100");
            }
            if (idleTimeoutSec != null && (idleTimeoutSec < 5 || idleTimeoutSec > 3600)) {
                throw new IllegalArgumentException("idleTimeoutSec must be 5-3600");
            }
        }

        public boolean isEmpty() {
            return enabled == null && cardMode == null && strictPolicy == null && reshareCap == null
                && maxCards == null && maxInflight == null && maxPerIp == null && idleTimeoutSec == null
                && allowList == null && denyList == null;
        }
    }
}
