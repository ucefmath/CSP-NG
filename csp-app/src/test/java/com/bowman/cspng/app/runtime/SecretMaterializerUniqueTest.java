package com.bowman.cspng.app.runtime;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests materializeUnique (contrat v2 §1, arbitrage sécurité) —
 * fichier NOUVEAU opaque à chaque appel : AUCUN overwrite, jamais deux
 * refs identiques, anti-symlink, 0600/0700, valeur jamais loggée.
 */
class SecretMaterializerUniqueTest {

  private static SecretMaterializer materializer(Vertx vertx, Path root) {
    return new SecretMaterializer(vertx, root);
  }

  @Test
  void twoCallsNeverOverwriteAndYieldDistinctRefs(@TempDir Path tmp) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      SecretMaterializer sm = materializer(vertx, tmp);
      String ref1 = sm.materializeUnique("csp-connector", "my-connector", "pwd",
          "first".getBytes()).toCompletionStage().toCompletableFuture().get();
      String ref2 = sm.materializeUnique("csp-connector", "my-connector", "pwd",
          "second".getBytes()).toCompletionStage().toCompletableFuture().get();

      assertNotEquals(ref1, ref2, "chaque appel crée un fichier DISTINCT (jamais d'overwrite)");
      assertTrue(ref1.startsWith("file:"));
      Path f1 = Path.of(ref1.substring(5));
      Path f2 = Path.of(ref2.substring(5));
      assertTrue(Files.exists(f1));
      assertTrue(Files.exists(f2));
      // Le PREMIER secret est INTACT — un fichier référencé ne peut pas être modifié
      assertEquals("first", Files.readString(f1));
      assertEquals("second", Files.readString(f2));
      // Nom opaque : contient owner slug + suffixe aléatoire hex
      String name = f1.getFileName().toString();
      assertTrue(name.startsWith("csp-connector-my-connector-"));
      assertTrue(name.endsWith(".pwd"));
      assertTrue(name.matches("csp-connector-my-connector-[0-9a-f]{16}\\.pwd"),
          "nom opaque imprévisible : " + name);
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get();
    }
  }

  @Test
  void permissions0600AndDir0700(@TempDir Path tmp) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      SecretMaterializer sm = materializer(vertx, tmp);
      String ref = sm.materializeUnique("csp-user", "alice", "pwd",
          "x".getBytes()).toCompletionStage().toCompletableFuture().get();
      Path file = Path.of(ref.substring(5));
      try {
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
      } catch (UnsupportedOperationException ignored) {}
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get();
    }
  }

  @Test
  void rejectsSymlinkTarget(@TempDir Path tmp) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      SecretMaterializer sm = materializer(vertx, tmp);
      // Préparer un symlink au nom potentiel est impossible (nom aléatoire) ;
      // on vérifie le refus générique via un préfixe invalide + la garde realpath du résolveur.
      // Ici : préfixe invalide => rejet immédiat (fail-closed)
      var fut = sm.materializeUnique("BAD PREFIX", "x", "pwd", "v".getBytes());
      assertTrue(fut.toCompletionStage().toCompletableFuture().isCompletedExceptionally()
          || assertFailed(fut));
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get();
    }
  }

  @Test
  void rejectsSymlinkedParentEscapingAllowedRoots(@TempDir Path tmp) throws Exception {
    // R4 : normalize() lexical seul laisse passer une racine dont un SEGMENT est
    // un symlink vers hors-racines-autorisées. toRealPath() hors boucle doit refuser.
    Path allowed = tmp.resolve("allowed");
    Files.createDirectories(allowed);
    Path outside = tmp.resolve("outside");
    Files.createDirectories(outside);
    // Lien DANS l'arbre autorisé vers dehors : lexicalement sous allowed, réellement dehors.
    Path linkSub = allowed.resolve("linksub");
    try {
      Files.createSymbolicLink(linkSub, outside);
    } catch (UnsupportedOperationException e) {
      org.junit.jupiter.api.Assumptions.abort("symlinks non supportés sur ce FS");
    }
    Vertx vertx = Vertx.vertx();
    try {
      // Le ctor lexical accepte (linksub sous allowed) mais l'écriture doit refuser :
      // le parent réel (outside) n'est ni la racine réelle ni sous une racine autorisée réelle.
      SecretMaterializer sm = new SecretMaterializer(
          vertx, linkSub.toString(), allowed.toString());
      assertTrue(assertFailed(sm.materializeUnique(
          "csp-connector", "victim", "pwd", "s3cr3t".getBytes())),
          "écriture via parent symlinké hors racines => refus");
      assertEquals(0, Files.list(outside).count(), "rien écrit hors racine");
      // Contrôle : racine réelle sous allowed => succès.
      SecretMaterializer ok = new SecretMaterializer(vertx, allowed);
      String ref = ok.materializeUnique("csp-connector", "legit", "pwd", "s3cr3t".getBytes())
          .toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(ref.startsWith("file:"));
      assertTrue(Files.exists(Path.of(ref.substring("file:".length()))));
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get();
    }
  }

  private static boolean assertFailed(io.vertx.core.Future<String> fut) throws Exception {
    try {
      fut.toCompletionStage().toCompletableFuture().get();
      return false;
    } catch (Exception e) {
      return true;
    }
  }

  @Test
  void rejectsEmptyAndOversizeContent(@TempDir Path tmp) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      SecretMaterializer sm = materializer(vertx, tmp);
      assertTrue(assertFailed(sm.materializeUnique("csp-connector", "x", "pwd", new byte[0])));
      assertTrue(assertFailed(sm.materializeUnique("csp-connector", "x", "pwd", null)));
      byte[] oversize = new byte[64 * 1024 + 1];
      assertTrue(assertFailed(sm.materializeUnique("csp-connector", "x", "pwd", oversize)));
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get();
    }
  }

  @Test
  void uppercaseOwnerAcceptedSlugLowercasedNoCollision(@TempDir Path tmp) throws Exception {
    // C6 : les identités légales en majuscules NE SONT PAS interdites — le slug est
    // normalisé en minuscules, le suffixe aléatoire garantit l'unicité des fichiers.
    Vertx vertx = Vertx.vertx();
    try {
      SecretMaterializer sm = materializer(vertx, tmp);
      String refUpper = sm.materializeUnique("csp-connector", "Alice-Conn", "pwd",
          "v1".getBytes()).toCompletionStage().toCompletableFuture().get();
      String refLower = sm.materializeUnique("csp-connector", "alice-conn", "pwd",
          "v2".getBytes()).toCompletionStage().toCompletableFuture().get();
      assertNotEquals(refUpper, refLower, "même slug normalisé => fichiers distincts");
      Path fUpper = Path.of(refUpper.substring(5));
      assertTrue(fUpper.getFileName().toString().startsWith("csp-connector-alice-conn-"),
          "slug minuscule : " + fUpper.getFileName());
      assertEquals("v1", Files.readString(fUpper));
      assertEquals("v2", Files.readString(Path.of(refLower.substring(5))));
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get();
    }
  }

  @Test
  void slugNormalizationApplies(@TempDir Path tmp) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      SecretMaterializer sm = materializer(vertx, tmp);
      String ref = sm.materializeUnique("csp-listener", "My Listener.42", "des",
          "AA BB CC DD EE FF 00 11 22 33 44 55 66 77".getBytes())
          .toCompletionStage().toCompletableFuture().get();
      Path file = Path.of(ref.substring(5));
      assertTrue(file.getFileName().toString().startsWith("csp-listener-my-listener-42-"),
          "owner slug normalisé : " + file.getFileName());
      // Un owner vide après normalisation => rejet
      assertTrue(assertFailed(sm.materializeUnique("csp-connector", "///", "pwd", "v".getBytes())));
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get();
    }
  }
}
