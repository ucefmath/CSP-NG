package com.bowman.cspng.domain.srvid;

/**
 * Information descriptive d'une chaîne / service TV (issu de oscam.srvid).
 *
 * @param name        nom de la chaîne (ex: "Canal+ 360")
 * @param provider    nom du bouquet / diffuseur (ex: "Orange Polska Platforma Canal+")
 * @param type        type de service (ex: "TV", "Radio")
 * @param description description optionnelle (ex: "General", "Movies")
 */
public record ServiceInfo(String name, String provider, String type, String description) {

  public ServiceInfo(String name) {
    this(name, null, null, null);
  }

  public String displayName() {
    if (name != null && !name.isBlank()) {
      return name;
    }
    if (provider != null && !provider.isBlank()) {
      return provider;
    }
    return "";
  }
}
