package com.bowman.cspng.users;

/**
 * Exception levée quand on tente de supprimer/désactiver/révoquer admin
 * du dernier administrateur actif.
 */
public class LastAdminException extends RuntimeException {
    public LastAdminException(String userName) {
        super("Cannot remove admin role from '" + userName + "': last active admin");
    }
}
