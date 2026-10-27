package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.domain.CwReply;
import io.vertx.core.Vertx;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Classe de base pour les sessions entrantes (Newcamd, Radegast, CCcam, CS378x).
 */
public abstract class AbstractSession {

  public static class PreauthLease implements AutoCloseable {
    private final SessionRegistry registry;
    private final String ip;
    private final AtomicBoolean released = new AtomicBoolean(false);

    public PreauthLease(SessionRegistry registry, String ip) {
      this.registry = registry;
      this.ip = ip;
    }

    public void release() {
      if (released.compareAndSet(false, true)) {
        if (registry != null) {
          registry.releasePreauthIp(ip);
        }
      }
    }

    @Override
    public void close() {
      release();
    }
  }

  protected final Logger logger;
  protected final Vertx vertx;
  protected final CacheVerticle cacheVerticle;
  protected final SessionRegistry registry;
  protected final List<Runnable> closeHooks = new CopyOnWriteArrayList<>();

  protected String sessionId;
  protected String username;
  protected String remoteAddress;
  protected boolean authenticated;
  protected volatile boolean alive;
  protected String profileName;
  /** Identifiant du listener d'origine (stats cacheex par pair "L<id>:<user>"). */
  protected long listenerId;
  protected PreauthLease preauthLease;

  protected AbstractSession(Vertx vertx, CacheVerticle cacheVerticle, SessionRegistry registry) {
    this.vertx = vertx;
    this.cacheVerticle = cacheVerticle;
    this.registry = registry;
    this.logger = Logger.getLogger(getClass().getName());
  }

  public String getSessionId() { return sessionId; }
  public void setSessionId(String id) { this.sessionId = id; }
  public String getUsername() { return username; }
  public String getRemoteAddress() { return remoteAddress; }
  public boolean isAuthenticated() { return authenticated; }
  public boolean isAlive() { return alive; }

  public String getProfileName() { return profileName; }
  public void setProfileName(String profileName) { this.profileName = profileName; }

  public long getListenerId() { return listenerId; }
  public void setListenerId(long listenerId) { this.listenerId = listenerId; }

  /** Identifiant de paire cacheex entrante : "L<listenerId>:<userName>". */
  public String cacheExPeerId() {
    return "L" + listenerId + ":" + (username != null ? username : "?");
  }

  public void setPreauthLease(PreauthLease lease) {
    this.preauthLease = lease;
  }

  public void releasePreauthLease() {
    if (preauthLease != null) {
      preauthLease.release();
      preauthLease = null;
    }
  }

  public void addCloseHook(Runnable hook) {
    if (hook != null) closeHooks.add(hook);
  }

  protected void fireCloseHooks() {
    releasePreauthLease();
    for (Runnable hook : closeHooks) {
      try {
        hook.run();
      } catch (Exception ignore) {}
    }
  }

  /** Ferme la session. */
  public abstract void close();

  /**
   * Construit un EcmRequest à partir de la clé ECM et du body.
   */
  protected EcmRequest buildEcmRequest(EcmKey key, byte[] body, long maxWaitMs) {
    return new EcmRequest(key, body, profileName, remoteAddress, null,
        System.currentTimeMillis(), null, maxWaitMs);
  }
}
