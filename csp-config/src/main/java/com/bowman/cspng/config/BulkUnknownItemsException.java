package com.bowman.cspng.config;

import java.util.List;

/**
 * Items bulk inexistants — la transaction est roulée back intégralement (rien d'appliqué).
 * Mapping API : 400 {@code UNKNOWN_ITEMS} avec la liste dans {@code details}.
 */
public class BulkUnknownItemsException extends RuntimeException {

    private final List<String> unknownIds;

    public BulkUnknownItemsException(List<String> unknownIds) {
        super("unknown items: " + String.join(", ", unknownIds));
        this.unknownIds = List.copyOf(unknownIds);
    }

    public List<String> unknownIds() {
        return unknownIds;
    }
}
