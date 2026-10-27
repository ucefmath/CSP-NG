package com.bowman.cspng.config;

/**
 * Affectation explicite connecteur amont → worker (Gemini FIX : aucun
 * connecteur amont sans affectation). Le worker refuse toute config locale
 * independante : seuls ces connecteurs sont instancies.
 */
public record RelayNodeConnectorAssignment(
    String nodeName,
    String connectorName,
    boolean enabled,
    long version
) {
  public RelayNodeConnectorAssignment {
    if (nodeName == null || !nodeName.matches("^[a-z0-9][a-z0-9._-]{0,62}$"))
      throw new IllegalArgumentException("nodeName invalid");
    if (connectorName == null || connectorName.isBlank())
      throw new IllegalArgumentException("connectorName required");
    if (version < 1) throw new IllegalArgumentException("version must be >= 1");
  }
}
