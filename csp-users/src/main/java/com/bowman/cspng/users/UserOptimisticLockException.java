package com.bowman.cspng.users;

/**
 * Exception levée en cas de conflit de version (optimistic locking) sur un utilisateur.
 */
public class UserOptimisticLockException extends RuntimeException {
    private final String userName;
    private final long expectedVersion;

    public UserOptimisticLockException(String userName, long expectedVersion) {
        super("Optimistic lock conflict on user '" + userName + "': expected version " + expectedVersion);
        this.userName = userName;
        this.expectedVersion = expectedVersion;
    }

    public String userName() { return userName; }
    public long expectedVersion() { return expectedVersion; }
}
