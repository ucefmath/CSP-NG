package com.bowman.cspng.users;

/**
 * Exception typée levée après advisory lock quand aucun admin n'existe et
 * les credentials de bootstrap sont manquants.
 */
public class BootstrapCredentialsMissingException extends RuntimeException {
    public BootstrapCredentialsMissingException(String message) {
        super(message);
    }
}
