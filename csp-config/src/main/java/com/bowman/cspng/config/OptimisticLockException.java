package com.bowman.cspng.config;

/**
 * Exception levee en cas de conflit de version (optimistic locking).
 */
public class OptimisticLockException extends RuntimeException {
    private final String table;
    private final String entityName;
    private final long expectedVersion;

    public OptimisticLockException(String table, String entityName, long expectedVersion) {
        super(String.format("Optimistic lock conflict on %s '%s': expected version %d",
            table, entityName, expectedVersion));
        this.table = table;
        this.entityName = entityName;
        this.expectedVersion = expectedVersion;
    }

    public String table() { return table; }
    public String entityName() { return entityName; }
    public long expectedVersion() { return expectedVersion; }
}
