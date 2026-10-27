package com.bowman.cspng.filters;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.CopyOptions;
import io.vertx.core.file.FileProps;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Implémentation Vert.x du {@link LinkStore} en JSON versionné.
 *
 * <h3>Format strict</h3>
 * <pre>{@code
 * {
 *   "version": 1,
 *   "links": [
 *     ["1a:dvbt", "2b:dvbt"],
 *     ["3c:sat"]
 *   ]
 * }
 * }</pre>
 *
 * <p>Groupes ordonnés de façon déterministe. Version exactement {@code 1}
 * (entier, pas 1.0). Tout champ top-level inconnu rejeté. Aucun
 * polymorphic typing. La structure plate (version + links) rend un
 * contrôle de profondeur d'imbrication superflu — le champ statique
 * {@code MAX_NESTING_DEPTH} a été supprimé car la validation
 * structurelle stricte suffit.
 *
 * <h3>Taille maximale</h3>
 * Bornée, vérifiée via {@code stat()} avant {@code readFile()}.
 * Défaut : 1 MiB.
 *
 * <h3>Atomicité</h3>
 * Sauvegarde via fichier temporaire unique (UUID) dans le même
 * répertoire, flush, close, puis {@code move} avec
 * {@code CopyOptions.setAtomicMove(true)}. Fallback non-atomique
 * documenté si l'OS ne supporte pas l'atomic move.
 *
 * <h3>Quarantaine</h3>
 * Fichier invalide déplacé vers {@code .corrupt-<uuid>} (suffixe
 * unique par opération). Retour fail-safe : ensemble vide.
 * Messages publics : codes génériques bornés, jamais de chemin
 * absolu ni d'exception brute.
 */
public class VersionedJsonLinkStore implements LinkStore {

  private static final Logger LOG = Logger.getLogger(VersionedJsonLinkStore.class.getName());

  static final int DEFAULT_MAX_FILE_SIZE = 1_048_576; // 1 MiB
  static final int MAX_GROUPS = 10_000;
  static final int MAX_MEMBERS_PER_GROUP = 500;
  static final int MAX_REF_LENGTH = 128;

  /** sidHex:profile — hex 1-4 digits, colon, profile 1-64 chars alphanum + [-_.] */
  private static final Pattern REF_PATTERN =
      Pattern.compile("[0-9a-f]{1,4}:[a-zA-Z0-9][a-zA-Z0-9_.\\-]{0,63}");

  // --- messages publics bornés (jamais de chemin/exception brute) ---

  private static final String MSG_IO_ERROR = "IO_ERROR";
  private static final String MSG_OVERSIZE = "OVERSIZE";
  private static final String MSG_INVALID_JSON = "INVALID_JSON";
  private static final String MSG_INVALID_FORMAT = "INVALID_FORMAT";
  private static final String MSG_WRITE_FAILED = "WRITE_FAILED";

  // ==================== FileOps SPI (package-private, injectable pour tests) ====================

  /**
   * Abstraction package-private des opérations fichiers nécessaires au store.
   * Permet l'injection pour les tests sans exposer d'API publique inutile.
   */
  interface FileOps {
    Future<Boolean> exists(String path);
    Future<FileProps> props(String path);
    Future<Buffer> readFile(String path);
    Future<Void> mkdirs(String path);
    Future<AsyncFile> open(String path, OpenOptions options);
    Future<Void> move(String from, String to, CopyOptions options);
    Future<Void> delete(String path);
  }

  /** Implémentation réelle via Vert.x FileSystem. */
  private static final class VertxFileOps implements FileOps {
    private final FileSystem fs;
    VertxFileOps(FileSystem fs) { this.fs = fs; }
    @Override public Future<Boolean> exists(String path) { return fs.exists(path); }
    @Override public Future<FileProps> props(String path) { return fs.props(path); }
    @Override public Future<Buffer> readFile(String path) { return fs.readFile(path); }
    @Override public Future<Void> mkdirs(String path) { return fs.mkdirs(path); }
    @Override public Future<AsyncFile> open(String path, OpenOptions opts) { return fs.open(path, opts); }
    @Override public Future<Void> move(String from, String to, CopyOptions opts) { return fs.move(from, to, opts); }
    @Override public Future<Void> delete(String path) { return fs.delete(path); }
  }

  private final FileOps fops;
  private final String filePath;
  private final int maxFileSize;

  // --- constructeurs publics ---

  public VersionedJsonLinkStore(Vertx vertx, String filePath) {
    this(vertx, filePath, DEFAULT_MAX_FILE_SIZE);
  }

  public VersionedJsonLinkStore(Vertx vertx, String filePath, int maxFileSize) {
    Objects.requireNonNull(vertx, "vertx");
    this.filePath = Objects.requireNonNull(filePath, "filePath");
    if (maxFileSize <= 0) throw new IllegalArgumentException("maxFileSize must be > 0");
    this.maxFileSize = maxFileSize;
    this.fops = new VertxFileOps(vertx.fileSystem());
  }

  /** Constructeur package-private pour tests avec FileOps injecté. */
  VersionedJsonLinkStore(FileOps fops, String filePath, int maxFileSize) {
    this.fops = Objects.requireNonNull(fops, "fops");
    this.filePath = Objects.requireNonNull(filePath, "filePath");
    if (maxFileSize <= 0) throw new IllegalArgumentException("maxFileSize must be > 0");
    this.maxFileSize = maxFileSize;
  }

  // ==================== load ====================

  @Override
  public Future<Set<Set<String>>> load() {
    Promise<Set<Set<String>>> promise = Promise.promise();

    fops.exists(filePath).onComplete(ar -> {
      if (ar.failed() || !ar.result()) {
        promise.complete(Collections.emptySet());
        return;
      }
      fops.props(filePath).onComplete(propsAr -> {
        if (propsAr.failed()) {
          LOG.warning(() -> "Cannot stat link file: " + propsAr.cause());
          promise.complete(Collections.emptySet());
          return;
        }
        long size = propsAr.result().size();
        if (size > maxFileSize) {
          LOG.warning(() -> "Link file exceeds max size (" + size + " > " + maxFileSize
              + "), quarantining");
          quarantine().onComplete(qr -> promise.complete(Collections.emptySet()));
          return;
        }
        if (size == 0) {
          promise.complete(Collections.emptySet());
          return;
        }
        fops.readFile(filePath).onComplete(readAr -> {
          if (readAr.failed()) {
            LOG.warning(() -> "Cannot read link file: " + readAr.cause());
            promise.complete(Collections.emptySet());
            return;
          }
          try {
            Set<Set<String>> groups = parseAndValidate(readAr.result());
            promise.complete(groups);
          } catch (Exception e) {
            LOG.warning(() -> "Invalid link file, quarantining: " + e.getMessage());
            quarantine().onComplete(qr -> promise.complete(Collections.emptySet()));
          }
        });
      });
    });

    return promise.future();
  }

  // ==================== save ====================

  @Override
  public Future<Void> save(Set<Set<String>> groups) {
    Objects.requireNonNull(groups, "groups must not be null");
    Promise<Void> promise = Promise.promise();

    String json = encode(groups);
    Buffer buffer = Buffer.buffer(json, "UTF-8");

    if (buffer.length() > maxFileSize) {
      promise.fail(MSG_OVERSIZE);
      return promise.future();
    }

    String uniqueId = UUID.randomUUID().toString();
    String tmpPath = filePath + ".tmp-" + uniqueId;
    String parent = parentPath(filePath);

    Future<Void> mkdirsFuture;
    if (parent != null && !parent.isEmpty()) {
      mkdirsFuture = fops.mkdirs(parent);
    } else {
      mkdirsFuture = Future.succeededFuture();
    }

    mkdirsFuture.onComplete(mkdirsAr -> {
      if (mkdirsAr.failed()) {
        promise.fail(MSG_IO_ERROR);
        return;
      }

      OpenOptions openOpts = new OpenOptions()
          .setCreate(true)
          .setTruncateExisting(true)
          .setWrite(true)
          .setPerms("rw-------");

      fops.open(tmpPath, openOpts).onComplete(openAr -> {
        if (openAr.failed()) {
          promise.fail(MSG_IO_ERROR);
          return;
        }
        AsyncFile asyncFile = openAr.result();

        asyncFile.write(buffer, 0).onComplete(writeAr -> {
          if (writeAr.failed()) {
            asyncFile.close().onComplete(ignored ->
                fops.delete(tmpPath).onComplete(ignored2 ->
                    promise.fail(MSG_WRITE_FAILED)));
            return;
          }
          asyncFile.flush().onComplete(flushAr -> {
            if (flushAr.failed()) {
              asyncFile.close().onComplete(ignored ->
                  fops.delete(tmpPath).onComplete(ignored2 ->
                      promise.fail(MSG_WRITE_FAILED)));
              return;
            }
            asyncFile.close().onComplete(closeAr -> {
              if (closeAr.failed()) {
                fops.delete(tmpPath).onComplete(ignored ->
                    promise.fail(MSG_WRITE_FAILED));
                return;
              }

              CopyOptions copyOpts = new CopyOptions()
                  .setReplaceExisting(true)
                  .setAtomicMove(true);

              fops.move(tmpPath, filePath, copyOpts).onComplete(moveAr -> {
                if (moveAr.failed()) {
                  LOG.warning("Atomic move not supported, using non-atomic replace fallback");
                  CopyOptions fallbackOpts = new CopyOptions()
                      .setReplaceExisting(true);
                  fops.move(tmpPath, filePath, fallbackOpts).onComplete(fbAr -> {
                    if (fbAr.failed()) {
                      fops.delete(tmpPath).onComplete(ignored ->
                          promise.fail(MSG_WRITE_FAILED));
                    } else {
                      promise.complete();
                    }
                  });
                } else {
                  promise.complete();
                }
              });
            });
          });
        });
      });
    });

    return promise.future();
  }

  // ==================== parse / validate ====================

  /**
   * Parse et valide un buffer JSON. Visible pour les tests et outils offline.
   *
   * @throws IllegalArgumentException si le format est invalide
   */
  public Set<Set<String>> parseAndValidate(Buffer buffer) {
    String raw = buffer.toString("UTF-8");
    JsonObject root;
    try {
      root = new JsonObject(raw);
    } catch (Exception e) {
      throw new IllegalArgumentException(MSG_INVALID_JSON);
    }

    // version — doit être l'entier exact 1 (pas 1.0, 1.5, ni Long hors valeur)
    if (!root.containsKey("version")) {
      throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": missing version");
    }
    Object versionObj = root.getValue("version");
    if (!(versionObj instanceof Integer)) {
      throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": version must be integer 1");
    }
    int version = (Integer) versionObj;
    if (version != 1) {
      throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": unsupported version " + version);
    }

    // links
    if (!root.containsKey("links")) {
      throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": missing links");
    }
    JsonArray links;
    try {
      links = root.getJsonArray("links");
    } catch (ClassCastException e) {
      throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": links not array");
    }
    if (links == null) {
      throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": links not array");
    }

    // unknown top-level fields rejected
    for (String field : root.fieldNames()) {
      if (!"version".equals(field) && !"links".equals(field)) {
        throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": unknown field " + field);
      }
    }

    if (links.size() > MAX_GROUPS) {
      throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": too many groups");
    }

    Set<Set<String>> groups = new LinkedHashSet<>();
    for (int i = 0; i < links.size(); i++) {
      JsonArray groupArr;
      try {
        groupArr = links.getJsonArray(i);
      } catch (ClassCastException e) {
        throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": group not array");
      }
      if (groupArr == null) {
        throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": group not array");
      }
      if (groupArr.size() < 2) {
        throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": group < 2 members");
      }
      if (groupArr.size() > MAX_MEMBERS_PER_GROUP) {
        throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": group too large");
      }

      Set<String> group = new LinkedHashSet<>();
      for (int j = 0; j < groupArr.size(); j++) {
        String ref = groupArr.getString(j);
        if (ref == null || ref.isBlank()) {
          throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": null ref");
        }
        if (ref.length() > MAX_REF_LENGTH) {
          throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": ref too long");
        }
        if (!isValidServiceRef(ref)) {
          throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": invalid ref format");
        }
        if (!group.add(ref)) {
          throw new IllegalArgumentException(MSG_INVALID_FORMAT + ": duplicate ref");
        }
      }
      groups.add(Collections.unmodifiableSet(group));
    }

    return Collections.unmodifiableSet(groups);
  }

  // ==================== encode ====================

  public static String encode(Set<Set<String>> groups) {
    JsonObject root = new JsonObject();
    root.put("version", 1);

    List<Set<String>> sortedGroups = new ArrayList<>();
    for (Set<String> group : groups) {
      if (group.size() < 2) continue;
      List<String> sorted = new ArrayList<>(group);
      Collections.sort(sorted);
      sortedGroups.add(new LinkedHashSet<>(sorted));
    }
    sortedGroups.sort((a, b) -> {
      String firstA = a.iterator().next();
      String firstB = b.iterator().next();
      return firstA.compareTo(firstB);
    });

    JsonArray links = new JsonArray();
    for (Set<String> group : sortedGroups) {
      JsonArray groupArr = new JsonArray();
      for (String ref : group) {
        groupArr.add(ref);
      }
      links.add(groupArr);
    }
    root.put("links", links);

    return root.encodePrettily();
  }

  // ==================== ref validation publique partagée ====================

  /**
   * Valide une ref de service au format {@code sidHex:profile}.
   * Exposée publiquement pour réutilisation par le converter et le filtre.
   *
   * @param ref la référence à valider (ex. {@code "1a:dvbt"})
   * @return true si le format est valide
   */
  public static boolean isValidServiceRef(String ref) {
    if (ref == null || ref.isBlank()) return false;
    if (ref.length() > MAX_REF_LENGTH) return false;
    return REF_PATTERN.matcher(ref).matches();
  }

  // ==================== quarantine ====================

  private Future<Void> quarantine() {
    String uniqueId = UUID.randomUUID().toString();
    String quarantinePath = filePath + ".corrupt-" + uniqueId;
    return fops.exists(filePath).compose(exists -> {
      if (!exists) return Future.succeededFuture();
      return fops.move(filePath, quarantinePath, new CopyOptions()).onComplete(ar -> {
        if (ar.succeeded()) {
          LOG.info(() -> "Quarantined corrupt link file as ..." + quarantinePath.substring(Math.max(0, quarantinePath.length() - 60)));
        } else {
          LOG.warning(() -> "Failed to quarantine corrupt file: " + ar.cause());
        }
      }).otherwiseEmpty();
    });
  }

  // ==================== utilities ====================

  private static String parentPath(String path) {
    int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    if (idx > 0) return path.substring(0, idx);
    return null;
  }

  /** Visible pour tests. */
  String filePath() { return filePath; }
  /** Visible pour tests. */
  int maxFileSize() { return maxFileSize; }
  /** Visible pour tests. */
  FileOps fileOps() { return fops; }
}
