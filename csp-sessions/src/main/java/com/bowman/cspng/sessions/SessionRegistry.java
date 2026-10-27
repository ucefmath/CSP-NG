package com.bowman.cspng.sessions;

import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Registre des sessions — L5b : délégué à {@link UserDirectory} (aucun store clair).
 */
public class SessionRegistry {

  private static final Logger LOG = Logger.getLogger(SessionRegistry.class.getName());

  public static final int DEFAULT_MAX_GLOBAL_PREAUTH = 500;

  private final Vertx vertx;
  private final UserDirectory userDirectory;
  private final Map<String, AbstractSession> sessions = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> sessionsByUser = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> sessionsByIp = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> preauthByIp = new ConcurrentHashMap<>();
  private final AtomicInteger globalPreauthCount = new AtomicInteger(0);
  private final Map<String, long[]> userRateWindows = new ConcurrentHashMap<>();
  private final AtomicInteger sessionCounter = new AtomicInteger(0);

  public SessionRegistry(Vertx vertx, UserDirectory userDirectory) {
    this.vertx = vertx;
    this.userDirectory = userDirectory;
  }

  /** Constructeur rétrocompatible pour tests sans PG : InMemory vide. */
  public SessionRegistry(Vertx vertx) {
    this(vertx, new InMemoryUserDirectory());
  }

  public UserDirectory userDirectory() { return userDirectory; }

  /** Réservé tests : ajoute un user via InMemory si applicable. */
  public void addTestUser(UserRecord user) {
    if (userDirectory instanceof InMemoryUserDirectory mem) {
      mem.add(user);
    } else {
      throw new IllegalStateException("addTestUser only supported with InMemoryUserDirectory");
    }
  }

  /** L4 legacy — conservé pour compat tests, mais déconseillé en prod. */
  @Deprecated
  public void addUser(String username, String password, int maxConnections) {
    if (userDirectory instanceof InMemoryUserDirectory mem) {
      String hash = com.bowman.cspng.users.crypto.MD5Crypt.crypt(password, "salt1234");
      UserRecord rec = new UserRecord(username, username, hash, true, false, java.util.Set.of("default"), maxConnections);
      mem.add(rec);
      LOG.fine("Added test user: " + username);
    } else {
      throw new IllegalStateException("addUser not supported with PostgresUserDirectory");
    }
  }

  /** Authentification réactive. */
  public Future<Boolean> authenticate(String username, String password) {
    return userDirectory.authenticate(username, password);
  }

  private static String userKey(String username) {
    return username == null ? null : username.toLowerCase(Locale.ROOT);
  }

  /** Retourne le maxConnections depuis lookupCached (0 I/O). */
  public int getMaxConnections(String username) {
    UserRecord rec = userDirectory.lookupCached(username);
    return rec != null ? rec.maxConnections() : 1;
  }

  public boolean isKnownUser(String username) {
    return userDirectory.lookupCached(username) != null;
  }

  public void register(AbstractSession session) {
    String id = "session-" + sessionCounter.incrementAndGet();
    session.setSessionId(id);
    sessions.put(id, session);
    String user = session.getUsername();
    if (user != null) {
      String k = userKey(user);
      sessionsByUser.compute(k, (kk, cnt) -> {
        if (cnt == null) cnt = new AtomicInteger(0);
        cnt.incrementAndGet();
        return cnt;
      });
    }
    String ip = session.getRemoteAddress();
    if (ip != null && !"0.0.0.0".equals(ip)) {
      sessionsByIp.compute(ip, (kk, cnt) -> {
        if (cnt == null) cnt = new AtomicInteger(0);
        cnt.incrementAndGet();
        return cnt;
      });
    }
    LOG.fine("Registered session " + id + " for user " + user);
  }

  /**
   * Enregistrement atomique avec quota : incrémente uniquement si quotas (user + IP) non atteints.
   * @return true si enregistré, false si quota dépassé
   */
  public boolean tryRegister(AbstractSession session, int maxConnections, int maxPerIp) {
    String user = session.getUsername();
    if (user == null) return false;
    String k = userKey(user);
    String ip = session.getRemoteAddress();
    boolean checkIp = ip != null && !"0.0.0.0".equals(ip) && maxPerIp > 0;

    // 1. Vérification & réservation quota IP atomique
    if (checkIp) {
      AtomicBoolean ipOk = new AtomicBoolean(false);
      sessionsByIp.compute(ip, (kk, cnt) -> {
        if (cnt == null) cnt = new AtomicInteger(0);
        if (cnt.get() >= maxPerIp) {
          ipOk.set(false);
          return cnt;
        }
        cnt.incrementAndGet();
        ipOk.set(true);
        return cnt;
      });
      if (!ipOk.get()) return false;
    }

    // 2. Vérification & réservation quota user atomique
    if (maxConnections > 0) {
      AtomicBoolean userOk = new AtomicBoolean(false);
      sessionsByUser.compute(k, (kk, cnt) -> {
        if (cnt == null) cnt = new AtomicInteger(0);
        if (cnt.get() >= maxConnections) {
          userOk.set(false);
          return cnt;
        }
        cnt.incrementAndGet();
        userOk.set(true);
        return cnt;
      });
      if (!userOk.get()) {
        // Rollback réservation IP
        if (checkIp) {
          sessionsByIp.computeIfPresent(ip, (kk, cnt) -> {
            int nv = cnt.decrementAndGet();
            return nv <= 0 ? null : cnt;
          });
        }
        return false;
      }
    } else {
      sessionsByUser.compute(k, (kk, cnt) -> {
        if (cnt == null) cnt = new AtomicInteger(0);
        cnt.incrementAndGet();
        return cnt;
      });
    }

    // Réservation réussie — enregistrer la session
    String id = "session-" + sessionCounter.incrementAndGet();
    session.setSessionId(id);
    sessions.put(id, session);
    if (!checkIp && ip != null && !"0.0.0.0".equals(ip)) {
      sessionsByIp.compute(ip, (kk, cnt) -> {
        if (cnt == null) cnt = new AtomicInteger(0);
        cnt.incrementAndGet();
        return cnt;
      });
    }
    LOG.fine("Registered session " + id + " for user " + user + " (quota " + maxConnections + ", ipLimit " + maxPerIp + ")");
    return true;
  }

  /**
   * Enregistrement atomique avec quota user.
   */
  public boolean tryRegister(AbstractSession session, int maxConnections) {
    return tryRegister(session, maxConnections, 0);
  }

  /**
   * Tente d'acquérir un slot pre-auth pour une IP avec quota global et par IP.
   */
  public boolean tryAcquirePreauthIp(String ip, int maxPerIp) {
    if (globalPreauthCount.incrementAndGet() > DEFAULT_MAX_GLOBAL_PREAUTH) {
      globalPreauthCount.decrementAndGet();
      LOG.warning("Global preauth capacity exceeded (" + DEFAULT_MAX_GLOBAL_PREAUTH + ")");
      AuthAudit.preauthReject(ip, "global-capacity");
      return false;
    }
    if (ip == null || "0.0.0.0".equals(ip) || maxPerIp <= 0) return true;
    AtomicBoolean ok = new AtomicBoolean(false);
    preauthByIp.compute(ip, (k, cnt) -> {
      if (cnt == null) cnt = new AtomicInteger(0);
      int active = countByIp(ip);
      if (cnt.get() + active >= maxPerIp * 2) { // marge preauth = 2x maxPerIp
        ok.set(false);
        return cnt;
      }
      cnt.incrementAndGet();
      ok.set(true);
      return cnt;
    });
    if (!ok.get()) {
      globalPreauthCount.decrementAndGet();
      AuthAudit.preauthReject(ip, "per-ip-quota");
      return false;
    }
    return true;
  }

  /**
   * Libère un slot pre-auth global et IP.
   */
  public void releasePreauthIp(String ip) {
    globalPreauthCount.updateAndGet(v -> Math.max(0, v - 1));
    if (ip == null || "0.0.0.0".equals(ip)) return;
    preauthByIp.computeIfPresent(ip, (k, cnt) -> {
      int nv = cnt.decrementAndGet();
      return nv <= 0 ? null : cnt;
    });
  }

  public int getGlobalPreauthCount() {
    return globalPreauthCount.get();
  }

  /**
   * Vérifie le rate limit de connexion global pour un utilisateur (ex: max 30 connexions / min).
   */
  public synchronized boolean checkUserRateLimit(String username, int maxPerMinute) {
    if (username == null || maxPerMinute <= 0) return true;
    String k = userKey(username);
    long now = System.currentTimeMillis();
    long[] win = userRateWindows.computeIfAbsent(k, u -> new long[]{ now, 0 });
    if (now - win[0] > 60_000L) {
      win[0] = now;
      win[1] = 1;
      return true;
    }
    if (win[1] >= maxPerMinute) {
      return false;
    }
    win[1]++;
    return true;
  }

  public void unregister(AbstractSession session) {
    String id = session.getSessionId();
    boolean removed = false;
    if (id != null) {
      removed = sessions.remove(id, session);
      if (!removed) return;
    } else {
      return;
    }
    String user = session.getUsername();
    if (user != null) {
      String k = userKey(user);
      sessionsByUser.computeIfPresent(k, (kk, cnt) -> {
        int nv = cnt.decrementAndGet();
        return nv <= 0 ? null : cnt;
      });
    }
    String ip = session.getRemoteAddress();
    if (ip != null) {
      sessionsByIp.computeIfPresent(ip, (kk, cnt) -> {
        int nv = cnt.decrementAndGet();
        return nv <= 0 ? null : cnt;
      });
    }
  }

  public int countByUser(String username) {
    AtomicInteger c = sessionsByUser.get(userKey(username));
    return c != null ? c.get() : 0;
  }

  public int countByIp(String ip) {
    AtomicInteger c = sessionsByIp.get(ip);
    return c != null ? c.get() : 0;
  }

  public int size() { return sessions.size(); }

  /** Snapshot immuable trié par id, avec adresses anonymisées. */
  public java.util.List<SessionSnapshot> snapshotSessions() {
    java.util.List<SessionSnapshot> list = sessions.values().stream()
        .map(this::toSnapshot)
        .sorted(java.util.Comparator.comparing(SessionSnapshot::id))
        .toList();
    return java.util.Collections.unmodifiableList(list);
  }

  /** Counts utiles pour status — copie immuable username→count sans secrets. */
  public SessionCounts snapshotCounts() {
    java.util.Map<String, Integer> copy = new java.util.HashMap<>();
    for (var e : sessionsByUser.entrySet()) {
      copy.put(e.getKey(), e.getValue().get());
    }
    return new SessionCounts(sessions.size(), java.util.Collections.unmodifiableMap(copy));
  }

  public record SessionCounts(int total, java.util.Map<String, Integer> byUserSnapshot) {}

  private SessionSnapshot toSnapshot(AbstractSession s) {
    String id = s.getSessionId() != null ? s.getSessionId() : "unknown";
    String username = s.getUsername();
    String anon = SessionSnapshot.anonymizeAddress(s.getRemoteAddress());
    String protocol = deriveProtocol(s);
    String profile = s.getProfileName();
    boolean auth = s.isAuthenticated();
    boolean alive = s.isAlive();
    return new SessionSnapshot(id, username, anon, protocol, profile, auth, alive);
  }

  private static String deriveProtocol(AbstractSession s) {
    String cn = s.getClass().getSimpleName();
    if ("NewcamdSession".equals(cn)) return "newcamd";
    if ("RadegastSession".equals(cn)) return "radegast";
    if ("ExtNewcamdSession".equals(cn)) return "ext-newcamd";
    if ("CcCamSession".equals(cn)) return "cccam";
    if ("Camd35TcpSession".equals(cn)) return "cs378x";
    return "unknown";
  }
}
