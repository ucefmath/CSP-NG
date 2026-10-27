package com.bowman.cspng.users;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IpMatcherTest {

  @Test void wildcardStarAllowsAll() {
    assertTrue(IpMatcher.isAllowed("1.2.3.4", "*"));
    assertTrue(IpMatcher.isAllowed("2001:db8::1", "*"));
  }

  @Test void exactMatch() {
    assertTrue(IpMatcher.isAllowed("10.0.0.1", "10.0.0.1"));
    assertFalse(IpMatcher.isAllowed("10.0.0.2", "10.0.0.1"));
  }

  @Test void wildcardLegacy() {
    assertTrue(IpMatcher.isAllowed("192.168.1.10", "192.168.1.*"));
    assertTrue(IpMatcher.isAllowed("192.168.10.5", "192.168.*"));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "192.168.*"));
    assertFalse(IpMatcher.isAllowed("1.2.3.4", "10.0.*.5")); // star not at simple prefix still works as prefix before star
  }

  @Test void cidrIpv4() {
    assertTrue(IpMatcher.isAllowed("10.0.0.5", "10.0.0.0/24"));
    assertTrue(IpMatcher.isAllowed("10.0.0.255", "10.0.0.0/24"));
    assertFalse(IpMatcher.isAllowed("10.0.1.0", "10.0.0.0/24"));
    assertTrue(IpMatcher.isAllowed("10.0.0.1", "10.0.0.0/8"));
    assertFalse(IpMatcher.isAllowed("11.0.0.1", "10.0.0.0/8"));
    assertTrue(IpMatcher.isAllowed("0.0.0.0", "0.0.0.0/0")); // any
    assertFalse(IpMatcher.isAllowed("192.168.1.1", "192.168.1.1/32") == false); // exact /32
    assertTrue(IpMatcher.isAllowed("192.168.1.1", "192.168.1.1/32"));
    assertFalse(IpMatcher.isAllowed("192.168.1.2", "192.168.1.1/32"));
  }

  @Test void cidrIpv6() {
    assertTrue(IpMatcher.isAllowed("2001:db8::1", "2001:db8::/32"));
    assertTrue(IpMatcher.isAllowed("2001:db8:ffff::1", "2001:db8::/32"));
    assertFalse(IpMatcher.isAllowed("2001:db9::1", "2001:db8::/32"));
    assertTrue(IpMatcher.isAllowed("::1", "::1/128"));
    assertFalse(IpMatcher.isAllowed("::2", "::1/128"));
  }

  @Test void invalidSyntaxFailClosed() {
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "10.0.0.0/33")); // prefix too long
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "10.0.0.0/abc"));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "notanip/24"));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "10.0.0.0/-1"));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "192.168.1.*extra*invalid"));
    // IPv4 vs IPv6 mismatch
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "2001:db8::/32"));
  }

  @Test void nullAndEmpty() {
    assertFalse(IpMatcher.isAllowed(null, "10.0.0.0/24"));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", null));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", ""));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "   "));
  }

  @Test void hostnameAndAbbreviatedRejected() {
    assertFalse(IpMatcher.isAllowed("localhost", "192.168.1.1"));
    assertFalse(IpMatcher.isAllowed("192.168.1.1", "localhost"));
    assertFalse(IpMatcher.isAllowed("10.0.1", "10.0.1.0/24")); // abbreviated remote
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "10.0.1/24")); // abbreviated net
    assertFalse(IpMatcher.isAllowed("10", "10.0.0.1"));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "myhost.example.com"));
    assertFalse(IpMatcher.isAllowed("::1", "localhost/128"));
  }

  @Test void wildcardStrictOnlyTerminal() {
    assertFalse(IpMatcher.isAllowed("10.0.5.1", "10.0.*.5"));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "10.*.0.1"));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "10.0.0.*extra"));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "10.0.0.**"));
    // valid wildcard still true – "* alone" is true via early check
    assertTrue(IpMatcher.isAllowed("10.5.6.7", "*"));
    assertFalse(IpMatcher.isAllowed("10.0.0.1", "10.0.*.*"));
    // IPv6 wildcard not allowed
    assertFalse(IpMatcher.isAllowed("2001:db8::1", "2001:db8::*"));
  }

  @Test void ipv6LiteralForms() {
    assertTrue(IpMatcher.isAllowed("::1", "::1"));
    assertTrue(IpMatcher.isAllowed("fe80::1", "fe80::1"));
    // La correspondance exacte reste une égalité textuelle (comportement inchangé)
    assertFalse(IpMatcher.isAllowed("2001:db8::1", "2001:0db8:0000:0000:0000:0000:0000:0001"));
    assertFalse(IpMatcher.isAllowed("0:0:0:0:0:0:0:1", "::1"));
    assertFalse(IpMatcher.isAllowed("2001:db8::2", "2001:db8::1"));
    // IPv4-mapped (comportement préservé)
    assertTrue(IpMatcher.isAllowed("::ffff:192.0.2.1", "::ffff:192.0.2.1"));
  }

  @Test void invalidIpv6FailClosed() {
    assertFalse(IpMatcher.isAllowed("::1", ":::1"));
    assertFalse(IpMatcher.isAllowed("::1", "1:2:3:4:5:6:7:8:9"));
    assertFalse(IpMatcher.isAllowed("::1", "1::2::3"));
    assertFalse(IpMatcher.isAllowed("::1", "::gggg"));
    assertFalse(IpMatcher.isAllowed("::1", ":1"));
  }
}
