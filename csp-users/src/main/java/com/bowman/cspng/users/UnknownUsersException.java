package com.bowman.cspng.users;

import java.util.List;

/**
 * Users bulk inexistants — la transaction est roulée back intégralement (rien d'appliqué).
 * Mapping API : 400 {@code UNKNOWN_ITEMS} avec la liste dans {@code details}.
 */
public class UnknownUsersException extends RuntimeException {

    private final List<String> unknownNames;

    public UnknownUsersException(List<String> unknownNames) {
        super("unknown users: " + String.join(", ", unknownNames));
        this.unknownNames = List.copyOf(unknownNames);
    }

    public List<String> unknownNames() {
        return unknownNames;
    }
}
