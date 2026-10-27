package com.bowman.cspng.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Holder thread-safe pour {@link ConfigSnapshot} via {@link AtomicReference}.
 *
 * <p>Initialisation fail-fast : l'appel a {@link #set(ConfigSnapshot)} est obligatoire
 * avant tout acces. Les getters retournent le snapshot courant sans allocation.
 */
public class ConfigHolder {

    private static final Logger LOG = Logger.getLogger(ConfigHolder.class.getName());

    private final AtomicReference<ConfigSnapshot> ref = new AtomicReference<>();

    /**
     * Remplace atomiquement le snapshot courant.
     * @throws NullPointerException si snapshot est null
     */
    public void set(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "ConfigSnapshot must not be null");
        ConfigSnapshot old = ref.getAndSet(snapshot);
        if (old != null) {
            LOG.info(() -> "ConfigSnapshot swapped: " + old.loadedAtMs() + " -> " + snapshot.loadedAtMs());
        }
    }

    /**
     * @return le snapshot courant (fail-fast si jamais initialise)
     * @throws IllegalStateException si aucun snapshot n'a ete charge
     */
    public ConfigSnapshot get() {
        ConfigSnapshot snapshot = ref.get();
        if (snapshot == null) {
            throw new IllegalStateException("ConfigSnapshot not initialized — call set() first");
        }
        return snapshot;
    }

    /**
     * @return true si un snapshot a deja ete charge
     */
    public boolean isInitialized() {
        return ref.get() != null;
    }

    /**
     * Met a jour atomiquement le snapshot via une fonction de transformation.
     * Purement volatile — pour usage simple (pas de CAS loop necessaire).
     */
    public void update(java.util.function.UnaryOperator<ConfigSnapshot> updater) {
        ConfigSnapshot old, updated;
        do {
            old = ref.get();
            if (old == null) throw new IllegalStateException("ConfigSnapshot not initialized");
            updated = updater.apply(old);
            Objects.requireNonNull(updated);
        } while (!ref.compareAndSet(old, updated));
    }
}
