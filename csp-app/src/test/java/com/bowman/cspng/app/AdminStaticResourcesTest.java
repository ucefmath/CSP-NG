package com.bowman.cspng.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestProfile(AdminStaticResourcesTest.Profile.class)
class AdminStaticResourcesTest {

    @Test
    void indexHtmlServedWithSecurityHeadersAndNoInline() {
        String csp = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
        String body = given()
            .get("/admin/index.html")
            .then()
            .statusCode(200)
            .contentType(containsString("text/html"))
            .header("Cache-Control", containsString("no-store"))
            .header("Content-Security-Policy", containsString("default-src 'self'"))
            .header("Content-Security-Policy", containsString("script-src 'self'"))
            .header("Content-Security-Policy", not(containsString("unsafe-inline")))
            .header("Content-Security-Policy", not(containsString("data:")))
            .header("Content-Security-Policy", equalTo(csp))
            .header("X-Content-Type-Options", containsString("nosniff"))
            .header("X-Frame-Options", equalTo("DENY"))
            .header("Strict-Transport-Security", containsString("max-age=31536000"))
            .header("Referrer-Policy", notNullValue())
            .header("Permissions-Policy", notNullValue())
            .extract().body().asString();

        // SPA React : landmarks rendus côté client ; le shell servi garantit
        // racine, langue, script externe déterministe et feuille de style.
        // (landmarks header/nav/main/dialog couverts en E2E : e2e/a11y.spec.ts.)
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("<html lang=\"fr\""), "html lang fr");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("<div id=\"root\">"), "SPA root div");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("/admin/assets/app.js"), "bundle script");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("/admin/assets/app.css"), "bundle stylesheet");

        // all script tags must have src and no inline content
        Pattern scriptTag = Pattern.compile("<script[^>]*>(.*?)</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        var m = scriptTag.matcher(body);
        while (m.find()) {
            String full = m.group(0);
            String inner = m.group(1).trim();
            org.junit.jupiter.api.Assertions.assertTrue(full.contains("src="), "script without src (inline) found: " + full);
            org.junit.jupiter.api.Assertions.assertTrue(inner.isEmpty(), "inline script content found: " + inner);
        }
        // no inline handlers / style attr / external URL / style tag with content
        org.junit.jupiter.api.Assertions.assertFalse(body.matches("(?s).*\\son\\w+\\s*=.*"), "no inline handlers");
        org.junit.jupiter.api.Assertions.assertFalse(body.matches("(?s).*<[^>]+\\sstyle\\s*=.*"), "no style attribute");
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("https://"), "no external https URL");
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("http://"), "no external http URL");
        org.junit.jupiter.api.Assertions.assertFalse(body.toLowerCase().contains("<style"), "no inline style tag");
    }

    @Test
    void cssServedWithCspAndCache() {
        given()
            .get("/admin/assets/app.css")
            .then()
            .statusCode(200)
            .contentType(containsString("text/css"))
            .header("Content-Security-Policy", containsString("default-src 'self'"))
            .header("X-Content-Type-Options", containsString("nosniff"))
            .header("Cache-Control", containsString("must-revalidate"));
    }

    @Test
    void jsModulesServedWithSecurityHeaders() {
        // Bundle Vite déterministe (noms fixes, build Maven reproductible).
        for (String p : new String[]{"/admin/assets/app.js"}) {
            given().get(p).then()
                .statusCode(200)
                .contentType(anyOf(containsString("javascript"), containsString("application/javascript")))
                .header("Content-Security-Policy", containsString("default-src 'self'"))
                .header("X-Content-Type-Options", containsString("nosniff"));
        }
    }

    @Test
    void jsSourceHasNoForbiddenPatternsAndUsesTextEncoder() {
        String appJs = given().get("/admin/assets/app.js").then().statusCode(200).extract().body().asString();
        String css = given().get("/admin/assets/app.css").then().statusCode(200).extract().body().asString();
        // puits DOM / stockages interdits (secrets jamais en stockage web ;
        // sessionStorage éventuel = internals routeur, jamais nos secrets)
        org.junit.jupiter.api.Assertions.assertFalse(appJs.contains("localStorage"), "no localStorage");
        org.junit.jupiter.api.Assertions.assertFalse(appJs.contains("document.cookie"), "no cookie");
        // pas de rendu HTML brut applicatif : garanti au niveau sources
        // (AdminUiE2eContractTest scanne les .tsx : aucun dangerouslySetInnerHTML)
        // et au runtime (e2e/dcw.spec.ts : payload XSS rendu en texte, aucun dialogue JS).
        // Le littéral éventuel dans le bundle provient des internals React DOM, jamais invoqués.
        org.junit.jupiter.api.Assertions.assertFalse(appJs.contains("unescape"), "unescape deprecated must not be used");
        // auth : cookie même origine + CSRF sur mutations
        org.junit.jupiter.api.Assertions.assertTrue(appJs.contains("same-origin"), "same-origin credentials required");
        org.junit.jupiter.api.Assertions.assertTrue(appJs.contains("X-CSP-Requested-With") && appJs.contains("CSP-WebUI"), "CSRF header required");
        // wiring : bulk + materialize + nouveaux endpoints
        org.junit.jupiter.api.Assertions.assertTrue(appJs.contains("/bulk"), "bulk endpoint wiring required");
        org.junit.jupiter.api.Assertions.assertTrue(appJs.contains("/api/admin/secrets/materialize"), "materialize wiring required");
        org.junit.jupiter.api.Assertions.assertTrue(appJs.contains("/api/admin/cacheex/stats"), "cacheex stats wiring required");
        org.junit.jupiter.api.Assertions.assertTrue(appJs.contains("/api/admin/config/cw-cycle"), "cw-cycle wiring required");
        org.junit.jupiter.api.Assertions.assertTrue(appJs.contains("/api/admin/cache/status"), "cache status wiring required");
        // versions optimistes par ligne
        org.junit.jupiter.api.Assertions.assertTrue(appJs.contains("version"), "optimistic version wiring required");
        // ACL chips + navigation regroupée accessibles
        org.junit.jupiter.api.Assertions.assertTrue(appJs.contains("chip-group") || appJs.contains("allowedConnectors"), "chips ACL present");
        org.junit.jupiter.api.Assertions.assertTrue(appJs.contains("Navigation principale"), "grouped nav required");
        org.junit.jupiter.api.Assertions.assertTrue(css.contains(".chip"), "css must contain chip styling");
        // DELETE avec version en query
        org.junit.jupiter.api.Assertions.assertTrue(appJs.contains("?version="), "version query on DELETE required");
    }

    @Test
    void adminRootHasNoStore() {
        int status = given().get("/admin/").then().extract().statusCode();
        org.junit.jupiter.api.Assertions.assertTrue(status==200 || status==301 || status==302, "status "+status);
        if (status==200) {
            given().get("/admin/").then().header("Cache-Control", containsString("no-store"));
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.http.auth.basic", "true",
                "csp.config.pg.enabled", "false",
                "csp.admin.bootstrap-required", "false",
                "csp.admin.require-https", "false",
                "quarkus.flyway.enabled", "false",
                "quarkus.datasource.devservices.enabled", "false"
            );
        }
    }
}
