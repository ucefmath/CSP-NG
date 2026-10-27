package com.bowman.cspng.app.admin;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broker SSE admin single-writer sur Context Vert.x dédié.
 * Aucun synchronized/lock, aucune opération bloquante.
 */
@ApplicationScoped
public class AdminEventBroker {

    public enum AdminEventType {
        STATUS_CHANGED,
        CONFIG_RELOADED,
        CONNECTOR_CHANGED,
        SESSION_CHANGED,
        CACHE_CHANGED,
        DCW_CHANGED,
        SNAPSHOT_REQUIRED,
        HEARTBEAT
    }

    public sealed interface AdminPayload permits
            StatusChangedPayload,
            ConfigReloadedPayload,
            ConnectorChangedPayload,
            SessionChangedPayload,
            CacheChangedPayload,
            DcwChangedPayload,
            SnapshotRequiredPayload,
            HeartbeatPayload {}

    public record StatusChangedPayload(int totalConnectors, int activeConnectors, int sessionCount, boolean degraded) implements AdminPayload {}
    public record ConfigReloadedPayload(String subject) implements AdminPayload {}
    public record ConnectorChangedPayload(String state, int count) implements AdminPayload {}
    public record SessionChangedPayload(int count, String state) implements AdminPayload {}
    public record CacheChangedPayload(long size, String state) implements AdminPayload {}
    public record DcwChangedPayload(String state, int groups) implements AdminPayload {}
    public record SnapshotRequiredPayload(String reason) implements AdminPayload {}
    public record HeartbeatPayload(long seq) implements AdminPayload {}

    public record AdminEvent(long id, Instant timestamp, AdminEventType type, AdminPayload payload) {}

    public record BrokerStats(int replaySize, int subscriberCount, long published, long slowEvictions, long replayGaps, long rejectedSubscribers) {}

    public static class RejectedSubscriberException extends RuntimeException {
        public RejectedSubscriberException(String msg) { super(msg); }
    }

    private static final Set<String> ALLOWED_STATES = Set.of(
            "CONNECTED","DISCONNECTED","ADDED","REMOVED","UPDATED","ENABLED","DISABLED","ACTIVE","IDLE","DEGRADED","OK"
    );
    private static final Set<String> SNAPSHOT_REASONS = Set.of(
            "REPLAY_GAP","INVALID_LAST_EVENT_ID","FUTURE_LAST_EVENT_ID","SLOW_CONSUMER"
    );

    final Vertx vertx;

    @ConfigProperty(name = "csp.sse.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "csp.sse.replay-size", defaultValue = "500")
    int replaySizeCfg;

    @ConfigProperty(name = "csp.sse.subscriber-queue-size", defaultValue = "64")
    int subscriberQueueSizeCfg;

    @ConfigProperty(name = "csp.sse.max-subscribers", defaultValue = "50")
    int maxSubscribersCfg;

    @ConfigProperty(name = "csp.sse.heartbeat-ms", defaultValue = "15000")
    long heartbeatMsCfg;

    // runtime effective (validated)
    int replaySize = 500;
    int subscriberQueueSize = 64;
    int maxSubscribers = 50;
    long heartbeatMs = 15000;

    Context brokerContext;
    final String instanceId;
    final AtomicLong nextId = new AtomicLong(0);
    final ArrayDeque<AdminEvent> replayBuffer = new ArrayDeque<>();
    final List<Subscriber> subscribers = new ArrayList<>();
    long heartbeatTimerId = -1;
    long published = 0;
    long slowEvictions = 0;
    long replayGaps = 0;
    long rejectedSubscribers = 0;

    static class Subscriber {
        final Flow.Subscriber<? super AdminEvent> downstream;
        final BrokerSubscription subscription;
        final ArrayDeque<AdminEvent> queue;
        boolean terminated;
        boolean draining;
        Subscriber(Flow.Subscriber<? super AdminEvent> downstream, BrokerSubscription subscription, int queueSize) {
            this.downstream = downstream;
            this.subscription = subscription;
            this.queue = new ArrayDeque<>(queueSize);
        }
    }

    static class TerminalHolder {
        final Flow.Subscriber<? super AdminEvent> downstream;
        final BrokerSubscription subscription;
        final ArrayDeque<AdminEvent> queue;
        boolean terminated;
        boolean draining;
        boolean completed;
        TerminalHolder(Flow.Subscriber<? super AdminEvent> downstream, BrokerSubscription subscription, AdminEvent snap) {
            this.downstream = downstream;
            this.subscription = subscription;
            this.queue = new ArrayDeque<>(1);
            this.queue.add(snap);
        }
        void clear() { queue.clear(); terminated = true; }
    }

    static class BrokerSubscription implements Flow.Subscription {
        final Flow.Subscriber<? super AdminEvent> downstream;
        final AdminEventBroker broker;
        Subscriber holder;
        TerminalHolder terminal;
        long requested = 0;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean terminated = new AtomicBoolean(false);
        BrokerSubscription(Flow.Subscriber<? super AdminEvent> downstream, AdminEventBroker broker) {
            this.downstream = downstream;
            this.broker = broker;
        }
        @Override public void request(long n) {
            if (n <= 0) {
                if (terminated.compareAndSet(false, true)) {
                    cancelled.set(true);
                    // cleanup holder/terminal synchronously before async error
                    Runnable task = () -> {
                        if (holder != null) broker.removeSubscriber(holder);
                        if (terminal != null) terminal.clear();
                        try { downstream.onError(new IllegalArgumentException("non-positive request")); } catch (Exception ignored) {}
                    };
                    if (broker.brokerContext != null) broker.brokerContext.runOnContext(v -> task.run());
                    else task.run();
                }
                return;
            }
            if (cancelled.get() || terminated.get()) return;
            Runnable task = () -> {
                if (cancelled.get() || terminated.get()) return;
                requested = addCap(requested, n);
                if (terminal != null && !terminal.terminated) broker.drainTerminal(terminal);
                else if (holder != null && !holder.terminated) broker.drain(holder);
            };
            if (broker.brokerContext != null) broker.brokerContext.runOnContext(v -> task.run());
            else task.run();
        }
        @Override public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            Runnable task = () -> {
                if (holder != null) broker.removeSubscriber(holder);
                if (terminal != null) terminal.clear();
            };
            if (broker.brokerContext != null) broker.brokerContext.runOnContext(v -> task.run());
            else task.run();
        }
        static long addCap(long a, long b) {
            long r = a + b;
            if (r < 0) return Long.MAX_VALUE;
            return r;
        }
    }

    // CDI proxy no-arg
    public AdminEventBroker() {
        this.vertx = null;
        this.instanceId = UUID.randomUUID().toString();
    }

    // CDI constructor unique
    @jakarta.inject.Inject
    public AdminEventBroker(Vertx vertx,
                            @ConfigProperty(name = "csp.sse.enabled", defaultValue = "false") boolean enabled,
                            @ConfigProperty(name = "csp.sse.replay-size", defaultValue = "500") int replaySizeCfg,
                            @ConfigProperty(name = "csp.sse.subscriber-queue-size", defaultValue = "64") int subscriberQueueSizeCfg,
                            @ConfigProperty(name = "csp.sse.max-subscribers", defaultValue = "50") int maxSubscribersCfg,
                            @ConfigProperty(name = "csp.sse.heartbeat-ms", defaultValue = "15000") long heartbeatMsCfg) {
        this.vertx = vertx;
        this.enabled = enabled;
        this.replaySizeCfg = replaySizeCfg;
        this.subscriberQueueSizeCfg = subscriberQueueSizeCfg;
        this.maxSubscribersCfg = maxSubscribersCfg;
        this.heartbeatMsCfg = heartbeatMsCfg;
        this.instanceId = UUID.randomUUID().toString();
        if (vertx != null) {
            this.brokerContext = vertx.getOrCreateContext();
        }
        validateAndAssign();
    }

    // Test constructor
    public AdminEventBroker(Vertx vertx, String instanceId, int replaySize, int subscriberQueueSize, int maxSubscribers, long heartbeatMs, boolean enabled) {
        this.vertx = vertx;
        this.instanceId = instanceId != null ? instanceId : UUID.randomUUID().toString();
        this.replaySize = replaySize;
        this.subscriberQueueSize = subscriberQueueSize;
        this.maxSubscribers = maxSubscribers;
        this.heartbeatMs = heartbeatMs;
        this.enabled = enabled;
        this.replaySizeCfg = replaySize;
        this.subscriberQueueSizeCfg = subscriberQueueSize;
        this.maxSubscribersCfg = maxSubscribers;
        this.heartbeatMsCfg = heartbeatMs;
        if (vertx != null) {
            this.brokerContext = vertx.getOrCreateContext();
        }
        validateAndAssign();
    }

    @PostConstruct
    void init() {
        if (vertx != null && brokerContext == null) {
            brokerContext = vertx.getOrCreateContext();
        }
        validateAndAssign();
    }

    private void validateAndAssign() {
        int rs = replaySizeCfg;
        if (rs < 10 || rs > 10000) throw new IllegalStateException("csp.sse.replay-size must be in [10..10000] got " + rs);
        int qs = subscriberQueueSizeCfg;
        if (qs < 4 || qs > 1000) throw new IllegalStateException("csp.sse.subscriber-queue-size must be in [4..1000] got " + qs);
        int ms = maxSubscribersCfg;
        if (ms < 1 || ms > 1000) throw new IllegalStateException("csp.sse.max-subscribers must be in [1..1000] got " + ms);
        long hb = heartbeatMsCfg;
        if (hb < 1000 || hb > 120000) throw new IllegalStateException("csp.sse.heartbeat-ms must be in [1000..120000] got " + hb);
        this.replaySize = rs;
        this.subscriberQueueSize = qs;
        this.maxSubscribers = ms;
        this.heartbeatMs = hb;
    }

    public boolean isEnabled() { return enabled; }
    public String instanceId() { return instanceId; }

    static void validateNoCRLF(String s, String field) {
        if (s == null) throw new IllegalArgumentException(field + " is null");
        if (s.contains("\r") || s.contains("\n") ) throw new IllegalArgumentException(field + " contains CRLF");
    }
    static void validateLength(String s, int max, String field) {
        if (s.length() > max) throw new IllegalArgumentException(field + " too long");
    }
    static void validateSubject(String subject) {
        validateNoCRLF(subject, "subject");
        validateLength(subject, 64, "subject");
        if (!subject.matches("[a-zA-Z0-9._-]{1,64}")) throw new IllegalArgumentException("subject invalid characters");
    }
    static void validateState(String state) {
        validateNoCRLF(state, "state");
        validateLength(state, 32, "state");
        if (!ALLOWED_STATES.contains(state)) throw new IllegalArgumentException("state not allowlisted");
    }
    static void validateCount(int count, String field) {
        if (count < 0 || count > 1_000_000) throw new IllegalArgumentException(field + " out of range");
    }
    public static final long MAX_CACHE_EVENT_SIZE = 10_000_000L;
    static void validateCacheSize(long size, String field) {
        if (size < 0 || size > MAX_CACHE_EVENT_SIZE) throw new IllegalArgumentException(field + " out of range");
    }

    public void publishStatusChanged(int totalConnectors, int activeConnectors, int sessionCount, boolean degraded) {
        validateCount(totalConnectors, "totalConnectors");
        validateCount(activeConnectors, "activeConnectors");
        validateCount(sessionCount, "sessionCount");
        StatusChangedPayload p = new StatusChangedPayload(totalConnectors, activeConnectors, sessionCount, degraded);
        enqueuePublish(AdminEventType.STATUS_CHANGED, p);
    }
    public void publishConfigReloaded(String subject) {
        validateSubject(subject);
        ConfigReloadedPayload p = new ConfigReloadedPayload(subject);
        enqueuePublish(AdminEventType.CONFIG_RELOADED, p);
    }
    public void publishConnectorChanged(String state, int count) {
        validateState(state);
        validateCount(count, "count");
        ConnectorChangedPayload p = new ConnectorChangedPayload(state, count);
        enqueuePublish(AdminEventType.CONNECTOR_CHANGED, p);
    }
    public void publishSessionChanged(int count, String state) {
        validateCount(count, "count");
        validateState(state);
        SessionChangedPayload p = new SessionChangedPayload(count, state);
        enqueuePublish(AdminEventType.SESSION_CHANGED, p);
    }
    public void publishCacheChanged(int size, String state) {
        validateCount(size, "size");
        validateState(state);
        CacheChangedPayload p = new CacheChangedPayload(size, state);
        enqueuePublish(AdminEventType.CACHE_CHANGED, p);
    }
    /**
     * Saturation effectuée PAR bridge (Math.min(cachedEcms, MAX_CACHE_EVENT_SIZE)).
     * Ce broker rejette directement &gt;MAX (IllegalArgumentException).
     */
    public void publishCacheChanged(long size, String state) {
        validateCacheSize(size, "size");
        validateState(state);
        CacheChangedPayload p = new CacheChangedPayload(size, state);
        enqueuePublish(AdminEventType.CACHE_CHANGED, p);
    }
    public void publishDcwChanged(String state, int groups) {
        validateState(state);
        validateCount(groups, "groups");
        DcwChangedPayload p = new DcwChangedPayload(state, groups);
        enqueuePublish(AdminEventType.DCW_CHANGED, p);
    }

    private void enqueuePublish(AdminEventType type, AdminPayload payload) {
        if (brokerContext == null) {
            long id = nextId.incrementAndGet();
            AdminEvent ev = new AdminEvent(id, Instant.now(), type, payload);
            doPublish(ev);
            return;
        }
        brokerContext.runOnContext(v -> {
            long id = nextId.incrementAndGet();
            AdminEvent ev = new AdminEvent(id, Instant.now(), type, payload);
            doPublish(ev);
        });
    }

    private void doPublish(AdminEvent ev) {
        boolean isReplayable = ev.type() != AdminEventType.SNAPSHOT_REQUIRED && ev.type() != AdminEventType.HEARTBEAT;
        if (isReplayable) {
            replayBuffer.addLast(ev);
            while (replayBuffer.size() > replaySize) replayBuffer.removeFirst();
            published++;
        }
        List<Subscriber> copy = new ArrayList<>(subscribers);
        for (Subscriber sub : copy) {
            dispatchToSubscriber(sub, ev);
        }
    }

    private boolean signalNext(Flow.Subscriber<? super AdminEvent> downstream, BrokerSubscription sub, AdminEvent ev) {
        try {
            downstream.onNext(ev);
            return true;
        } catch (Throwable t) {
            // best-effort terminate, ensure cleanup once
            if (sub.terminated.compareAndSet(false, true)) {
                sub.cancelled.set(true);
                if (sub.holder != null) removeSubscriber(sub.holder);
                if (sub.terminal != null) sub.terminal.clear();
                try { downstream.onError(t); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    private void terminateWithError(BrokerSubscription sub, Throwable t) {
        if (!sub.terminated.compareAndSet(false, true)) return;
        sub.cancelled.set(true);
        if (sub.holder != null) removeSubscriber(sub.holder);
        if (sub.terminal != null) sub.terminal.clear();
        try { sub.downstream.onError(t); } catch (Exception ignored) {}
    }

    private void dispatchToSubscriber(Subscriber sub, AdminEvent ev) {
        if (sub.terminated || sub.subscription.cancelled.get() || sub.subscription.terminated.get()) return;
        long demand = sub.subscription.requested;
        boolean queueEmpty = sub.queue.isEmpty();
        if (demand > 0 && queueEmpty) {
            sub.subscription.requested--;
            if (!signalNext(sub.downstream, sub.subscription, ev)) {
                sub.terminated = true;
                sub.queue.clear();
            }
        } else {
            if (sub.queue.size() >= subscriberQueueSize) {
                handleSlowConsumer(sub);
            } else {
                sub.queue.add(ev);
                if (demand > 0) drain(sub);
            }
        }
    }

    private void handleSlowConsumer(Subscriber sub) {
        if (sub.terminated) return;
        sub.terminated = true;
        slowEvictions++;
        // slow consumer respects RS: never onNext without demand
        // clean immediately, onComplete, no onNext if demand==0
        if (sub.subscription.requested > 0 && !sub.subscription.cancelled.get() && !sub.subscription.terminated.get()) {
            AdminEvent snap = createSnapshotRequiredEvent("SLOW_CONSUMER");
            sub.subscription.requested--;
            if (!signalNext(sub.downstream, sub.subscription, snap)) {
                // signalNext already handled termination
            } else {
                try { sub.downstream.onComplete(); } catch (Exception ignored) {}
                sub.subscription.terminated.set(true);
            }
        } else {
            try { sub.downstream.onComplete(); } catch (Exception ignored) {}
            sub.subscription.terminated.set(true);
        }
        subscribers.remove(sub);
        sub.queue.clear();
        if (subscribers.isEmpty()) cancelHeartbeatTimer();
    }

    private AdminEvent createSnapshotRequiredEvent(String reason) {
        if (!SNAPSHOT_REASONS.contains(reason)) reason = "REPLAY_GAP";
        long id = nextId.incrementAndGet();
        return new AdminEvent(id, Instant.now(), AdminEventType.SNAPSHOT_REQUIRED, new SnapshotRequiredPayload(reason));
    }

    private AdminEvent createHeartbeatEvent() {
        long id = nextId.incrementAndGet();
        return new AdminEvent(id, Instant.now(), AdminEventType.HEARTBEAT, new HeartbeatPayload(id));
    }

    public Multi<AdminEvent> subscribe(String lastEventIdRaw) {
        return Multi.createFrom().publisher(publisher(lastEventIdRaw));
    }

    private Flow.Publisher<AdminEvent> publisher(String lastEventIdRaw) {
        return subscriber -> {
            BrokerSubscription sub = new BrokerSubscription(subscriber, this);
            subscriber.onSubscribe(sub);
            if (brokerContext != null) {
                brokerContext.runOnContext(v -> handleSubscribe(sub, lastEventIdRaw));
            } else {
                handleSubscribe(sub, lastEventIdRaw);
            }
        };
    }

    void handleSubscribe(BrokerSubscription subscription, String raw) {
        if (subscription.cancelled.get() || subscription.terminated.get()) return;
        Flow.Subscriber<? super AdminEvent> downstream = subscription.downstream;
        String trimmed = raw == null ? null : raw.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            if (subscription.cancelled.get()) return;
            if (subscribers.size() >= maxSubscribers) {
                rejectedSubscribers++;
                terminateWithError(subscription, new RejectedSubscriberException("MAX_SUBSCRIBERS_REACHED"));
                return;
            }
            if (subscription.cancelled.get()) return;
            Subscriber holder = new Subscriber(downstream, subscription, subscriberQueueSize);
            subscription.holder = holder;
            subscribers.add(holder);
            if (subscribers.size() == 1) startHeartbeatTimer();
            return;
        }
        long lastId;
        try {
            lastId = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            attachTerminal(subscription, "INVALID_LAST_EVENT_ID");
            return;
        }
        if (lastId < 0) {
            attachTerminal(subscription, "INVALID_LAST_EVENT_ID");
            return;
        }
        long currentId = nextId.get() == 0 ? 0 : nextId.get(); // currentId = last published id (0 if none)
        // valid if lastId == currentId (live without replay), only > is future
        if (lastId > currentId) {
            attachTerminal(subscription, "FUTURE_LAST_EVENT_ID");
            return;
        }
        if (!replayBuffer.isEmpty()) {
            long oldest = replayBuffer.peekFirst().id();
            if (lastId < oldest - 1) {
                attachTerminal(subscription, "REPLAY_GAP");
                return;
            }
        } else {
            // empty buffer: if published>0 then any lastId != currentId is gap (buffer evicted)
            if (published > 0 && lastId != currentId) {
                attachTerminal(subscription, "REPLAY_GAP");
                return;
            }
            // no events yet, lastId==0 already handled as valid, also allow 0
            if (published == 0 && lastId != 0) {
                attachTerminal(subscription, "INVALID_LAST_EVENT_ID");
                return;
            }
        }
        if (subscription.cancelled.get()) return;
        if (subscribers.size() >= maxSubscribers) {
            rejectedSubscribers++;
            terminateWithError(subscription, new RejectedSubscriberException("MAX_SUBSCRIBERS_REACHED"));
            return;
        }
        if (subscription.cancelled.get()) return;
        Subscriber holder = new Subscriber(downstream, subscription, subscriberQueueSize);
        subscription.holder = holder;
        subscribers.add(holder);
        if (subscribers.size() == 1) startHeartbeatTimer();
        List<AdminEvent> toReplay = replayBuffer.stream().filter(e -> e.id() > lastId).toList();
        for (AdminEvent e : toReplay) {
            if (holder.terminated || subscription.cancelled.get()) break;
            if (holder.queue.size() >= subscriberQueueSize) {
                handleSlowConsumer(holder);
                break;
            }
            long demand = subscription.requested;
            boolean queueEmpty = holder.queue.isEmpty();
            if (demand > 0 && queueEmpty) {
                subscription.requested--;
                if (!signalNext(downstream, subscription, e)) {
                    holder.terminated = true;
                    holder.queue.clear();
                    break;
                }
            } else {
                if (holder.queue.size() >= subscriberQueueSize) { handleSlowConsumer(holder); break; }
                holder.queue.add(e);
            }
        }
        if (!holder.terminated && subscription.requested > 0 && !holder.queue.isEmpty() && !subscription.cancelled.get()) {
            drain(holder);
        }
    }

    private void attachTerminal(BrokerSubscription subscription, String reason) {
        replayGaps++;
        AdminEvent snap = createSnapshotRequiredEvent(reason);
        TerminalHolder th = new TerminalHolder(subscription.downstream, subscription, snap);
        subscription.terminal = th;
        if (subscription.cancelled.get()) {
            th.clear();
            return;
        }
        // not added to live subscribers, not counted in max, no heartbeat
        if (subscription.requested > 0 && !subscription.cancelled.get()) {
            drainTerminal(th);
        } // else wait for request
    }

    void drain(Subscriber sub) {
        if (sub.terminated || sub.draining) return;
        if (sub.subscription.cancelled.get() || sub.subscription.terminated.get()) return;
        sub.draining = true;
        try {
            while (!sub.queue.isEmpty()) {
                long demand = sub.subscription.requested;
                if (demand <= 0) break;
                if (sub.subscription.cancelled.get() || sub.subscription.terminated.get()) break;
                AdminEvent e = sub.queue.poll();
                if (e == null) break;
                sub.subscription.requested--;
                if (!signalNext(sub.downstream, sub.subscription, e)) {
                    sub.terminated = true;
                    sub.queue.clear();
                    break;
                }
            }
        } finally {
            sub.draining = false;
        }
    }

    void drainTerminal(TerminalHolder th) {
        if (th.terminated || th.draining || th.completed) return;
        if (th.subscription.cancelled.get() || th.subscription.terminated.get()) return;
        th.draining = true;
        try {
            while (!th.queue.isEmpty()) {
                long demand = th.subscription.requested;
                if (demand <= 0) break;
                if (th.subscription.cancelled.get() || th.subscription.terminated.get()) break;
                AdminEvent e = th.queue.poll();
                if (e == null) break;
                th.subscription.requested--;
                if (!signalNext(th.downstream, th.subscription, e)) {
                    th.terminated = true;
                    th.queue.clear();
                    return;
                }
            }
            if (th.queue.isEmpty() && !th.completed && !th.terminated) {
                th.completed = true;
                th.subscription.terminated.set(true);
                try { th.downstream.onComplete(); } catch (Exception ignored) {}
                th.clear();
            }
        } finally {
            th.draining = false;
        }
    }

    void removeSubscriber(Subscriber sub) {
        boolean removed = subscribers.remove(sub);
        if (removed) {
            sub.terminated = true;
            sub.queue.clear();
            if (subscribers.isEmpty()) cancelHeartbeatTimer();
        }
    }

    void startHeartbeatTimer() {
        if (heartbeatTimerId != -1) return;
        if (heartbeatMs <= 0) return;
        if (vertx == null) return;
        heartbeatTimerId = vertx.setPeriodic(heartbeatMs, tid -> {
            if (brokerContext != null) brokerContext.runOnContext(v -> doHeartbeat());
            else doHeartbeat();
        });
    }

    void cancelHeartbeatTimer() {
        if (heartbeatTimerId != -1 && vertx != null) {
            vertx.cancelTimer(heartbeatTimerId);
            heartbeatTimerId = -1;
        }
    }

    void doHeartbeat() {
        if (subscribers.isEmpty()) { cancelHeartbeatTimer(); return; }
        AdminEvent hb = createHeartbeatEvent();
        List<Subscriber> copy = new ArrayList<>(subscribers);
        for (Subscriber s : copy) dispatchToSubscriber(s, hb);
    }

    public void triggerHeartbeatForTest() {
        if (brokerContext != null) brokerContext.runOnContext(v -> doHeartbeat());
        else doHeartbeat();
    }

    @PreDestroy
    void shutdown() {
        if (brokerContext != null) {
            brokerContext.runOnContext(v -> {
                cancelHeartbeatTimer();
                for (Subscriber s : new ArrayList<>(subscribers)) {
                    try { s.downstream.onComplete(); } catch (Exception ignored) {}
                    s.queue.clear();
                }
                subscribers.clear();
            });
        } else {
            cancelHeartbeatTimer();
            for (Subscriber s : new ArrayList<>(subscribers)) {
                try { s.downstream.onComplete(); } catch (Exception ignored) {}
                s.queue.clear();
            }
            subscribers.clear();
        }
    }

    public Future<BrokerStats> statsAsync() {
        Promise<BrokerStats> p = Promise.promise();
        if (brokerContext != null) brokerContext.runOnContext(v -> p.complete(currentStats()));
        else p.complete(currentStats());
        return p.future();
    }

    public BrokerStats currentStats() {
        return new BrokerStats(replayBuffer.size(), subscribers.size(), published, slowEvictions, replayGaps, rejectedSubscribers);
    }

    long getHeartbeatTimerId() { return heartbeatTimerId; }
    int getReplayBufferSize() { return replayBuffer.size(); }
    long getNextId() { return nextId.get(); }
}
