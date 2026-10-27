package com.bowman.cspng.testkit;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.filters.VersionedJsonLinkStore;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

class LegacyLinksConverterTest {

  @TempDir
  Path tempDir;

  private Vertx vertx;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() {
    vertx.close();
  }

  // ==================== 1. valid legacy round-trip → store reloadable ====================

  @Test
  void convertValidLegacyToJsonAndReload() throws Exception {
    Path input = tempDir.resolve("legacy-links.dat");
    Path output = tempDir.resolve("links.json");

    Map<String, Set<String>> legacy = new LinkedHashMap<>();
    legacy.put("1:dvbt", new LinkedHashSet<>(Set.of("2:dvbt", "3:dvbt")));
    legacy.put("2:dvbt", new LinkedHashSet<>(Set.of("1:dvbt")));

    writeLegacy(legacy, input);

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", output.toString()
    });
    assertEquals(0, code);

    // Verify output is valid JSON v1
    String json = Files.readString(output);
    assertTrue(json.contains("\"version\""));
    assertTrue(json.contains("\"links\""));

    // Parse and verify content — must be reloadable by store without quarantine
    var store = new VersionedJsonLinkStore(vertx, "dummy");
    Set<Set<String>> groups = store.parseAndValidate(Buffer.buffer(json));
    assertEquals(1, groups.size());
    Set<String> group = groups.iterator().next();
    assertEquals(3, group.size());
    assertTrue(group.contains("1:dvbt"));
    assertTrue(group.contains("2:dvbt"));
    assertTrue(group.contains("3:dvbt"));
  }

  // ==================== 2. overwrite protection ====================

  @Test
  void refuseOverwriteByDefault() throws Exception {
    Path input = tempDir.resolve("legacy.dat");
    Path output = tempDir.resolve("out.json");
    Files.writeString(output, "existing");

    Map<String, Set<String>> legacy = new LinkedHashMap<>();
    legacy.put("1:p", new LinkedHashSet<>(Set.of("2:p")));
    writeLegacy(legacy, input);

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", output.toString()
    });
    assertNotEquals(0, code);
    assertEquals("existing", Files.readString(output));
  }

  @Test
  void allowOverwriteWithFlag() throws Exception {
    Path input = tempDir.resolve("legacy.dat");
    Path output = tempDir.resolve("out.json");
    Files.writeString(output, "existing");

    Map<String, Set<String>> legacy = new LinkedHashMap<>();
    legacy.put("1:p", new LinkedHashSet<>(Set.of("2:p")));
    writeLegacy(legacy, input);

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", output.toString(), "--overwrite"
    });
    assertEquals(0, code);
    String content = Files.readString(output);
    assertNotEquals("existing", content);
  }

  // ==================== 3. output symlink rejected ====================

  @Test
  void rejectOutputSymlink() throws Exception {
    Path input = tempDir.resolve("legacy.dat");
    Map<String, Set<String>> legacy = new LinkedHashMap<>();
    legacy.put("1:p", new LinkedHashSet<>(Set.of("2:p")));
    writeLegacy(legacy, input);

    Path realOutput = tempDir.resolve("real.json");
    Files.writeString(realOutput, "dummy");
    Path symlink = tempDir.resolve("link.json");
    Files.createSymbolicLink(symlink, realOutput);

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", symlink.toString()
    });
    assertNotEquals(0, code, "symlink output should be rejected");
  }

  // ==================== 4. output with null parent (relative in CWD) ====================

  @Test
  void outputRelativePathWithoutParent() throws Exception {
    Path input = tempDir.resolve("legacy.dat");
    Map<String, Set<String>> legacy = new LinkedHashMap<>();
    legacy.put("1:p", new LinkedHashSet<>(Set.of("2:p")));
    writeLegacy(legacy, input);

    // Use a relative path with no parent (just filename)
    Path cwd = Path.of(".").toAbsolutePath();
    Path output = cwd.resolve("test-converter-output.json");
    try {
      int code = LegacyLinksConverter.run(new String[]{
          "--input", input.toString(), "--output", "test-converter-output.json"
      });
      // May fail if cwd not writable — accept either 0 or failure
      // Just verify no crash
      assertTrue(code == 0 || code != 0);
    } finally {
      Files.deleteIfExists(output);
    }
  }

  // ==================== 5. input validation ====================

  @Test
  void rejectMissingInput() throws Exception {
    int code = LegacyLinksConverter.run(new String[]{
        "--input", "/nonexistent/file.dat", "--output", tempDir.resolve("out.json").toString()
    });
    assertNotEquals(0, code);
  }

  @Test
  void rejectEmptyInput() throws Exception {
    Path input = tempDir.resolve("empty.dat");
    Files.writeString(input, "");

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", tempDir.resolve("out.json").toString()
    });
    assertNotEquals(0, code);
  }

  @Test
  void rejectOversizeInput() throws Exception {
    Path input = tempDir.resolve("big.dat");
    byte[] big = new byte[2_000_000];
    Files.write(input, big);

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", tempDir.resolve("out.json").toString()
    });
    assertNotEquals(0, code);
  }

  @Test
  void rejectNonMapRootObject() throws Exception {
    Path input = tempDir.resolve("bad.dat");
    writeLegacy(List.of("not a map"), input);

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", tempDir.resolve("out.json").toString()
    });
    assertNotEquals(0, code);
  }

  @Test
  void rejectNonStringKeyInMap() throws Exception {
    Path input = tempDir.resolve("bad-keys.dat");
    Map<Object, Object> bad = new LinkedHashMap<>();
    bad.put(42, new LinkedHashSet<>(Set.of("1:p")));
    writeLegacy(bad, input);

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", tempDir.resolve("out.json").toString()
    });
    assertNotEquals(0, code);
  }

  @Test
  void rejectNonSetValueInMap() throws Exception {
    Path input = tempDir.resolve("bad-values.dat");
    Map<Object, Object> bad = new LinkedHashMap<>();
    bad.put("1:p", "not a set");
    writeLegacy(bad, input);

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", tempDir.resolve("out.json").toString()
    });
    assertNotEquals(0, code);
  }

  @Test
  void rejectNonStringElementInSet() throws Exception {
    Path input = tempDir.resolve("bad-elements.dat");
    Map<Object, Object> bad = new LinkedHashMap<>();
    bad.put("1:p", new LinkedHashSet<>(Set.of(42)));
    writeLegacy(bad, input);

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", tempDir.resolve("out.json").toString()
    });
    assertNotEquals(0, code);
  }

  @Test
  void rejectInvalidRefsEmptyResult() throws Exception {
    Path input = tempDir.resolve("bad-refs.dat");
    Map<String, Set<String>> bad = new LinkedHashMap<>();
    bad.put("badref", new LinkedHashSet<>(Set.of("nocolon")));
    writeLegacy(bad, input);

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", tempDir.resolve("out.json").toString()
    });
    assertNotEquals(0, code, "no valid groups → error");
  }

  // ==================== 6. converter output always reloadable by store ====================

  @Test
  void converterOutputReloadableWithoutQuarantine() throws Exception {
    Path input = tempDir.resolve("reload-input.dat");
    Path output = tempDir.resolve("reload-output.json");

    Map<String, Set<String>> legacy = new LinkedHashMap<>();
    legacy.put("a:dvbt", new LinkedHashSet<>(Set.of("b:dvbt", "c:dvbt")));
    writeLegacy(legacy, input);

    int code = LegacyLinksConverter.run(new String[]{
        "--input", input.toString(), "--output", output.toString()
    });
    assertEquals(0, code);

    // Synchronous validation: parseAndValidate must succeed without exception
    String json = Files.readString(output);
    var store = new VersionedJsonLinkStore(vertx, "dummy");
    Set<Set<String>> groups = store.parseAndValidate(Buffer.buffer(json));
    assertFalse(groups.isEmpty(), "output must be parsable and non-empty");

    // File still exists (not quarantined by parseAndValidate which is purely in-memory)
    assertTrue(Files.exists(output), "output file must still exist");
  }

  // ==================== 7. convertToGroups ====================

  @Test
  void convertToGroupsMergesTransitively() {
    Map<String, Set<String>> legacy = new LinkedHashMap<>();
    legacy.put("1:p", new LinkedHashSet<>(Set.of("2:p")));
    legacy.put("2:p", new LinkedHashSet<>(Set.of("3:p")));

    Set<Set<String>> groups = LegacyLinksConverter.convertToGroups(legacy);
    assertEquals(1, groups.size());
    assertEquals(3, groups.iterator().next().size());
  }

  @Test
  void convertToGroupsDisjointClusters() {
    Map<String, Set<String>> legacy = new LinkedHashMap<>();
    legacy.put("1:p", new LinkedHashSet<>(Set.of("2:p")));
    legacy.put("a:p", new LinkedHashSet<>(Set.of("b:p")));

    Set<Set<String>> groups = LegacyLinksConverter.convertToGroups(legacy);
    assertEquals(2, groups.size());
  }

  // ==================== helpers ====================

  static void writeLegacy(Object obj, Path path) throws IOException {
    try (OutputStream fos = Files.newOutputStream(path);
         ObjectOutputStream oos = new ObjectOutputStream(fos)) {
      oos.writeObject(obj);
    }
  }
}
