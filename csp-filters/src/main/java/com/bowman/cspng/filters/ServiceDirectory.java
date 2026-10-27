package com.bowman.cspng.filters;

import java.util.Optional;

/**
 * SPI d'annuaire de services.
 *
 * <p>Sans dépendance config/app. Le filtre {@code detectLinks} ne traite
 * que les services TV connus et dans le scope du profil.
 */
public interface ServiceDirectory {

  /**
   * Cherche un descripteur de service.
   *
   * @param profile   nom du profil
   * @param serviceId identifiant du service (16 bits, ex. 0x1810)
   * @return descripteur si trouvé, vide sinon
   */
  Optional<ServiceDescriptor> lookup(String profile, int serviceId);

  /**
   * Descripteur de service (scope profil).
   *
   * @param profile     nom du profil
   * @param serviceId   identifiant du service (16 bits)
   * @param displayName nom d'affichage (jamais null)
   * @param tv          true si service TV (pas radio/data)
   */
  record ServiceDescriptor(String profile, int serviceId, String displayName, boolean tv) {
    public ServiceDescriptor {
      if (profile == null || profile.isBlank())
        throw new IllegalArgumentException("profile required");
      if (displayName == null || displayName.isBlank())
        throw new IllegalArgumentException("displayName required");
    }

    @Override
    public String toString() {
      return Integer.toHexString(serviceId) + ":" + profile + ":" + displayName + (tv ? ":tv" : "");
    }
  }
}
