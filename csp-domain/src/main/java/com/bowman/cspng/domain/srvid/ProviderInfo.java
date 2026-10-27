package com.bowman.cspng.domain.srvid;

/**
 * Information descriptive d'un provider / bouquet CAS (issu de oscam.provid).
 *
 * @param provider  nom du provider (ex: "Cyfra+ (13E)", "Canal+ Nederland")
 * @param satellite position satellite / réseau (ex: "13E", "19.2E")
 * @param language  langue optionnelle
 */
public record ProviderInfo(String provider, String satellite, String language) {

  public ProviderInfo(String provider) {
    this(provider, null, null);
  }

  public String displayName() {
    return provider != null ? provider : "";
  }
}
