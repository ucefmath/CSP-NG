package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.*;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.List;

/**
 * REST endpoints pour la gestion de la configuration (admin uniquement).
 * DTOs explicites avec validation Bean Validation; mapping vers domain records.
 */
@Path("/api/admin/config")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminConfigResource {

    @Inject
    ConfigRepository configRepository;

    @Inject
    AdminBulkResource bulkResource;

    @Inject
    ConfigHolder configHolder;

    @Context
    SecurityContext securityContext;

    @GET
    public Uni<Response> snapshot() {
        if (!configHolder.isInitialized()) {
            return Uni.createFrom().item(
                Response.status(503).entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
        }
        ConfigSnapshot snap = configHolder.get();
        return Uni.createFrom().item(Response.ok(snap).build());
    }

    @PUT
    @Path("/root")
    public Uni<Response> updateRoot(@Valid RootConfigUpdate req) {
        if (req.proxyName() == null || req.proxyName().isBlank()) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "proxyName is required")).build());
        }
        if (req.version() < 1) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1")).build());
        }
        return Uni.createFrom().completionStage(
            configRepository.updateRoot(req.proxyName(), req.version(), actor()).toCompletionStage()
        ).map(newVersion -> Response.ok(new VersionResponse(newVersion)).build())
         .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
             Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    @PUT
    @Path("/cache")
    public Uni<Response> updateCache(@Valid CacheConfigUpdate req) {
        if (req.version() < 1) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1")).build());
        }
        return Uni.createFrom().completionStage(
            configRepository.updateCache(req.cwMaxAgeMs(), req.maximumSize(), req.maxCacheWaitMs(),
                req.maxCacheWaitPercent(), req.version(), actor()).toCompletionStage()
        ).map(newVersion -> Response.ok(new VersionResponse(newVersion)).build())
         .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
             Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    @PUT
    @Path("/cluster-udp")
    public Uni<Response> updateClusterUdp(@Valid ClusterUdpDto req) {
        if (req.version() < 1) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1")).build());
        }
        ClusterUdpConfig cfg = new ClusterUdpConfig(
            req.enabled(), req.bindPort(), req.bindAddress(), req.remoteHosts(), req.multicastGroup(), req.multicastPort(),
            req.multicastTtl(), req.autoAddPeers(), req.trackerUrlRef(), req.pingIntervalMs(), req.resendThrottleMs(),
            req.maxPacketsPerSec(), req.blacklist(), req.syncPeriodMs(), req.version());
        return Uni.createFrom().completionStage(
            configRepository.updateClusterUdp(cfg, actor()).toCompletionStage()
        ).map(newVersion -> Response.ok(new VersionResponse(newVersion)).build())
         .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
             Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    // -- CW cycle (V15) --

    @GET
    @Path("/cw-cycle")
    public Uni<Response> getCwCycle() {
        if (!configHolder.isInitialized()) {
            return Uni.createFrom().item(
                Response.status(503).entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
        }
        return Uni.createFrom().item(Response.ok(configHolder.get().cwCycle()).build());
    }

    @PUT
    @Path("/cw-cycle")
    public Uni<Response> updateCwCycle(@Valid CwCycleDto dto) {
        if (dto.version() < 1) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1")).build());
        }
        if (dto.caids() != null && dto.caids().size() > CwCycleConfig.MAX_CAIDS) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT", "caids limit " + CwCycleConfig.MAX_CAIDS)).build());
        }
        CwCycleConfig cfg;
        try {
            cfg = new CwCycleConfig(
                dto.enabled(),
                dto.caids() != null ? dto.caids() : List.of(),
                dto.maxList() != null ? dto.maxList() : CwCycleConfig.DEFAULT_LIST,
                dto.keepTimeSec() != null ? dto.keepTimeSec() : CwCycleConfig.DEFAULT_KEEP_SEC,
                dto.onBad() != null ? dto.onBad() : "drop",
                dto.dropOld() != null ? dto.dropOld() : true,
                dto.usePeerCycleMetadata() != null ? dto.usePeerCycleMetadata() : false,
                dto.version());
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
        }
        return Uni.createFrom().completionStage(configRepository.updateCwCycle(cfg, actor()).toCompletionStage())
            .map(nv -> Response.ok(new VersionResponse(nv)).build())
            .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
                Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    // -- Bulk (chemins littéraux : voir AdminBulkResource — un template
    // /{resource}/bulk serait éclipsé par /connectors/{name} etc. en JAX-RS) --

    @PUT
    @Path("/connectors/bulk")
    public Uni<Response> bulkConnectors(@Valid AdminBulkResource.BulkRequestDto req) {
        return bulkResource.bulkConnectors(req);
    }

    @PUT
    @Path("/profiles/bulk")
    public Uni<Response> bulkProfiles(@Valid AdminBulkResource.BulkRequestDto req) {
        return bulkResource.bulkProfiles(req);
    }

    @PUT
    @Path("/listeners/bulk")
    public Uni<Response> bulkListeners(@Valid AdminBulkResource.BulkRequestDto req) {
        return bulkResource.bulkListeners(req);
    }

    // -- Extended newcamd --

    @GET
    @Path("/extended-newcamd")
    public Uni<Response> getExtended() {
        if (!configHolder.isInitialized()) {
            return Uni.createFrom().item(Response.status(503).entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
        }
        ConfigSnapshot snap = configHolder.get();
        ExtendedNewcamdConfig ext = snap.extendedNewcamd();
        return Uni.createFrom().item(Response.ok(ext).build());
    }

    @PUT
    @Path("/extended-newcamd")
    public Uni<Response> updateExtended(@Valid ExtendedNewcamdDto dto) {
        if (dto.version() < 1) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1")).build());
        }
        if (dto.listenPort() < 1 || dto.listenPort() > 65535) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "listenPort must be 1-65535")).build());
        }
        if (dto.maxMsgSize() < 128 || dto.maxMsgSize() > 65536) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "maxMsgSize must be 128-65536")).build());
        }
        if (dto.bindAddress() == null || dto.bindAddress().isBlank()) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "bindAddress required")).build());
        }
        if (!ConfigValidator.isValidInet(dto.bindAddress())) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "bindAddress must be IP literal")).build());
        }
        if (dto.enabled() && (dto.desKeyRef() == null || dto.desKeyRef().isBlank())) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "desKeyRef required when enabled")).build());
        }
        if (dto.desKeyRef() != null && !dto.desKeyRef().isBlank() && !ConfigValidator.isValidSecretRef(dto.desKeyRef())) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "desKeyRef invalid secret ref")).build());
        }
        ExtendedNewcamdConfig cfg;
        try {
            cfg = new ExtendedNewcamdConfig(dto.enabled(), dto.listenPort(), dto.bindAddress(), dto.catchAll(), dto.debug(), dto.desKeyRef(), dto.allowList(), dto.denyList(), dto.maxMsgSize(), dto.version());
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
        }
        return Uni.createFrom().completionStage(configRepository.updateExtendedNewcamd(cfg, actor()).toCompletionStage())
            .map(nv -> Response.ok(new VersionResponse(nv)).build())
            .onFailure(IllegalArgumentException.class).recoverWithItem(e -> Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    // -- Profiles --

    @PUT
    @Path("/profiles/{name}")
    public Uni<Response> upsertProfile(@PathParam("name") @NotBlank String name, @Valid ProfileDto dto) {
        if (dto.version() < 1) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1")).build());
        }
        ProfileConfig toSave;
        try {
            toSave = new ProfileConfig(
                name, dto.enabled(), dto.debug(), dto.cacheOnly(),
                dto.caId(), dto.networkId(),
                dto.requireProviderMatch(), dto.checkEcmLength(),
                dto.maxCwWaitMs(), dto.congestionLimitMs(),
                dto.mismatchedCards(), dto.serviceConflicts(),
                dto.servicesFile(), dto.providerFilter(), dto.servicesFileFormat(),
                dto.blockCaidMismatch(),
                dto.version()
            );
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
        }
        return Uni.createFrom().completionStage(
            configRepository.upsertProfile(toSave, actor()).toCompletionStage()
        ).map(newVersion -> Response.ok(new VersionResponse(newVersion)).build())
         .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
             Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    @DELETE
    @Path("/profiles/{name}")
    public Uni<Response> deleteProfile(@PathParam("name") String name, @QueryParam("version") Long version) {
        if (version == null || version < 1) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version query parameter must be >=1")).build());
        }
        return Uni.createFrom().completionStage(
            configRepository.deleteProfile(name, version, actor()).toCompletionStage()
        ).map(v -> Response.noContent().build());
    }

    // -- Provider idents --

    @GET
    @Path("/profiles/{name}/provider-idents")
    public Uni<Response> getProviderIdents(@PathParam("name") String name) {
        if (!configHolder.isInitialized()) return Uni.createFrom().item(Response.status(503).entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
        ConfigSnapshot snap = configHolder.get();
        if (snap.profileByName().get(name) == null) {
            return Uni.createFrom().item(Response.status(404).entity(ErrorResponse.of("NOT_FOUND", "Profile not found")).build());
        }
        List<ProfileProviderIdentConfig> list = snap.providerIdentsFor(name);
        long ver = snap.profileByName().get(name).version();
        return Uni.createFrom().item(Response.ok(new ProviderIdentMapResponse(name, ver, list)).build());
    }

    @PUT
    @Path("/profiles/{name}/provider-idents")
    public Uni<Response> replaceProviderIdents(@PathParam("name") @NotBlank String name, @Valid ProviderIdentMapDto dto) {
        if (dto.version() < 1) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1")).build());
        if (dto.idents() != null && dto.idents().size() > 256) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "provider idents limit 256")).build());
        List<ProfileProviderIdentConfig> entries = List.of();
        if (dto.idents() != null) {
            // validate ranges and profile existence
            for (ProviderIdentEntryDto e : dto.idents()) {
                if (e.providerIdent() < 0 || e.providerIdent() > 0xFFFFFF) {
                    return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "providerIdent out of range")).build());
                }
            }
            // duplicate check
            long distinct = dto.idents().stream().map(ProviderIdentEntryDto::providerIdent).distinct().count();
            if (distinct != dto.idents().size()) {
                return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "duplicate providerIdent")).build());
            }
            entries = dto.idents().stream().map(e -> new ProfileProviderIdentConfig(0L, name, e.providerIdent(), e.label(), 1L)).toList();
        }
        List<ProfileProviderIdentConfig> finalEntries = entries;
        return Uni.createFrom().completionStage(configRepository.replaceProviderIdents(name, finalEntries, dto.version(), actor()).toCompletionStage())
            .map(nv -> Response.ok(new VersionResponse(nv)).build())
            .onFailure(IllegalArgumentException.class).recoverWithItem(e -> Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    // -- Connectors --

    @PUT
    @Path("/connectors/{name}")
    public Uni<Response> upsertConnector(@PathParam("name") @NotBlank String name, @Valid ConnectorDto dto) {
        if (dto.version() < 1) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1")).build());
        }
        if (dto.caidProfileMap() != null && dto.caidProfileMap().size() > 256) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "caidProfileMap limit 256")).build());
        }
        boolean isDynamic = dto.profileName() == null || dto.profileName().isBlank();
        List<CaidProfileEntryDto> mapDtos = dto.caidProfileMap();
        List<ConnectorCaidProfileConfig> mapEntries = null;
        if (mapDtos != null) {
            mapEntries = mapDtos.stream().map(e -> new ConnectorCaidProfileConfig(0L, name, e.ordinal(), e.caId(), e.profileName(), 1L)).toList();
        }
        // validation
        if (isDynamic) {
            if (mapEntries == null || mapEntries.isEmpty()) {
                return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "Dynamic connector requires non-empty caidProfileMap")).build());
            }
            // validate entries ranges
            for (ConnectorCaidProfileConfig e : mapEntries) {
                if (e.caId() < 0 || e.caId() > 65535) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "caId out of range")).build());
                if (e.ordinal() < 0) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "ordinal must be >=0")).build());
                if (e.profileName() == null || e.profileName().isBlank()) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "profileName required in map")).build());
            }
            // check duplicate caId/ordinal
            long distinctCa = mapEntries.stream().map(ConnectorCaidProfileConfig::caId).distinct().count();
            if (distinctCa != mapEntries.size()) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "duplicate caId in map")).build());
            long distinctOrd = mapEntries.stream().map(ConnectorCaidProfileConfig::ordinal).distinct().count();
            if (distinctOrd != mapEntries.size()) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "duplicate ordinal in map")).build());
            // ordinal contiguous check
            int maxOrd = mapEntries.stream().mapToInt(ConnectorCaidProfileConfig::ordinal).max().orElse(-1);
            if (mapEntries.size() != maxOrd + 1) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "ordinal must be contiguous 0..n-1")).build());
        } else {
            if (mapEntries != null && !mapEntries.isEmpty()) {
                return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "Fixed connector must not have caidProfileMap")).build());
            }
            if (dto.profileName() != null && dto.profileName().length() > 64) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "profileName too long")).build());
            if (dto.profileName() != null && !dto.profileName().matches("^[A-Za-z0-9_\\-.:]+(?: [A-Za-z0-9_\\-.:]+)*$")) {
                // allow simple pattern as fallback
                if (!dto.profileName().matches("^[A-Za-z0-9_.-]+$")) {
                    return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "profileName invalid")).build());
                }
            }
        }
        ConnectorConfig toSave;
        try {
            String effectiveProfile = isDynamic ? null : dto.profileName();
            // proxyName nullable : null/blank = connexion directe. Référence inconnue
            // rejetée par ConfigValidator au reload ; proxy disabled référencé = ACCEPTÉ (kill switch runtime).
            String proxyName = (dto.proxyName() != null && !dto.proxyName().isBlank()) ? dto.proxyName() : null;
            toSave = new ConnectorConfig(
                name, effectiveProfile, dto.protocol(), dto.enabled(), dto.debug(),
                dto.host(), dto.port(), dto.username(), dto.passwordRef(), dto.desKeyRef(),
                dto.connectTimeoutMs(), dto.reconnectDelayMs(), dto.keepAliveSec(), dto.minDelayMs(),
                dto.maxQueue(), dto.newcamdMaxMsgSize(), dto.newcamdClientIdRef(),
                dto.radegastResponseTimeoutMs(), dto.cccamEnabled(), dto.cccamNodeId(),
                dto.cccamVersion(), dto.cacheexMode(), dto.cacheexFilter(), dto.cacheexMaxSend(),
                dto.cacheexMaxHop(),
                dto.chameleonProfiles(), dto.conaxReaderIndex(), dto.priority(), dto.maxCapacity(),
                dto.version(), proxyName,
                dto.cardDiscovery() != null ? dto.cardDiscovery() : false,
                dto.reshareGrant(),
                dto.newcamdDialect() != null ? dto.newcamdDialect() : "legacy",
                dto.reshareCap(),
                dto.newcamdPasswordEncoding() != null ? dto.newcamdPasswordEncoding() : "plaintext"
            );
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
        }
        Uni<Response> result;
        if (isDynamic) {
            List<ConnectorCaidProfileConfig> finalMap = mapEntries;
            result = Uni.createFrom().completionStage(configRepository.upsertConnectorWithCaidMap(toSave, finalMap, actor()).toCompletionStage())
                .map(nv -> Response.ok(new VersionResponse(nv)).build());
        } else {
            result = Uni.createFrom().completionStage(configRepository.upsertConnector(toSave, actor()).toCompletionStage())
                .map(nv -> Response.ok(new VersionResponse(nv)).build());
        }
        return result.onFailure(IllegalArgumentException.class).recoverWithItem(e -> Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    @GET
    @Path("/connectors/{name}/caid-profile-map")
    public Uni<Response> getCaidMap(@PathParam("name") String name) {
        if (!configHolder.isInitialized()) return Uni.createFrom().item(Response.status(503).entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
        ConfigSnapshot snap = configHolder.get();
        ConnectorConfig cc = snap.connectorByName().get(name);
        if (cc == null) return Uni.createFrom().item(Response.status(404).entity(ErrorResponse.of("NOT_FOUND", "Connector not found")).build());
        List<ConnectorCaidProfileConfig> entries = snap.caidProfilesForConnector(name);
        return Uni.createFrom().item(Response.ok(new CaidProfileMapResponse(name, cc.version(), entries)).build());
    }

    @PUT
    @Path("/connectors/{name}/caid-profile-map")
    public Uni<Response> replaceCaidMap(@PathParam("name") @NotBlank String name, @Valid CaidProfileMapDto dto) {
        if (dto.version() < 1) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1")).build());
        if (dto.entries() != null && dto.entries().size() > 256) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "caidProfileMap limit 256")).build());
        List<ConnectorCaidProfileConfig> entries = List.of();
        if (dto.entries() != null) {
            for (CaidProfileEntryDto e : dto.entries()) {
                if (e.caId() < 0 || e.caId() > 65535) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "caId out of range")).build());
                if (e.ordinal() < 0) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "ordinal must be >=0")).build());
            }
            long distinctCa = dto.entries().stream().map(CaidProfileEntryDto::caId).distinct().count();
            if (distinctCa != dto.entries().size()) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "duplicate caId")).build());
            long distinctOrd = dto.entries().stream().map(CaidProfileEntryDto::ordinal).distinct().count();
            if (distinctOrd != dto.entries().size()) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "duplicate ordinal")).build());
            int maxOrd = dto.entries().stream().mapToInt(CaidProfileEntryDto::ordinal).max().orElse(-1);
            if (dto.entries().size() != maxOrd + 1) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "ordinal must be contiguous 0..n-1")).build());
            entries = dto.entries().stream().map(e -> new ConnectorCaidProfileConfig(0L, name, e.ordinal(), e.caId(), e.profileName(), 1L)).toList();
        }
        if (entries.isEmpty()) return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "CAID map must be non-empty for dynamic connector")).build());
        List<ConnectorCaidProfileConfig> finalEntries = entries;
        return Uni.createFrom().completionStage(configRepository.replaceCaidProfileMap(name, finalEntries, dto.version(), actor()).toCompletionStage())
            .map(nv -> Response.ok(new VersionResponse(nv)).build())
            .onFailure(IllegalArgumentException.class).recoverWithItem(e -> Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    @DELETE
    @Path("/connectors/{name}")
    public Uni<Response> deleteConnector(@PathParam("name") String name, @QueryParam("version") Long version) {
        if (version == null || version < 1) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version query parameter must be >=1")).build());
        }
        return Uni.createFrom().completionStage(
            configRepository.deleteConnector(name, version, actor()).toCompletionStage()
        ).map(v -> Response.noContent().build());
    }

    // =========================================================================
    // PROFILE LISTENERS CRUD
    // =========================================================================

    @GET
    @Path("/listeners")
    public Uni<Response> listListeners() {
        if (!configHolder.isInitialized()) return Uni.createFrom().item(Response.status(503).entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
        List<ProfileListenerConfig> list = configHolder.get().profileListeners();
        return Uni.createFrom().item(Response.ok(list).build());
    }

    @PUT
    @Path("/listeners/{id}")
    public Uni<Response> upsertListener(@PathParam("id") long id, @Valid ListenerDto dto) {
        if (dto.version() < 1) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version must be >= 1")).build());
        }
        ProfileListenerConfig cfg;
        try {
            cfg = new ProfileListenerConfig(
                id, dto.profileName(), dto.protocol(), dto.port(), dto.bindAddress(),
                dto.enabled(), dto.desKeyRef(), dto.allowList(), dto.denyList(), dto.version(),
                dto.cardMode() != null ? dto.cardMode() : "manual",
                dto.strictPolicy() != null ? dto.strictPolicy() : true,
                dto.reshareCap() != null ? dto.reshareCap() : 0,
                dto.maxCards() != null && dto.maxCards() > 0 ? dto.maxCards() : 512,
                dto.maxInflight() != null && dto.maxInflight() > 0 ? dto.maxInflight() : 50,
                dto.maxPerIp() != null && dto.maxPerIp() > 0 ? dto.maxPerIp() : 5,
                dto.nodeId(),
                dto.idleTimeoutSec() != null && dto.idleTimeoutSec() > 0 ? dto.idleTimeoutSec() : 60
            );
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
        }

        return Uni.createFrom().completionStage(
            configRepository.upsertListener(cfg, actor()).toCompletionStage()
        ).map(newVersion -> Response.ok(new VersionResponse(newVersion)).build())
         .onFailure(java.util.NoSuchElementException.class).recoverWithItem(e ->
             Response.status(404).entity(ErrorResponse.of("NOT_FOUND", e.getMessage())).build())
         .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
             Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    @DELETE
    @Path("/listeners/{id}")
    public Uni<Response> deleteListener(@PathParam("id") long id, @QueryParam("version") Long version) {
        if (version == null || version < 1) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version query parameter must be >= 1")).build());
        }
        return Uni.createFrom().completionStage(
            configRepository.deleteListener(id, version, actor()).toCompletionStage()
        ).map(v -> Response.noContent().build())
         .onFailure(java.util.NoSuchElementException.class).recoverWithItem(e ->
             Response.status(404).entity(ErrorResponse.of("NOT_FOUND", e.getMessage())).build());
    }

    private String actor() {
        Principal p = securityContext.getUserPrincipal();
        return p != null ? p.getName() : "unknown";
    }

    // -- DTOs for updates --

    public record RootConfigUpdate(@NotBlank String proxyName, long version) {}
    public record CacheConfigUpdate(long cwMaxAgeMs, long maximumSize, long maxCacheWaitMs,
                                     int maxCacheWaitPercent, long version) {}
    public record ClusterUdpDto(boolean enabled, int bindPort, String bindAddress, String remoteHosts,
                                 String multicastGroup, Integer multicastPort, Integer multicastTtl,
                                 boolean autoAddPeers, String trackerUrlRef, long pingIntervalMs, long resendThrottleMs,
                                 Integer maxPacketsPerSec, String blacklist, long syncPeriodMs, long version) {}
    public record VersionResponse(long version) {}

    public record ProfileDto(boolean enabled, boolean debug, boolean cacheOnly, int caId, int networkId,
                              Boolean requireProviderMatch, Boolean checkEcmLength, long maxCwWaitMs,
                              Long congestionLimitMs, boolean mismatchedCards, int serviceConflicts,
                              String servicesFile, String providerFilter, String servicesFileFormat,
                              Boolean blockCaidMismatch, long version) {}
    public record ConnectorDto(String profileName, @NotBlank String protocol, boolean enabled, boolean debug,
                                @NotBlank String host, int port, String username, String passwordRef, String desKeyRef,
                                int connectTimeoutMs, int reconnectDelayMs, Integer keepAliveSec, Integer minDelayMs,
                                Integer maxQueue, Integer newcamdMaxMsgSize, String newcamdClientIdRef,
                                Integer radegastResponseTimeoutMs, boolean cccamEnabled, String cccamNodeId,
                                String cccamVersion, Integer cacheexMode, String cacheexFilter, Integer cacheexMaxSend,
                                Integer cacheexMaxHop,
                                String chameleonProfiles, Integer conaxReaderIndex, int priority, int maxCapacity,
                                long version, String proxyName,
                                 @Size(max=256) List<@Valid CaidProfileEntryDto> caidProfileMap,
                                 Boolean cardDiscovery, @Min(0) @Max(10) Integer reshareGrant, String newcamdDialect, @Min(0) @Max(10) Integer reshareCap,
                                 @Pattern(regexp = "(?i)^(plaintext|md5crypt)$", message = "newcamdPasswordEncoding must be plaintext or md5crypt") String newcamdPasswordEncoding) {
        /** Compat sans cacheexMaxHop (pre-V15) — défaut runtime. */
        public ConnectorDto(String profileName, String protocol, boolean enabled, boolean debug,
                            String host, int port, String username, String passwordRef, String desKeyRef,
                            int connectTimeoutMs, int reconnectDelayMs, Integer keepAliveSec, Integer minDelayMs,
                            Integer maxQueue, Integer newcamdMaxMsgSize, String newcamdClientIdRef,
                            Integer radegastResponseTimeoutMs, boolean cccamEnabled, String cccamNodeId,
                            String cccamVersion, Integer cacheexMode, String cacheexFilter, Integer cacheexMaxSend,
                            String chameleonProfiles, Integer conaxReaderIndex, int priority, int maxCapacity,
                            long version, String proxyName, List<CaidProfileEntryDto> caidProfileMap,
                            Boolean cardDiscovery, Integer reshareGrant, String newcamdDialect, Integer reshareCap) {
            this(profileName, protocol, enabled, debug, host, port, username, passwordRef, desKeyRef,
                 connectTimeoutMs, reconnectDelayMs, keepAliveSec, minDelayMs, maxQueue, newcamdMaxMsgSize,
                 newcamdClientIdRef, radegastResponseTimeoutMs, cccamEnabled, cccamNodeId, cccamVersion,
                 cacheexMode, cacheexFilter, cacheexMaxSend, null,
                 chameleonProfiles, conaxReaderIndex, priority, maxCapacity, version, proxyName, caidProfileMap,
                 cardDiscovery, reshareGrant, newcamdDialect, reshareCap, "plaintext");
        }
        public ConnectorDto(String profileName, String protocol, boolean enabled, boolean debug,
                            String host, int port, String username, String passwordRef, String desKeyRef,
                            int connectTimeoutMs, int reconnectDelayMs, Integer keepAliveSec, Integer minDelayMs,
                            Integer maxQueue, Integer newcamdMaxMsgSize, String newcamdClientIdRef,
                            Integer radegastResponseTimeoutMs, boolean cccamEnabled, String cccamNodeId,
                            String cccamVersion, Integer cacheexMode, String cacheexFilter, Integer cacheexMaxSend,
                            String chameleonProfiles, Integer conaxReaderIndex, int priority, int maxCapacity,
                            long version, String proxyName, List<CaidProfileEntryDto> caidProfileMap) {
            this(profileName, protocol, enabled, debug, host, port, username, passwordRef, desKeyRef,
                 connectTimeoutMs, reconnectDelayMs, keepAliveSec, minDelayMs, maxQueue, newcamdMaxMsgSize,
                 newcamdClientIdRef, radegastResponseTimeoutMs, cccamEnabled, cccamNodeId, cccamVersion,
                 cacheexMode, cacheexFilter, cacheexMaxSend, null,
                 chameleonProfiles, conaxReaderIndex, priority, maxCapacity, version, proxyName, caidProfileMap,
                 false, null, "legacy", null, "plaintext");
        }
    }
    public record ListenerDto(@NotBlank String profileName, @NotBlank String protocol, @Min(1) @Max(65535) int port,
                              @NotBlank String bindAddress, boolean enabled, String desKeyRef, String allowList,
                              String denyList, String cardMode, Boolean strictPolicy, @Min(0) @Max(10) Integer reshareCap,
                              @Min(1) @Max(4096) Integer maxCards, @Min(1) @Max(64) Integer maxInflight,
                              @Min(1) @Max(100) Integer maxPerIp, String nodeId,
                              @Min(5) @Max(3600) Integer idleTimeoutSec, long version) {}
    public record ExtendedNewcamdDto(boolean enabled, int listenPort, @NotBlank String bindAddress, boolean catchAll, boolean debug, String desKeyRef, String allowList, String denyList, int maxMsgSize, @Min(1) long version) {}
    public record CwCycleDto(boolean enabled, @Size(max=32) List<@Min(1) @Max(65535) Integer> caids,
                              @Min(16) @Max(4000) Integer maxList,
                              @Min(5) @Max(240) Integer keepTimeSec,
                              @Pattern(regexp = "(?i)^(log|drop)$", message = "onBad must be log or drop") String onBad,
                              Boolean dropOld, Boolean usePeerCycleMetadata, @Min(1) long version) {}
    public record CaidProfileEntryDto(@Min(0) int ordinal, @Min(0) @Max(65535) int caId, @NotBlank String profileName) {}
    public record CaidProfileMapDto(@Size(max=256) List<@Valid CaidProfileEntryDto> entries, @Min(1) long version) {}
    public record CaidProfileMapResponse(String connector, long version, List<ConnectorCaidProfileConfig> entries) {}
    public record ProviderIdentEntryDto(@Min(0) @Max(16777215) int providerIdent, String label) {}
    public record ProviderIdentMapDto(@Size(max=256) List<@Valid ProviderIdentEntryDto> idents, @Min(1) long version) {}
    public record ProviderIdentMapResponse(String profile, long version, List<ProfileProviderIdentConfig> idents) {}
}
