package com.bowman.cspng.testkit;

import com.bowman.cspng.filters.VersionedJsonLinkStore;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/**
 * Convertisseur offline de liens DCW legacy (Java Serialization) vers JSON v1.
 *
 * <h3>Utilisation</h3>
 * <pre>{@code
 * java -cp ... com.bowman.cspng.testkit.LegacyLinksConverter \
 *   --input links.dat --output links.json [--overwrite]
 * }</pre>
 *
 * <h3>Sécurité</h3>
 * <ul>
 *   <li>Entrée max 1 MiB vérifiée avant {@link ObjectInputStream}</li>
 *   <li>{@link ObjectInputFilter} stream-specific avec allowlist stricte
 *       (String + collections Java nécessaires uniquement)</li>
 *   <li>Les limites depth/references/arrayLength/streamBytes sont vérifiées
 *       <b>avant</b> la décision sur la classe, pour intercepter les graphes
 *       profonds même sur des classes autorisées</li>
 *   <li>Classes non allowlist → REJECTED ; serialClass()==null → UNDECIDED
 *       <b>uniquement</b> après passage réussi des limites</li>
 *   <li>Aucune classe projet/custom autorisée</li>
 *   <li>Validation que l'objet racine est {@code Map<String, Set<String>>}</li>
 *   <li>Réfs validées via {@link VersionedJsonLinkStore#isValidServiceRef(String)}</li>
 *   <li>Aucun contenu sensible dans les logs/erreurs</li>
 *   <li>Code de sortie non-zéro sur rejet</li>
 * </ul>
 *
 * <h3>Format de sortie</h3>
 * JSON v1 via {@link VersionedJsonLinkStore#encode(Set)}.
 */
public final class LegacyLinksConverter {

  private static final int MAX_INPUT_SIZE = 1_048_576;
  private static final long MAX_DEPTH = 10;
  private static final long MAX_REFERENCES = 10_000;
  private static final long MAX_ARRAY_LENGTH = 100_000;
  private static final long MAX_STREAM_BYTES = 1_048_576 + 1024;

  private LegacyLinksConverter() {}

  public static void main(String[] args) {
    try {
      int code = run(args);
      System.exit(code);
    } catch (Exception e) {
      System.err.println("Error: internal");
      System.exit(2);
    }
  }

  static int run(String[] args) throws Exception {
    String inputPath = null;
    String outputPath = null;
    boolean overwrite = false;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--input" -> inputPath = args[++i];
        case "--output" -> outputPath = args[++i];
        case "--overwrite" -> overwrite = true;
        default -> {
          System.err.println("Unknown argument");
          printUsage();
          return 1;
        }
      }
    }

    if (inputPath == null || outputPath == null) {
      printUsage();
      return 1;
    }

    Path input = Path.of(inputPath);
    Path output = Path.of(outputPath);

    if (!Files.exists(input)) {
      System.err.println("Input file not found");
      return 1;
    }

    long size = Files.size(input);
    if (size > MAX_INPUT_SIZE) {
      System.err.println("Input file too large");
      return 1;
    }
    if (size == 0) {
      System.err.println("Input file is empty");
      return 1;
    }

    // Vérifier output : rejeter les symlinks (même brisés), refuser overwrite par défaut
    if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(output)) {
        System.err.println("Output path is a symlink — rejected for safety");
        return 1;
      }
      if (!overwrite) {
        System.err.println("Output file already exists (use --overwrite)");
        return 1;
      }
    }

    // Lire et désérialiser
    Map<String, Set<String>> legacyLinks;
    try (InputStream fis = Files.newInputStream(input);
         ObjectInputStream ois = new ObjectInputStream(fis)) {

      ois.setObjectInputFilter(createFilter());
      Object obj = ois.readObject();

      if (!(obj instanceof Map)) {
        System.err.println("Invalid root object: expected Map");
        return 1;
      }

      @SuppressWarnings("unchecked")
      Map<?, ?> raw = (Map<?, ?>) obj;
      legacyLinks = new LinkedHashMap<>();

      for (Map.Entry<?, ?> entry : raw.entrySet()) {
        if (!(entry.getKey() instanceof String)) {
          System.err.println("Invalid map key type");
          return 1;
        }
        if (!(entry.getValue() instanceof Set)) {
          System.err.println("Invalid map value type");
          return 1;
        }

        String key = (String) entry.getKey();
        @SuppressWarnings("unchecked")
        Set<?> rawSet = (Set<?>) entry.getValue();
        Set<String> values = new LinkedHashSet<>();

        for (Object v : rawSet) {
          if (!(v instanceof String)) {
            System.err.println("Invalid set element type");
            return 1;
          }
          values.add((String) v);
        }
        legacyLinks.put(key, values);
      }
    } catch (InvalidClassException | ClassCastException e) {
      System.err.println("Invalid serialized data");
      return 1;
    } catch (IOException e) {
      System.err.println("I/O error reading input");
      return 1;
    }

    // Convertir en groupes de liens
    Set<Set<String>> groups = convertToGroups(legacyLinks);
    if (groups.isEmpty()) {
      System.err.println("No valid link groups found");
      return 1;
    }

    // Valider que chaque ref respecte le format du store
    for (Set<String> g : groups) {
      for (String ref : g) {
        if (!VersionedJsonLinkStore.isValidServiceRef(ref)) {
          System.err.println("Invalid service ref: " + ref);
          return 1;
        }
      }
    }

    // Écrire le JSON v1
    String json = VersionedJsonLinkStore.encode(groups);
    try {
      // output.getParent() peut être null (fichier dans le répertoire courant)
      Path parent = output.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(output, json);
    } catch (IOException e) {
      System.err.println("I/O error writing output");
      return 1;
    }

    System.out.println("Converted " + groups.size() + " link groups");
    return 0;
  }

  /**
   * Convertit le format legacy {@code Map<String, Set<String>>} en groupes
   * de liens {@code Set<Set<String>>} par union transitive.
   */
  static Set<Set<String>> convertToGroups(Map<String, Set<String>> legacy) {
    List<Set<String>> groups = new ArrayList<>();

    for (Map.Entry<String, Set<String>> e : legacy.entrySet()) {
      String key = e.getKey();
      Set<String> values = new LinkedHashSet<>(e.getValue());
      values.add(key);

      // Réutiliser la validation partagée du store
      values.removeIf(ref -> !VersionedJsonLinkStore.isValidServiceRef(ref));
      if (values.size() < 2) continue;

      groups.add(new LinkedHashSet<>(values));
    }

    // Fusion transitive
    boolean merged;
    do {
      merged = false;
      for (int i = 0; i < groups.size(); i++) {
        for (int j = i + 1; j < groups.size(); j++) {
          Set<String> g1 = groups.get(i);
          Set<String> g2 = groups.get(j);
          Set<String> intersection = new LinkedHashSet<>(g1);
          intersection.retainAll(g2);
          if (!intersection.isEmpty()) {
            g1.addAll(g2);
            groups.remove(j);
            merged = true;
            break;
          }
        }
        if (merged) break;
      }
    } while (merged);

    return Set.copyOf(groups);
  }

  // ==================== ObjectInputFilter ====================

  /**
   * Filtre de désérialisation stricte.
   *
   * <p>Ordre d'évaluation critique (cf. Oracle {@code ObjectInputFilter}) :
   * les limites depth/references/arrayLength/streamBytes sont vérifiées
   * <b>avant</b> toute décision basée sur la classe. Si
   * {@code serialClass()==null} (ex. back-reference), on retourne
   * {@code UNDECIDED} <b>seulement après</b> validation réussie des
   * limites — jamais avant. Toute classe non explicitement allowlistée
   * est {@code REJECTED}.
   */
  private static ObjectInputFilter createFilter() {
    Set<String> allowed = Set.of(
        "java.lang.String",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Number",
        "java.util.HashMap",
        "java.util.LinkedHashMap",
        "java.util.HashSet",
        "java.util.LinkedHashSet",
        "java.util.Collections$UnmodifiableSet",
        "java.util.Collections$UnmodifiableMap",
        "java.util.Collections$UnmodifiableCollection",
        "java.util.Collections$UnmodifiableRandomAccessList",
        "java.util.Map$Entry",
        "java.util.HashMap$Node",
        "java.util.LinkedHashMap$Entry",
        "java.util.AbstractMap$SimpleEntry",
        "java.util.AbstractMap$SimpleImmutableEntry",
        "[Ljava.lang.String;",
        "[Ljava.lang.Object;",
        "[Ljava.util.HashMap$Node;",
        "[Ljava.util.Map$Entry;",
        "[Ljava.util.LinkedHashMap$Entry;"
    );

    return filterInfo -> {
      // 1. limites de sécurité — vérifiées AVANT toute décision de classe
      if (filterInfo.depth() > MAX_DEPTH) return ObjectInputFilter.Status.REJECTED;
      if (filterInfo.references() > MAX_REFERENCES) return ObjectInputFilter.Status.REJECTED;
      if (filterInfo.arrayLength() > MAX_ARRAY_LENGTH) return ObjectInputFilter.Status.REJECTED;
      if (filterInfo.streamBytes() > MAX_STREAM_BYTES) return ObjectInputFilter.Status.REJECTED;

      // 2. back-reference (TC_REFERENCE, etc.) : class null, UNDECIDED
      //    uniquement après validation réussie des limites ci-dessus
      if (filterInfo.serialClass() == null) return ObjectInputFilter.Status.UNDECIDED;

      // 3. décision sur la classe
      String name = filterInfo.serialClass().getName();
      if (allowed.contains(name)) return ObjectInputFilter.Status.ALLOWED;

      // Classes tableau des types autorisés
      if (name.startsWith("[L") && name.endsWith(";")) {
        String component = name.substring(2, name.length() - 1);
        if (allowed.contains(component)) return ObjectInputFilter.Status.ALLOWED;
      }

      // Toute classe non allowlistée → REJECTED
      return ObjectInputFilter.Status.REJECTED;
    };
  }

  // ==================== usage / messages ====================

  private static void printUsage() {
    System.err.println("Usage: LegacyLinksConverter --input <path> --output <path> [--overwrite]");
    System.err.println("  --input      Path to legacy Java serialization file (links.dat)");
    System.err.println("  --output     Path to output JSON v1 file (no symlinks allowed)");
    System.err.println("  --overwrite  Allow overwriting existing output file");
  }
}
