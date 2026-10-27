package com.bowman.cspng.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StatusRouterTest {

  @Test
  void welcomeContainsCspNg() {
    assertTrue(new StatusRouter().welcome().contains("CSP-NG"));
  }
}
