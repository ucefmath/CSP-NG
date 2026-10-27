package com.bowman.cspng.users;

/**
 * Exception levée quand un utilisateur n'est pas trouvé.
 */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String userName) {
        super("User not found: " + userName);
    }
}
