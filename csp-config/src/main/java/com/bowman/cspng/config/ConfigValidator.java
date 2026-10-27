package com.bowman.cspng.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validateur de configuration — regles metier cote serveur.
 */
public final class ConfigValidator {

    private ConfigValidator() { /* util */ }

    public static List<String> validate(ConfigSnapshot snapshot) {
        if (snapshot == null) return List.of("snapshot is null");
        List<String> errors = new ArrayList<>();
        checkUniqueNames(snapshot, errors);
        checkProfileReferences(snapshot, errors);
        checkConnectors(snapshot, errors);
        checkListeners(snapshot, errors);
        checkSecrets(snapshot, errors);
        checkConnectorCaidMaps(snapshot, errors);
        checkProviderIdents(snapshot, errors);
        checkExtendedNewcamd(snapshot, errors);
        checkProxies(snapshot, errors);
        checkConnectorProxyRefs(snapshot, errors);
        checkRelay(snapshot, errors);
        checkDcwPolicy(snapshot, errors);
        return errors;
    }

    private static void checkUniqueNames(ConfigSnapshot s, List<String> errors) {
        Set<String> profileNames = new HashSet<>();
        for (ProfileConfig p : s.profiles()) {
            if (!profileNames.add(p.name())) {
                errors.add("Duplicate profile name: " + p.name());
            }
        }
        Set<String> connectorNames = new HashSet<>();
        for (ConnectorConfig c : s.connectors()) {
            if (!connectorNames.add(c.name())) {
                errors.add("Duplicate connector name: " + c.name());
            }
        }
        Set<String> pluginNames = new HashSet<>();
        for (PluginConfig p : s.plugins()) {
            if (!pluginNames.add(p.name())) {
                errors.add("Duplicate plugin name: " + p.name());
            }
        }
    }

    private static void checkProfileReferences(ConfigSnapshot s, List<String> errors) {
        Set<String> profileNames = new HashSet<>();
        for (ProfileConfig p : s.profiles()) profileNames.add(p.name());

        for (ConnectorConfig c : s.connectors()) {
            if (c.profileName() != null && !profileNames.contains(c.profileName())) {
                errors.add("Connector '" + c.name() + "' references unknown profile: " + c.profileName());
            }
        }
        for (ProfileListenerConfig l : s.profileListeners()) {
            if (!profileNames.contains(l.profileName())) {
                errors.add("Listener references unknown profile: " + l.profileName());
            }
        }
        for (ServiceMapEntryConfig e : s.serviceMap()) {
            if (!profileNames.contains(e.profileName())) {
                errors.add("ServiceMap entry references unknown profile: " + e.profileName());
            }
        }
    }

    private static void checkConnectors(ConfigSnapshot s, List<String> errors) {
        Set<String> profileNames = s.profiles().stream().map(ProfileConfig::name).collect(Collectors.toSet());
        Set<String> enabledProfiles = s.profiles().stream().filter(ProfileConfig::enabled).map(ProfileConfig::name).collect(Collectors.toSet());
        for (ConnectorConfig c : s.connectors()) {
            if (c.cacheexMode() != null && c.cacheexMode() != 0 && c.cacheexMode() != 2 && c.cacheexMode() != 3) {
                errors.add("Connector '" + c.name() + "': cacheexMode must be 0, 2, or 3 (got " + c.cacheexMode() + ")");
            }
            if (c.priority() < 0 || c.priority() > 100) {
                errors.add("Connector '" + c.name() + "': priority must be 0-100 (got " + c.priority() + ")");
            }
            if ("oscam_ncd525".equalsIgnoreCase(c.newcamdDialect())) {
                errors.add("Connector '" + c.name() + "': dialect 'oscam_ncd525' requires full Eurocrypt cipher engine; use 'legacy'");
            }
            if (c.newcamdPasswordEncoding() != null && !c.newcamdPasswordEncoding().equalsIgnoreCase("plaintext") && !c.newcamdPasswordEncoding().equalsIgnoreCase("md5crypt")) {
                errors.add("Connector '" + c.name() + "': newcamdPasswordEncoding must be plaintext or md5crypt (got " + c.newcamdPasswordEncoding() + ")");
            }
            if (c.reshareCap() != null && (c.reshareCap() < 0 || c.reshareCap() > 10)) {
                errors.add("Connector '" + c.name() + "': reshareCap must be 0-10 (got " + c.reshareCap() + ")");
            }
            if (c.reshareGrant() != null && (c.reshareGrant() < 0 || c.reshareGrant() > 10)) {
                errors.add("Connector '" + c.name() + "': reshareGrant must be 0-10 (got " + c.reshareGrant() + ")");
            }
            if (c.hasFixedProfile()) {
                if (!profileNames.contains(c.profileName())) {
                    errors.add("Connector '" + c.name() + "' fixed profile unknown: " + c.profileName());
                } else if (!enabledProfiles.contains(c.profileName())) {
                    errors.add("Connector '" + c.name() + "' fixed profile disabled: " + c.profileName());
                }
            } else {
                long cnt = s.connectorCaidProfiles().stream().filter(e -> Objects.equals(e.connectorName(), c.name())).count();
                if (cnt == 0) {
                    errors.add("Connector '" + c.name() + "' is dynamic (NULL profile) but has no CAID map entries");
                }
            }
        }
    }

    private static void checkListeners(ConfigSnapshot s, List<String> errors) {
        for (ProfileListenerConfig l : s.profileListeners()) {
            if (l.port() < 1 || l.port() > 65535) {
                errors.add("Listener port out of range: " + l.port() + " for profile " + l.profileName());
            }
            if (!isValidInet(l.bindAddress())) {
                errors.add("Listener bind_address must be IP literal: " + l.bindAddress());
            }
            String proto = l.protocol() != null ? l.protocol().toLowerCase(Locale.ROOT) : "";
            if (("cccam".equals(proto) || "cs378x".equals(proto)) && !l.strictPolicy()) {
                errors.add("Listener for " + proto + " on profile " + l.profileName() + " requires strictPolicy=true");
            }
            if (l.cardMode() != null && !("manual".equals(l.cardMode()) || "wildcard".equals(l.cardMode()))) {
                errors.add("Listener cardMode must be 'manual' or 'wildcard', got: " + l.cardMode());
            }
            if (l.reshareCap() != null && (l.reshareCap() < 0 || l.reshareCap() > 10)) {
                errors.add("Listener reshareCap must be 0-10, got: " + l.reshareCap());
            }
            if (l.maxCards() < 1 || l.maxCards() > 4096) {
                errors.add("Listener maxCards must be 1-4096, got: " + l.maxCards());
            }
            if (l.maxInflight() < 1 || l.maxInflight() > 64) {
                errors.add("Listener maxInflight must be 1-64, got: " + l.maxInflight());
            }
            if (l.maxPerIp() < 1 || l.maxPerIp() > 100) {
                errors.add("Listener maxPerIp must be 1-100, got: " + l.maxPerIp());
            }
            if (l.idleTimeoutSec() < 5 || l.idleTimeoutSec() > 3600) {
                errors.add("Listener idleTimeoutSec must be 5-3600, got: " + l.idleTimeoutSec());
            }
        }
    }

    // -- Connector CAID maps --

    private static void checkConnectorCaidMaps(ConfigSnapshot s, List<String> errors) {
        Set<String> connectorNames = s.connectors().stream().map(ConnectorConfig::name).collect(Collectors.toSet());
        Set<String> profileNames = s.profiles().stream().map(ProfileConfig::name).collect(Collectors.toSet());
        Set<String> enabledProfiles = s.profiles().stream().filter(ProfileConfig::enabled).map(ProfileConfig::name).collect(Collectors.toSet());
        MapByConnector grouped = new MapByConnector(s);
        for (String conn : grouped.connectors()) {
            List<ConnectorCaidProfileConfig> entries = grouped.get(conn);
            if (!connectorNames.contains(conn)) {
                errors.add("CAID map references unknown connector: " + conn);
                continue;
            }
            ConnectorConfig cc = s.connectorByName().get(conn);
            if (cc != null && cc.hasFixedProfile() && !entries.isEmpty()) {
                errors.add("Connector '" + conn + "' has fixed profile but has CAID map entries");
            }
            Set<Integer> caIds = new HashSet<>();
            Set<Integer> ordinals = new HashSet<>();
            for (ConnectorCaidProfileConfig e : entries) {
                if (e.caId() < 0 || e.caId() > 65535) {
                    errors.add("Connector '" + conn + "' CAID out of range: " + e.caId());
                }
                if (!caIds.add(e.caId())) {
                    errors.add("Connector '" + conn + "' duplicate CAID: " + e.caId());
                }
                if (e.ordinal() < 0) {
                    errors.add("Connector '" + conn + "' ordinal negative: " + e.ordinal());
                }
                if (!ordinals.add(e.ordinal())) {
                    errors.add("Connector '" + conn + "' duplicate ordinal: " + e.ordinal());
                }
                if (!profileNames.contains(e.profileName())) {
                    errors.add("Connector '" + conn + "' CAID " + e.caId() + " references unknown profile: " + e.profileName());
                } else if (!enabledProfiles.contains(e.profileName())) {
                    errors.add("Connector '" + conn + "' CAID " + e.caId() + " references disabled profile: " + e.profileName());
                }
            }
            if (!ordinals.isEmpty()) {
                int max = ordinals.stream().mapToInt(Integer::intValue).max().orElse(-1);
                if (ordinals.size() != max + 1) {
                    for (int i = 0; i <= max; i++) {
                        if (!ordinals.contains(i)) {
                            errors.add("Connector '" + conn + "' missing ordinal: " + i);
                            break;
                        }
                    }
                }
            }
        }
    }

    // -- Provider idents --

    private static void checkProviderIdents(ConfigSnapshot s, List<String> errors) {
        Set<String> profileNames = s.profiles().stream().map(ProfileConfig::name).collect(Collectors.toSet());
        Set<String> enabledProfiles = s.profiles().stream().filter(ProfileConfig::enabled).map(ProfileConfig::name).collect(Collectors.toSet());
        java.util.Map<String, List<ProfileProviderIdentConfig>> byProfile = s.profileProviderIdents().stream()
            .collect(Collectors.groupingBy(ProfileProviderIdentConfig::profileName));
        for (var e : byProfile.entrySet()) {
            String profile = e.getKey();
            List<ProfileProviderIdentConfig> list = e.getValue();
            if (!profileNames.contains(profile)) {
                errors.add("Provider idents reference unknown profile: " + profile);
                continue;
            }
            if (!enabledProfiles.contains(profile)) {
                errors.add("Provider idents for disabled profile: " + profile);
            }
            Set<Integer> idents = new HashSet<>();
            for (ProfileProviderIdentConfig pi : list) {
                if (pi.providerIdent() < 0 || pi.providerIdent() > 0xFFFFFF) {
                    errors.add("Provider ident out of range for profile " + profile + ": " + pi.providerIdent());
                }
                if (!idents.add(pi.providerIdent())) {
                    errors.add("Duplicate provider ident " + pi.providerIdent() + " for profile " + profile);
                }
            }
        }
    }

    // -- Extended newcamd --

    private static void checkExtendedNewcamd(ConfigSnapshot s, List<String> errors) {
        ExtendedNewcamdConfig ext = s.extendedNewcamd();
        if (ext == null) {
            errors.add("extendedNewcamd is null");
            return;
        }
        if (ext.listenPort() < 1 || ext.listenPort() > 65535) {
            errors.add("Extended newcamd listenPort out of range: " + ext.listenPort());
        }
        if (ext.maxMsgSize() < 128 || ext.maxMsgSize() > 65536) {
            errors.add("Extended newcamd maxMsgSize out of range: " + ext.maxMsgSize());
        }
        if (!isValidInet(ext.bindAddress())) {
            errors.add("Extended newcamd bind_address must be IP literal: " + ext.bindAddress());
        }
        // listener vs listener collisions (always, even if extended disabled)
        for (int i = 0; i < s.profileListeners().size(); i++) {
            ProfileListenerConfig a = s.profileListeners().get(i);
            if (!a.enabled()) continue;
            for (int j = i + 1; j < s.profileListeners().size(); j++) {
                ProfileListenerConfig b = s.profileListeners().get(j);
                if (!b.enabled()) continue;
                if (a.port() != b.port()) continue;
                if (bindConflicts(a.bindAddress(), b.bindAddress())) {
                    errors.add("Port collision: listeners " + a.profileName() + "/" + a.protocol() + " " + a.bindAddress() + ":" + a.port()
                        + " vs " + b.profileName() + "/" + b.protocol() + " " + b.bindAddress() + ":" + b.port());
                }
            }
        }
        if (ext.enabled()) {
            if (ext.desKeyRef() != null && !ext.desKeyRef().isBlank() && !isValidSecretRef(ext.desKeyRef())) {
                errors.add("Extended newcamd desKeyRef invalid: " + ext.desKeyRef());
            } else if (ext.desKeyRef() == null || ext.desKeyRef().isBlank()) {
                errors.add("Extended newcamd enabled but desKeyRef is required");
            }
            // collision listeners vs extended
            for (ProfileListenerConfig l : s.profileListeners()) {
                if (!l.enabled()) continue;
                if (l.port() != ext.listenPort()) continue;
                if (bindConflicts(ext.bindAddress(), l.bindAddress())) {
                    errors.add("Port collision: extended-newcamd " + ext.bindAddress() + ":" + ext.listenPort()
                        + " vs listener " + l.profileName() + "/" + l.protocol() + " " + l.bindAddress() + ":" + l.port());
                }
            }
            if (!ext.catchAll()) {
                boolean hasRouting = false;
                for (ProfileConfig p : s.profiles()) {
                    if (p.caId() != 0 || p.networkId() != 0) { hasRouting = true; break; }
                }
                if (!hasRouting) {
                    for (ServiceMapEntryConfig sm : s.serviceMap()) { hasRouting = true; break; }
                }
                if (!hasRouting && !s.profileProviderIdents().isEmpty()) { hasRouting = true; }
                if (!hasRouting) {
                    errors.add("Extended newcamd enabled without catchAll requires at least one profile/service routing pair");
                }
            }
        }
    }

    // -- Proxy validation --

    private static final java.util.regex.Pattern PROXY_NAME_PATTERN =
        java.util.regex.Pattern.compile("^[A-Za-z0-9_.\\-]{1,64}$");

    private static void checkProxies(ConfigSnapshot s, List<String> errors) {
        if (s.proxies() == null) return;
        Set<String> names = new HashSet<>();
        for (NetworkProxyConfig p : s.proxies()) {
            if (!names.add(p.name())) {
                errors.add("Duplicate proxy name: " + p.name());
            }
            if (!PROXY_NAME_PATTERN.matcher(p.name()).matches()) {
                errors.add("Proxy '" + p.name() + "': name must match [A-Za-z0-9_.-]{1,64}");
            }
            if (!"socks5".equals(p.type())) {
                errors.add("Proxy '" + p.name() + "': type must be 'socks5', got: " + p.type());
            }
            if (p.host() == null || p.host().isBlank()) {
                errors.add("Proxy '" + p.name() + "': host required");
            } else {
                // Reject URI-like, userinfo, spaces, control chars
                String host = p.host();
                if (host.contains("://") || host.contains("@") || host.contains(" ")) {
                    errors.add("Proxy '" + p.name()
                        + "': host must not contain '://', '@', or spaces");
                }
                for (char c : host.toCharArray()) {
                    if (c < 32 || c == 127) {
                        errors.add("Proxy '" + p.name() + "': host contains control character");
                        break;
                    }
                }
            }
            if (p.port() < 1 || p.port() > 65535) {
                errors.add("Proxy '" + p.name() + "': port must be 1-65535, got: " + p.port());
            }
            // Auth pair check
            boolean hasUser = p.username() != null;
            boolean hasPwd  = p.passwordRef() != null;
            if (hasUser != hasPwd) {
                errors.add("Proxy '" + p.name()
                    + "': username and passwordRef must both be null or both non-null");
            }
            if (hasPwd) {
                checkSecretField("password_ref", p.passwordRef(), "Proxy '" + p.name() + "'", errors);
            }
            // PAS de timeout sur le proxy (Plan P2) : le budget T est porté par le
            // connecteur (csp_connector.connect_timeout_ms, validé 1000..300000
            // sur le chemin proxy par le lifecycle).
            if (p.description() != null && p.description().length() > 500) {
                errors.add("Proxy '" + p.name() + "': description max 500 chars");
            }
        }
    }

    /**
     * Vérifie que chaque connecteur référençant un proxy pointe vers un proxy existant.
     * Un proxy désactivé référencé est ACCEPTÉ (kill switch : il est intentionnel de
     * conserver une référence vers un proxy désactivé pour couper la connexion sans
     * supprimer la configuration du connecteur).
     */
    private static void checkConnectorProxyRefs(ConfigSnapshot s, List<String> errors) {
        if (s.proxies() == null || s.connectors() == null) return;
        Set<String> knownProxies = s.proxies().stream()
            .map(NetworkProxyConfig::name)
            .collect(Collectors.toSet());
        for (ConnectorConfig c : s.connectors()) {
            if (c.proxyName() != null && !knownProxies.contains(c.proxyName())) {
                errors.add("Connector '" + c.name()
                    + "' references unknown proxy: " + c.proxyName());
            }
            // Note: proxy désactivé intentionnellement accepté — pas d'erreur ici.
        }
    }

    // -- Helpers --

    private static class MapByConnector {
        private final java.util.Map<String, List<ConnectorCaidProfileConfig>> map;
        MapByConnector(ConfigSnapshot s) {
            map = s.connectorCaidProfiles().stream().collect(Collectors.groupingBy(ConnectorCaidProfileConfig::connectorName));
        }
        Set<String> connectors() { return map.keySet(); }
        List<ConnectorCaidProfileConfig> get(String c) { return map.getOrDefault(c, List.of()); }
    }

    // -- IP literal helpers (sans DNS) --
    // Politique: :: est considéré IPv6-only pour collision cross-family ; runtime ListenerManager devra appliquer IPV6_V6ONLY cohérent ou fail-fast.
    public static boolean isValidInet(String addr) {
        if (addr == null || addr.isBlank()) return false;
        String a = addr.trim();
        // Host-only : rejeter CIDR/networks (ex: 192.168.1.1/24, ::/64)
        if (a.contains("/")) return false;
        return parseInetAddress(a) != null;
    }

    public static boolean isWildcard(String addr) {
        byte[] b = parseInetAddress(addr);
        if (b == null) return false;
        for (byte bb : b) if (bb != 0) return false;
        return true;
    }

    public static boolean bindConflicts(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ba = parseInetAddress(a);
        byte[] bb = parseInetAddress(b);
        if (ba == null || bb == null) return false;
        if (java.util.Arrays.equals(ba, bb)) return true;
        boolean wa = isWildcard(a);
        boolean wb = isWildcard(b);
        if (wa && ba.length == bb.length) return true;
        if (wb && ba.length == bb.length) return true;
        return false;
    }

    // -- Parseurs IP littéraux 100% locaux (pas de DNS) --

    private static byte[] parseInetAddress(String addr) {
        if (addr == null) return null;
        String s = addr.trim();
        if (s.isEmpty()) return null;
        if (s.contains(":")) {
            // IPv6 (y compris formes IPv4-mapped)
            return parseIPv6Strict(s);
        } else if (s.contains(".")) {
            return parseIPv4Strict(s);
        }
        return null;
    }

    private static byte[] parseIPv4Strict(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || !p.matches("\\d+")) return null;
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return null;
                out[i] = (byte) v;
            } catch (NumberFormatException e) { return null; }
        }
        return out;
    }

    private static byte[] parseIPv6Strict(String s) {
        if (s.contains(".")) {
            // Forme avec suffixe IPv4 (IPv4-mapped / IPv4-compatible)
            int lastColon = s.lastIndexOf(':');
            if (lastColon < 0) return null;
            // seul le dernier token (après le dernier ':') peut contenir des '.'
            if (s.lastIndexOf('.', lastColon - 1) >= 0) return null;
            String dotted = s.substring(lastColon + 1);
            byte[] v4 = parseIPv4Strict(dotted);
            if (v4 == null) return null;
            String hexPart = s.substring(0, lastColon);
            String w1 = String.format("%02x%02x", v4[0] & 0xff, v4[1] & 0xff);
            String w2 = String.format("%02x%02x", v4[2] & 0xff, v4[3] & 0xff);
            String combined = hexPart.isEmpty() ? (w1 + ":" + w2) : (hexPart + ":" + w1 + ":" + w2);
            return parsePureIPv6(combined);
        }
        return parsePureIPv6(s);
    }

    private static byte[] parsePureIPv6(String s) {
        if (s.isEmpty()) return null;
        int doubleColon = s.indexOf("::");
        boolean hasCompression = doubleColon >= 0;
        if (hasCompression && s.indexOf("::", doubleColon + 2) >= 0) return null;

        String head = hasCompression ? s.substring(0, doubleColon) : s;
        String tail = hasCompression ? s.substring(doubleColon + 2) : "";
        String[] headParts = head.isEmpty() ? new String[0] : head.split(":", -1);
        String[] tailParts = tail.isEmpty() ? new String[0] : tail.split(":", -1);
        for (String p : headParts) if (p.isEmpty()) return null;
        for (String p : tailParts) if (p.isEmpty()) return null;

        int explicit = headParts.length + tailParts.length;
        int missing = 8 - explicit;
        if (hasCompression) {
            if (missing <= 0) return null;
        } else {
            if (missing != 0) return null;
        }

        byte[] out = new byte[16];
        int pos = 0;
        for (String p : headParts) {
            int v = parseHexGroup(p);
            if (v < 0) return null;
            out[pos++] = (byte) (v >>> 8);
            out[pos++] = (byte) v;
        }
        for (int z = 0; z < missing; z++) {
            out[pos++] = 0;
            out[pos++] = 0;
        }
        for (String p : tailParts) {
            int v = parseHexGroup(p);
            if (v < 0) return null;
            out[pos++] = (byte) (v >>> 8);
            out[pos++] = (byte) v;
        }
        return pos == 16 ? out : null;
    }

    private static int parseHexGroup(String g) {
        if (g == null || g.isEmpty() || g.length() > 4) return -1;
        try {
            return Integer.parseInt(g, 16);
        } catch (NumberFormatException e) { return -1; }
    }

    // -- Secret refs : canonique env:NAME/file:/path/vault:path + compat legacy ${...} --

    private static final java.util.regex.Pattern SECRET_REF_PATTERN =
        java.util.regex.Pattern.compile(
            "^(?:" 
            + "\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}"        // ${ENV_VAR} legacy
            + "|\\$\\{env:[A-Za-z_][A-Za-z0-9_]*\\}"   // ${env:NAME} legacy
            + "|\\$\\{file:[^}]+\\}"                  // ${file:/path} legacy
            + "|\\$\\{vault:[^}]+\\}"                 // ${vault:path} legacy
            + "|env:[A-Za-z_][A-Za-z0-9_]*"           // env:NAME canonique (persisté)
            + "|file:[^\\s]+"
            + "|vault:[^\\s]+"
            + ")$"
        );

    public static boolean isValidSecretRef(String ref) {
        if (ref == null) return true;
        if (ref.isBlank()) return false;
        // Pas de trim silencieux : entourant whitespace invalide (ex: "file:/x\n")
        if (!ref.equals(ref.trim())) return false;
        String r = ref;
        if (!SECRET_REF_PATTERN.matcher(r).matches()) return false;
        // Durcissement file: doit être chemin absolu sans .. ni NUL/control
        // Le résolveur futur devra encore allowlist racines (ex: /run/secrets, /etc/csp) — documenté ici sans créer résolveur.
        if (r.startsWith("file:") || r.startsWith("${file:")) {
            String path;
            if (r.startsWith("${file:")) {
                path = r.substring(7, r.length() - 1);
            } else {
                path = r.substring(5);
            }
            if (!path.startsWith("/")) return false;
            if (path.indexOf('\0') >= 0) return false;
            for (char c : path.toCharArray()) {
                if (c < 32 || c == 127) return false;
            }
            // pas de segment ..
            for (String seg : path.split("/")) {
                if ("..".equals(seg)) return false;
            }
            // pas de double slash avec .. déjà couvert, mais on interdit aussi // (optionnel)
        }
        // vault et env inchangés (allowlist future si besoin)
        return true;
    }

    private static void checkSecretField(String field, String value, String context, List<String> errors) {
        if (value == null) return;
        if (value.isBlank()) {
            errors.add(context + ": " + field + " is blank");
            return;
        }
        if (!isValidSecretRef(value)) {
            errors.add(context + ": " + field + " must be env:NAME or file:/path or vault:path (legacy ${...} accepted), got: " + value);
        }
    }

    private static void checkSecrets(ConfigSnapshot s, List<String> errors) {
        checkSecretField("default_des_key_ref", s.caProfilesGlobal().defaultDesKeyRef(), "CaProfilesGlobal", errors);
        checkSecretField("default_connector_des_key_ref", s.caProfilesGlobal().defaultConnectorDesKeyRef(), "CaProfilesGlobal", errors);
        checkSecretField("default_client_id_ref", s.caProfilesGlobal().defaultClientIdRef(), "CaProfilesGlobal", errors);

        for (ConnectorConfig c : s.connectors()) {
            String ctx = "Connector '" + c.name() + "'";
            checkSecretField("password_ref", c.passwordRef(), ctx, errors);
            checkSecretField("des_key_ref", c.desKeyRef(), ctx, errors);
            checkSecretField("newcamd_client_id_ref", c.newcamdClientIdRef(), ctx, errors);
        }
        for (ProfileListenerConfig l : s.profileListeners()) {
            String ctx = "Listener " + l.profileName() + "/" + l.protocol() + ":" + l.port();
            checkSecretField("des_key_ref", l.desKeyRef(), ctx, errors);
        }
        checkSecretField("tracker_url_ref", s.clusterUdp().trackerUrlRef(), "ClusterUdp", errors);
        checkSecretField("tls_cert_ref", s.webui().tlsCertRef(), "WebUi", errors);
        checkSecretField("tls_key_ref", s.webui().tlsKeyRef(), "WebUi", errors);
        checkSecretField("admin_user_ref", s.webui().adminUserRef(), "WebUi", errors);
        checkSecretField("admin_password_ref", s.webui().adminPasswordRef(), "WebUi", errors);
        if (s.extendedNewcamd() != null) {
            checkSecretField("des_key_ref", s.extendedNewcamd().desKeyRef(), "ExtendedNewcamd", errors);
        }
    }

    /** ADR 0002 : references relay, pas d'activation de pair inconnu, peers UDP sur caches udp. */
    private static void checkRelay(ConfigSnapshot s, List<String> errors) {
        java.util.Set<String> nodeNames = new java.util.HashSet<>();
        for (var n : s.relayNodes()) {
            if (!nodeNames.add(n.nodeName())) errors.add("Duplicate relay node: " + n.nodeName());
            if (n.enabled() && !n.authorized())
                errors.add("Relay node '" + n.nodeName() + "' enabled without authorized=true (unknown peer must stay detached)");
            if (n.appliedVersion() != null && n.appliedVersion() > n.desiredVersion())
                errors.add("Relay node '" + n.nodeName() + "' appliedVersion > desiredVersion");
        }
        java.util.Set<String> cacheNames = new java.util.HashSet<>();
        java.util.Map<String, String> cacheKind = new java.util.HashMap<>();
        for (var c : s.relayCaches()) {
            if (!cacheNames.add(c.name())) errors.add("Duplicate relay cache: " + c.name());
            cacheKind.put(c.name(), c.kind());
            if ("node".equals(c.kind()) && (c.nodeName() == null || !nodeNames.contains(c.nodeName())))
                errors.add("Relay cache '" + c.name() + "' references unknown node: " + c.nodeName());
        }
        java.util.Set<Long> listenerIds = new java.util.HashSet<>();
        for (var l : s.profileListeners()) listenerIds.add(l.id());
        java.util.Set<String> connectorNames = new java.util.HashSet<>();
        for (var c : s.connectors()) connectorNames.add(c.name());
        for (var a : s.nodeListeners()) {
            if (!nodeNames.contains(a.nodeName()))
                errors.add("Relay assignment references unknown node: " + a.nodeName());
            if (!listenerIds.contains(a.listenerId()))
                errors.add("Relay assignment references unknown listener id: " + a.listenerId());
        }
        for (var a : s.nodeConnectors()) {
            if (!nodeNames.contains(a.nodeName()))
                errors.add("Relay assignment references unknown node: " + a.nodeName());
            if (!connectorNames.contains(a.connectorName()))
                errors.add("Relay assignment references unknown connector: " + a.connectorName());
        }
        for (var link : s.listenerCacheLinks()) {
            if (!listenerIds.contains(link.listenerId()))
                errors.add("Relay link references unknown listener id: " + link.listenerId());
            if (!cacheNames.contains(link.cacheName()))
                errors.add("Relay link references unknown cache: " + link.cacheName());
            // UDP consultable uniquement via transport cable + pairs allowlistes :
            // sans pair assigne, l'echange echoue ferme (pas d'erreur de config).
        }
        for (var p : s.udpPeers()) {
            if (!cacheNames.contains(p.cacheName()))
                errors.add("Relay udp peer references unknown cache: " + p.cacheName());
            else if (!"udp".equals(cacheKind.get(p.cacheName())))
                errors.add("Relay udp peer on non-udp cache: " + p.cacheName()
                    + " (peers UDP = caches kind=udp exclusivement)");
        }
    }

    private static void checkDcwPolicy(ConfigSnapshot s, List<String> errors) {
        if (s.dcwPolicy() == null) return;
        Set<String> profileNames = s.profiles().stream().map(ProfileConfig::name).collect(Collectors.toSet());
        errors.addAll(DcwConfigRules.validatePolicy(s.dcwPolicy(), profileNames));
    }

    /**
     * FIX Sonnet #6 : localNodeId obligatoire des qu'un relais est actif
     * (anti-boucle). Appele au demarrage par {@code RelayPolicyService}.
     */
    public static List<String> validateRelayRequiresLocalNode(ConfigSnapshot s, String localNodeId) {
        if (s == null) return List.of("snapshot is null");
        boolean anyActive = false;
        for (var n : s.relayNodes()) if (n.enabled()) { anyActive = true; break; }
        if (!anyActive) for (var c : s.relayCaches()) if (c.enabled()) { anyActive = true; break; }
        if (!anyActive) for (var l : s.listenerCacheLinks()) if (l.enabled()) { anyActive = true; break; }
        if (anyActive && (localNodeId == null || localNodeId.isBlank())) {
            return List.of("csp.relay.local-node-id is required when any relay node/cache/link is enabled (anti-loop)");
        }
        return List.of();
    }
}
