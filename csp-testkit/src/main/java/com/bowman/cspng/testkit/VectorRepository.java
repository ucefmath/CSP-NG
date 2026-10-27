package com.bowman.cspng.testkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Charge et expose les vecteurs golden binaires du fichier
 * {@code vectors/cluster-udp.json}.
 *
 * <p>Les vecteurs sont produits par exécution du code legacy Java 17
 * (cf. {@code src/main/resources/legacy-tool/}) et serviront de référence
 * byte-for-byte pour le codec du lot L1.
 */
public class VectorRepository {

  private final List<Vector> vectors;

  public VectorRepository() {
    this("vectors/cluster-udp.json");
  }

  public VectorRepository(String resourcePath) {
    this.vectors = Collections.unmodifiableList(load(resourcePath));
  }

  public List<Vector> all() {
    return vectors;
  }

  public Optional<Vector> byId(String id) {
    return vectors.stream().filter(v -> v.id().equals(id)).findFirst();
  }

  public long countByType(String type) {
    return vectors.stream().filter(v -> v.type().equals(type)).count();
  }

  private List<Vector> load(String resourcePath) {
    try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IllegalStateException("Vector resource not found: " + resourcePath);
      }
      String json = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
          .lines().reduce("", (a, b) -> a + b + "\n");
      return parse(json);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load vectors", e);
    }
  }

  private List<Vector> parse(String json) {
    List<Vector> result = new ArrayList<>();
    int idx = json.indexOf("\"vectors\"");
    if (idx < 0) return result;
    int start = json.indexOf('[', idx);
    int end = json.indexOf(']', start);
    if (start < 0 || end < 0) return result;
    String array = json.substring(start + 1, end);

    int brace = 0;
    int objStart = -1;
    for (int i = 0; i < array.length(); i++) {
      char c = array.charAt(i);
      if (c == '{') {
        if (brace == 0) objStart = i;
        brace++;
      } else if (c == '}') {
        brace--;
        if (brace == 0) {
          result.add(parseObject(array.substring(objStart, i + 1)));
        }
      }
    }
    return result;
  }

  private Vector parseObject(String obj) {
    String id = extractString(obj, "\"id\"");
    String type = extractString(obj, "\"type\"");
    int length = extractInt(obj, "\"length\"");
    String hex = extractString(obj, "\"hex\"");
    String description = extractString(obj, "\"description\"");
    return new Vector(id, type, length, hex, description);
  }

  private String extractString(String obj, String key) {
    int k = obj.indexOf(key);
    if (k < 0) return "";
    int quote = obj.indexOf('"', k + key.length());
    if (quote < 0) return "";
    int end = obj.indexOf('"', quote + 1);
    if (end < 0) return "";
    return unescape(obj.substring(quote + 1, end));
  }

  private int extractInt(String obj, String key) {
    int k = obj.indexOf(key);
    if (k < 0) return 0;
    int colon = obj.indexOf(':', k);
    int comma = obj.indexOf(',', colon);
    if (comma < 0) comma = obj.indexOf('}', colon);
    String val = obj.substring(colon + 1, comma).trim();
    return Integer.parseInt(val);
  }

  private String unescape(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char next = s.charAt(i + 1);
        switch (next) {
          case '"': sb.append('"'); i++; break;
          case '\\': sb.append('\\'); i++; break;
          case 'n': sb.append('\n'); i++; break;
          case 'r': sb.append('\r'); i++; break;
          case 't': sb.append('\t'); i++; break;
          case 'b': sb.append('\b'); i++; break;
          case 'f': sb.append('\f'); i++; break;
          case 'u':
            if (i + 5 < s.length()) {
              sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
              i += 5;
            }
            break;
          default: sb.append(next); i++;
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  public record Vector(String id, String type, int length, String hex, String description) {

    public byte[] bytes() {
      if (hex == null || hex.isEmpty()) return new byte[0];
      byte[] out = new byte[hex.length() / 2];
      for (int i = 0; i < out.length; i++) {
        out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
      }
      return out;
    }
  }
}
