package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.runtime.RuntimeSecretResolver;
import com.bowman.cspng.app.runtime.SecretMaterializer;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Future;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Matérialisation de secrets inline en fichiers serveur 0600 (contrat v2 §1).
 *
 * <p>POST ONLY — pas de DELETE ni de listing (arbitrage sécurité). Chaque appel crée un
 * fichier NOUVEAU au nom opaque imprévisible ({@link SecretMaterializer#materializeUnique}) :
 * aucun overwrite idempotent ne peut modifier un secret déjà référencé en DB.
 *
 * <p>Sécurité :
 * <ul>
 *   <li>la valeur ({@code value}) est write-only : jamais échoquée, jamais loggée, jamais
 *       auditée — l'audit ne porte que purpose/owner/secretRef ;</li>
 *   <li>écriture hors event-loop (executeBlocking dans le materializer), racine sous
 *       allowlist, anti-symlink, 0600/0700 ;</li>
 *   <li>le client DOIT ensuite persister la référence via le PUT de config concerné ;
 *       un fichier non référencé devient orphelin (accepté, documenté).</li>
 * </ul>
 */
@Path("/api/admin/secrets/materialize")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminSecretResource {

  private static final Logger LOG = Logger.getLogger(AdminSecretResource.class.getName());

  private static final int MAX_VALUE_BYTES = 4096;

  /** purpose → préfixe fichier / extension / type. */
  private enum Purpose {
    CONNECTOR_PASSWORD("connector-password", "csp-connector", "pwd", false),
    CONNECTOR_DES_KEY("connector-des-key", "csp-connector", "des", true),
    LISTENER_DES_KEY("listener-des-key", "csp-listener", "des", true),
    PROXY_PASSWORD("proxy-password", "csp-proxy", "pwd", false),
    USER_PROTOCOL_SECRET("user-protocol-secret", "csp-user", "pwd", false),
    EXTENDED_NEWCAMD_DES_KEY("extended-newcamd-des-key", "csp-extncd", "des", true);

    final String wire;
    final String prefix;
    final String ext;
    final boolean desKey;

    Purpose(String wire, String prefix, String ext, boolean desKey) {
      this.wire = wire;
      this.prefix = prefix;
      this.ext = ext;
      this.desKey = desKey;
    }

    static Purpose fromWire(String s) {
      for (Purpose p : values()) if (p.wire.equals(s)) return p;
      return null;
    }
  }

  @Inject
  SecretMaterializer secretMaterializer;

  @Inject
  com.bowman.cspng.config.ConfigRepository configRepository;

  @Context
  SecurityContext securityContext;

  @POST
  public Uni<Response> materialize(@Valid MaterializeSecretRequest req) {
    Purpose purpose = Purpose.fromWire(req == null ? null : req.purpose());
    if (purpose == null) {
      return bad("INVALID_INPUT",
          "purpose must be one of connector-password, connector-des-key, listener-des-key,"
              + " proxy-password, user-protocol-secret, extended-newcamd-des-key");
    }
    if (req.value() == null || req.value().isEmpty()) {
      return bad("INVALID_INPUT", "value is required (write-only, never echoed)");
    }
    if (req.value().getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
      return bad("INVALID_INPUT", "value exceeds " + MAX_VALUE_BYTES + " bytes");
    }

    final byte[] content;
    if (purpose.desKey) {
      // Validation + normalisation : 28 hex (14 octets), espaces/":" tolérés — même parser
      // strict que RuntimeSecretResolver (fail-closed). La valeur n'est JAMAIS loggée.
      try {
        byte[] key14 = RuntimeSecretResolver.parseDesKey14(req.value(), "materialize:" + purpose.wire);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key14.length; i++) {
          if (i > 0) sb.append(' ');
          sb.append(String.format("%02X", key14[i] & 0xFF));
        }
        content = sb.toString().getBytes(StandardCharsets.UTF_8);
        Arrays.fill(key14, (byte) 0);
      } catch (RuntimeException e) {
        return bad("INVALID_INPUT", e.getMessage());
      }
    } else {
      content = req.value().getBytes(StandardCharsets.UTF_8);
    }

    Future<String> fut = secretMaterializer.materializeUnique(
        purpose.prefix, req.owner(), purpose.ext, content);
    return Uni.createFrom().completionStage(fut.toCompletionStage())
        .map(ref -> {
          // Audit DB best-effort : purpose/owner/secretRef uniquement — JAMAIS la valeur.
          // L'échec d'audit ne fait pas échouer la matérialisation (fichier déjà créé).
          String who = actor();
          if (configRepository != null) {
            configRepository.auditEvent("SECRET_MATERIALIZED", purpose.wire, req.owner(), who, ref)
                .onComplete(ar -> {
                  if (ar.failed()) {
                    LOG.log(Level.WARNING, "secret materialize audit failed (best-effort): "
                        + ar.cause().getMessage());
                  }
                });
          }
          LOG.info(() -> "secret materialized purpose=" + purpose.wire
              + " owner=" + req.owner() + " ref=" + ref + " by " + who);
          return Response.status(201)
              .header("Cache-Control", "no-store")
              .header("Pragma", "no-cache")
              .entity(new MaterializeSecretResponse(ref, purpose.wire, req.owner()))
              .build();
        })
        .onFailure().recoverWithItem(e -> {
          // Message d'erreur SANS la valeur (contexte technique uniquement)
          LOG.log(Level.WARNING, "secret materialization failed purpose=" + purpose.wire
              + " owner=" + req.owner() + ": " + e.getMessage());
          return Response.status(500)
              .header("Cache-Control", "no-store")
              .entity(ErrorResponse.of("SECRET_WRITE_FAILED",
                  "secret materialization failed (" + e.getClass().getSimpleName() + ")"))
              .build();
        });
  }

  private static Uni<Response> bad(String code, String msg) {
    return Uni.createFrom().item(
        Response.status(400).entity(ErrorResponse.of(code, msg)).build());
  }

  private String actor() {
    Principal p = securityContext != null ? securityContext.getUserPrincipal() : null;
    return p != null ? p.getName() : "unknown";
  }

  // -- DTOs --

  public record MaterializeSecretRequest(
      @NotBlank String purpose,
      @NotBlank @Size(max = 64) String owner,
      @NotBlank @Size(max = 8192) String value) {}

  public record MaterializeSecretResponse(String secretRef, String purpose, String owner) {}
}
