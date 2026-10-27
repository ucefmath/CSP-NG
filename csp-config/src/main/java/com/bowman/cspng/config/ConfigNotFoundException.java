package com.bowman.cspng.config;

/**
 * Exception levée quand une entité de configuration n'est pas trouvée.
 */
public class ConfigNotFoundException extends RuntimeException {
    private final String table;
    private final String entityName;

    public ConfigNotFoundException(String table, String entityName) {
        super("Config entity not found: " + table + "/" + entityName);
        this.table = table;
        this.entityName = entityName;
    }

    public String table() { return table; }
    public String entityName() { return entityName; }
}
