package com.bowman.cspng.worker;

import com.bowman.cspng.codec.camd35.Camd35Crypto;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.users.ProtocolAuthResolver;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authentification worker sans DB : {@link ProtocolAuthResolver.Fake} reconstruit
 * depuis le desired (users + secret refs, jamais de valeurs ni de hash).
 * validUntil = expiry du desired (lease bornee) ; epoch = desiredVersion.
 */
public final class WorkerAuth {

  private WorkerAuth() {}

  public static long ucrcOf(String userName) {
    byte[] b = Camd35Crypto.ucrc(userName);
    return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
  }

  public static ProtocolAuthResolver.Fake build(RelayWorkerContract.DesiredWorkerConfig desired) {
    ProtocolAuthResolver.Fake fake = new ProtocolAuthResolver.Fake();
    if (desired == null) return fake;
    Map<String, String> secretByUserProto = new HashMap<>();
    Map<String, RelayWorkerContract.UserSecretView> secretByView = new HashMap<>();
    for (var s : desired.userSecrets()) {
      secretByUserProto.put(s.userName() + "\0" + s.protocol(), s.secretRef());
      secretByView.put(s.userName() + "\0" + s.protocol(), s);
    }
    for (var u : desired.users()) {
      Set<String> profiles = Set.copyOf(u.profiles());
      Map<String, Set<Integer>> allow = toIntSetMap(u.allowedServices());
      Map<String, Set<Integer>> block = toIntSetMap(u.blockedServices());
      Set<String> conns = Set.copyOf(u.allowedConnectors());
      for (String protocol : List.of("cccam", "cs378x")) {
        String ref = secretByUserProto.get(u.userName() + "\0" + protocol);
        if (ref == null) continue;
        Long ucrc = "cs378x".equals(protocol) ? ucrcOf(u.userName()) : null;
        // Politique cacheex transportée (C1) : les sessions worker l'appliquent via
        // le pont (réception dans le vrai cache, émission via onReply) — le contrat
        // V15 transporte mode/maxHop/filter dans UserSecretView.
        com.bowman.cspng.users.CacheExPolicy policy = com.bowman.cspng.users.CacheExPolicy.OFF;
        RelayWorkerContract.UserSecretView view = secretByView.get(u.userName() + "\0" + protocol);
        if (view != null && view.cacheexMode() != null && view.cacheexMode() != 0) {
          try {
            policy = new com.bowman.cspng.users.CacheExPolicy(view.cacheexMode(),
                view.cacheexMaxHop() != null ? view.cacheexMaxHop() : 10, view.cacheexFilter());
          } catch (IllegalArgumentException e) {
            // Policy invalide => OFF fail-closed (jamais de fail-open cacheex)
          }
        }
        fake.add(protocol, u.userName(), new ProtocolAuthResolver.Fake.Entry(
            u.enabled(), null, ref, 1L, profiles, allow, block, conns, ucrc,
            -1, policy));
      }
    }
    return fake;
  }

  private static Map<String, Set<Integer>> toIntSetMap(Map<String, List<Integer>> in) {
    Map<String, Set<Integer>> out = new HashMap<>();
    if (in != null) in.forEach((k, v) -> out.put(k, Set.copyOf(v)));
    return out;
  }
}
