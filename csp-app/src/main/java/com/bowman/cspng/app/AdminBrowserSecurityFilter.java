package com.bowman.cspng.app;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;

import java.net.URI;

/**
 * Filtre de sécurité navigateur pour les endpoints admin sensibles.
 * Enregistré sur le Router Vert.x AVANT les mécanismes d'auth (order MIN_VALUE)
 * afin d'intercepter avant les endpoints REST et SSE.
 *
 * <p>Règles :</p>
 * <ul>
 *   <li>POST canonique {@code /api/admin/session} (login JSON) : exige {@code X-CSP-Requested-With: CSP-WebUI}.
 *       Sans CSRF → 403 JSON générique + no-store/nosniff. Aucun body inspection.</li>
 *   <li>GET canonique {@code /api/admin/events} (SSE) : validation strict same-origin si Origin présent.
 *       Si Origin absent et cookie exact {@code CSPADMINSESSION} présent sans Authorization Basic explicite
 *       → 403 (anti {@code <img>}/DoS). Si Authorization Basic explicite présente, auth décide (200/401).
 *       Sans Origin/cookie/Authorization → auth (401).</li>
 *   <li>Origin strict RFC6454 : scheme http/https, host non null, pas de userInfo, path, query, fragment,
 *       ni Origins multiples. Host request uniquement via {@code authority()}, pas de fallback Host manuel,
 *       pas de X-Forwarded* trusté. Normalisation crochets IPv6 + lower-case ; ports défaut 80/443.</li>
 *   <li>Autres chemins/méthodes inchangés.</li>
 * </ul>
 */
@ApplicationScoped
public class AdminBrowserSecurityFilter {

    private static final String LOGIN_CANONICAL = "/api/admin/session";
    private static final String SSE_CANONICAL = "/api/admin/events";
    private static final String COOKIE_NAME = "CSPADMINSESSION";
    private static final String LEGACY_COOKIE_NAME = "csp-admin-session";
    private static final String CSRF_HEADER = "X-CSP-Requested-With";
    private static final String CSRF_EXPECTED = "CSP-WebUI";
    private static final String BASIC_PREFIX = "Basic ";

    void onStart(@Observes StartupEvent ev, Router router) {
        router.route().order(Integer.MIN_VALUE).handler(this::handle);
    }

    private void handle(RoutingContext rc) {
        HttpMethod method = rc.request().method();

        if (method == HttpMethod.POST && isLoginPath(rc)) {
            handleLogin(rc);
            return;
        }
        if (method == HttpMethod.GET && isSsePath(rc)) {
            handleSse(rc);
            return;
        }
        rc.next();
    }

    /**
     * Détermine si le chemin correspond au login Form, en utilisant la normalisation Vert.x.
     * Stratégie fail-closed : si la normalisation n'est pas disponible, seul le chemin brut exact
     * est reconnu ; les variantes ne déclenchent pas le traitement et tombent en 404 (ou 403 si
     * le filtre les intercepte explicitement), mais ne peuvent pas authentifier ni provoquer de
     * Set-Cookie.
     */
    private boolean isLoginPath(RoutingContext rc) {
        String normalized = safeNormalizedPath(rc);
        if (normalized != null) {
            return LOGIN_CANONICAL.equals(normalized);
        }
        return LOGIN_CANONICAL.equals(rc.request().path());
    }

    private boolean isSsePath(RoutingContext rc) {
        String normalized = safeNormalizedPath(rc);
        if (normalized != null) {
            return SSE_CANONICAL.equals(normalized);
        }
        return SSE_CANONICAL.equals(rc.request().path());
    }

    private String safeNormalizedPath(RoutingContext rc) {
        try {
            return rc.normalizedPath();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void handleLogin(RoutingContext rc) {
        String csrf = rc.request().getHeader(CSRF_HEADER);
        if (!CSRF_EXPECTED.equals(csrf)) {
            sendForbidden(rc);
            return;
        }
        rc.next();
    }

    private void handleSse(RoutingContext rc) {
        String origin = rc.request().getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            String cookieHeader = rc.request().getHeader("Cookie");
            String auth = rc.request().getHeader("Authorization");
            boolean hasSessionCookie = hasExactCookie(cookieHeader, COOKIE_NAME) || hasExactCookie(cookieHeader, LEGACY_COOKIE_NAME);
            boolean hasBasic = hasBasicCredentials(auth);
            if (hasSessionCookie && !hasBasic) {
                // Cross-context sans Origin (ex: <img>, EventSource) avec cookie mais pas Basic → 403
                sendForbidden(rc);
                return;
            }
            // Absent Origin + Basic explicite → autorisé ; absent sans creds → vers auth (401)
            rc.next();
            return;
        }

        if (!isValidSameOrigin(rc, origin)) {
            sendForbidden(rc);
            return;
        }
        rc.next();
    }

    private boolean hasExactCookie(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return false;
        }
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.equals(name) || trimmed.startsWith(name + "=")) {
                // Vérifie la correspondance exacte du nom, pas un préfixe (ex: csp-admin-session-extra=)
                if (trimmed.length() == name.length() || trimmed.charAt(name.length()) == '=') {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasBasicCredentials(String auth) {
        return auth != null && auth.length() > BASIC_PREFIX.length()
                && auth.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length());
    }

    private boolean isValidSameOrigin(RoutingContext rc, String origin) {
        // Origins multiples / valeur comma-separated interdits
        if (origin.indexOf(',') >= 0) {
            return false;
        }
        URI originUri;
        try {
            originUri = URI.create(origin.trim());
        } catch (Exception e) {
            return false;
        }
        if (originUri.isOpaque()) {
            return false;
        }
        String scheme = originUri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return false;
        }
        if (originUri.getUserInfo() != null) {
            return false;
        }
        String path = originUri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return false;
        }
        if (originUri.getQuery() != null || originUri.getFragment() != null) {
            return false;
        }
        String originHostRaw = originUri.getHost();
        if (originHostRaw == null || originHostRaw.isBlank()) {
            return false;
        }

        // Request side : authority() uniquement, pas de fallback Host manuel
        if (rc.request().authority() == null) {
            return false;
        }
        String reqHostRaw = rc.request().authority().host();
        int reqPort = rc.request().authority().port();
        if (reqHostRaw == null || reqHostRaw.isBlank()) {
            return false;
        }

        String originHost = normalizeHost(originHostRaw);
        String reqHost = normalizeHost(reqHostRaw);
        String reqScheme = rc.request().scheme();
        if (reqScheme == null) {
            return false;
        }
        int originPort = originUri.getPort();
        int originEffectivePort = originPort != -1 ? originPort : defaultPort(scheme);
        int reqEffectivePort = reqPort != -1 ? reqPort : defaultPort(reqScheme);

        return scheme.equalsIgnoreCase(reqScheme)
                && originHost.equals(reqHost)
                && originEffectivePort == reqEffectivePort;
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim();
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        return h.toLowerCase();
    }

    private static int defaultPort(String scheme) {
        if (scheme == null) {
            return -1;
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return -1;
    }

    private void sendJsonError(RoutingContext rc, int status, String code, String message) {
        rc.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .putHeader("Cache-Control", "no-store")
                .putHeader("Pragma", "no-cache")
                .putHeader("X-Content-Type-Options", "nosniff")
                .putHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .end("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private void sendForbidden(RoutingContext rc) {
        sendJsonError(rc, 403, "CSRF_INVALID", "Invalid request");
    }

    private void sendUnsupportedMediaType(RoutingContext rc) {
        sendJsonError(rc, 415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type");
    }
}
