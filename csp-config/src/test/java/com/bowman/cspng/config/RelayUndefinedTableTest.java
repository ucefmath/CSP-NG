package com.bowman.cspng.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConfigRepository.isUndefinedTable — tolerance 42P01 stricte (FIX Sonnet #2)")
class RelayUndefinedTableTest {

  @Test
  @DisplayName("42P01 sur table relais => tolerance, autre panne => propagation")
  void strictTolerance() {
    RuntimeException missing = new RuntimeException(
        "ERROR: relation \"csp_relay_node\" does not exist (42P01)");
    assertTrue(ConfigRepository.isUndefinedTable(missing));

    RuntimeException connRefused = new RuntimeException("Connection refused");
    assertFalse(ConfigRepository.isUndefinedTable(connRefused));

    RuntimeException otherTable = new RuntimeException(
        "ERROR: relation \"csp_profile\" does not exist");
    // Autre table : ne doit pas etre confondu avec les tables relais... sauf si
    // le message porte 42P01 sur une table relais. Ici pas de 42P01 et pas de
    // table relais => false (propagation).
    assertFalse(ConfigRepository.isUndefinedTable(otherTable));
  }
}
