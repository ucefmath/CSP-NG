package com.bowman.cspng.web.admin;

import com.bowman.cspng.web.admin.dto.CreateUserRequest;
import com.bowman.cspng.web.admin.dto.UpdateUserRequest;
import com.bowman.cspng.web.admin.dto.UserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de validation des DTOs admin - contrat JSON users/ACL.
 */
class AdminDtoValidationTest {

    private static Validator validator;
    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
        mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
    }

    @Test
    void createUserRequestValid() {
        CreateUserRequest req = new CreateUserRequest("testuser", "Test User", "password123456", null,
            true, false, false, false, 1, null, -1, null, null, null, null);
        var violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
    }

    @Test
    void createUserRequestNameBlank() {
        CreateUserRequest req = new CreateUserRequest("", "", "password123456", null,
            true, false, false, false, 1, null, -1, null, null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for blank name");
    }

    @Test
    void createUserRequestPasswordTooShort() {
        CreateUserRequest req = new CreateUserRequest("testuser", "Test", "123", null, // 3 chars
            true, false, false, false, 1, null, -1, null, null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for short password");
    }

    @Test
    void createUserRequestPasswordMinLength() {
        CreateUserRequest req = new CreateUserRequest("@STB@", "Test", "@REC@", null, // @STB@ / @REC@
            true, false, false, false, 1, null, -1, null, null, null, null);
        var violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "Expected no violations for @STB@ and @REC@");
    }

    @Test
    void updateUserRequestAllNullIsValid() {
        UpdateUserRequest req = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "Expected no violations for all-null update");
    }

    @Test
    void updateUserPasswordMinLength() {
        UpdateUserRequest req = new UpdateUserRequest(null, "@REC@", null, null, null, null, null, null, null, null, null, null, null, null);
        var violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "Expected no violations for @REC@ password in update");
    }

    @Test
    void updateUserPasswordTooShort() {
        UpdateUserRequest req = new UpdateUserRequest(null, "abc", null, null, null, null, null, null, null, null, null, null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for short password in update");
    }

    // -- ACL contract tests --

    @Test
    void createNullAclBecomesEmpty() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, null, null, null);
        assertEquals(Set.of(), req.profiles());
        assertEquals(Map.of(), req.allowedServices());
        assertEquals(Map.of(), req.blockedServices());
        assertEquals(Set.of(), req.allowedConnectors());
    }

    @Test
    void createWithAclValid() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1,
            Set.of("p1", "p2"), Map.of("p1", Set.of(100, 200)), Map.of("p1", Set.of(300)), Set.of("conn1"));
        var violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
    }

    @Test
    void createWithInternalSpaceValid() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1,
            Set.of("p 1"), Map.of("p 1", Set.of(100)), Map.of(), Set.of("conn 1"));
        var violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "Internal space should be allowed, got: " + violations);
    }

    @Test
    void createAclTooManyProfiles() {
        Set<String> many = new HashSet<>();
        for (int i = 0; i < 501; i++) many.add("p" + i);
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, many, null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for too many profiles");
    }

    @Test
    void createAclTooManyMapKeys501Allowed() {
        Map<String, Set<Integer>> big = new HashMap<>();
        for (int i = 0; i < 501; i++) big.put("k" + i, Set.of(1));
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, big, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for 501 map keys allowedServices");
    }

    @Test
    void createAclTooManyMapKeys501Blocked() {
        Map<String, Set<Integer>> big = new HashMap<>();
        for (int i = 0; i < 501; i++) big.put("k" + i, Set.of(1));
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, null, big, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for 501 map keys blockedServices");
    }

    @Test
    void createAclTooManyConnectors() {
        Set<String> many = new HashSet<>();
        for (int i = 0; i < 501; i++) many.add("c" + i);
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, null, null, many);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for too many connectors");
    }

    @Test
    void createAclInvalidSidTooLarge() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1,
            Set.of("p1"), Map.of("p1", Set.of(99999)), Map.of(), Set.of());
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for SID out of range (>65535)");
    }

    @Test
    void createAclInvalidSidNegative() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1,
            Set.of("p1"), Map.of("p1", Set.of(-1)), Map.of(), Set.of());
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for SID negative");
    }

    @Test
    void createAclSidNullRejected() {
        Set<Integer> withNull = new HashSet<>();
        withNull.add(100);
        withNull.add(null);
        Map<String, Set<Integer>> allowed = new HashMap<>();
        allowed.put("p1", withNull);
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, allowed, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for null SID");
    }

    @Test
    void createAclSidNullInBlockedRejected() {
        Set<Integer> withNull = new HashSet<>();
        withNull.add(null);
        Map<String, Set<Integer>> blocked = new HashMap<>();
        blocked.put("p1", withNull);
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, null, blocked, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for null SID in blocked");
    }

    @Test
    void createProfileBlankRejected() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, Set.of(""), null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for blank profile");
    }

    @Test
    void createProfileLeadingSpaceRejected() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, Set.of(" p1"), null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for leading space");
    }

    @Test
    void createProfileTrailingSpaceRejected() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, Set.of("p1 "), null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for trailing space");
    }

    @Test
    void createProfileControlRejected() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, Set.of("p\u0001b"), null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for control char");
    }

    @Test
    void createProfileNulRejected() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, Set.of("p\u0000b"), null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for NUL char");
    }

    @Test
    void createMapKeyLeadingSpaceRejected() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, Map.of(" p1", Set.of(1)), null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for map key leading space");
    }

    @Test
    void createMapKeyControlRejected() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, Map.of("p\u0007", Set.of(1)), null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for map key control");
    }

    @Test
    void createConnectorBlankRejected() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, null, null, Set.of(""));
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for blank connector");
    }

    @Test
    void createConnectorTrailingSpaceRejected() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, null, null, Set.of("c1 "));
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for connector trailing space");
    }

    @Test
    void createMaxConnectionsOutOfBoundsLow() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, -2, null, -1, null, null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for maxConnections -2");
    }

    @Test
    void createMaxConnectionsOutOfBoundsHigh() {
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 100001, null, -1, null, null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "Expected violations for maxConnections 100001");
    }

    @Test
    void createMaxConnectionsBoundsValid() {
        for (int v : new int[]{-1, 0, 1, 100000}) {
            CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
                true, false, false, false, v, null, -1, null, null, null, null);
            var violations = validator.validate(req);
            assertTrue(violations.isEmpty(), "Expected no violations for maxConnections " + v + " got " + violations);
        }
    }

    @Test
    void createAllowedEcmRateBounds() {
        CreateUserRequest low = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -2, null, null, null, null);
        assertFalse(validator.validate(low).isEmpty(), "ECM -2 should fail");
        CreateUserRequest high = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, 100001, null, null, null, null);
        assertFalse(validator.validate(high).isEmpty(), "ECM 100001 should fail");
        for (int v : new int[]{-1, 0, 100000}) {
            CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
                true, false, false, false, 1, null, v, null, null, null, null);
            assertTrue(validator.validate(req).isEmpty(), "ECM " + v + " should pass");
        }
    }

    @Test
    void updateMaxConnectionsBounds() {
        UpdateUserRequest low = new UpdateUserRequest(null, null, null, null, null, null, null, -2, null, null, null, null, null, null);
        assertFalse(validator.validate(low).isEmpty(), "update maxConnections -2 should fail");
        UpdateUserRequest high = new UpdateUserRequest(null, null, null, null, null, null, null, 100001, null, null, null, null, null, null);
        assertFalse(validator.validate(high).isEmpty(), "update maxConnections 100001 should fail");
        UpdateUserRequest okNull = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(validator.validate(okNull).isEmpty(), "null should pass");
        UpdateUserRequest okMinus1 = new UpdateUserRequest(null, null, null, null, null, null, null, -1, null, null, null, null, null, null);
        assertTrue(validator.validate(okMinus1).isEmpty(), "-1 should pass");
    }

    @Test
    void updateAllowedEcmRateBounds() {
        UpdateUserRequest low = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, -2, null, null, null, null);
        assertFalse(validator.validate(low).isEmpty(), "update ecm -2 should fail");
        UpdateUserRequest high = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, 100001, null, null, null, null);
        assertFalse(validator.validate(high).isEmpty(), "update ecm 100001 should fail");
        UpdateUserRequest ok = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, 500, null, null, null, null);
        assertTrue(validator.validate(ok).isEmpty());
    }

    @Test
    void updateNullAclMeansUnchanged() {
        UpdateUserRequest req = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertNull(req.profiles());
        assertNull(req.allowedServices());
        assertNull(req.blockedServices());
        assertNull(req.allowedConnectors());
        var violations = validator.validate(req);
        assertTrue(violations.isEmpty());
    }

    @Test
    void updateEmptyAclMeansClear() {
        UpdateUserRequest req = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, null, Set.of(), Map.of(), Map.of(), Set.of());
        assertEquals(Set.of(), req.profiles());
        assertEquals(Map.of(), req.allowedServices());
        assertEquals(Map.of(), req.blockedServices());
        assertEquals(Set.of(), req.allowedConnectors());
        var violations = validator.validate(req);
        assertTrue(violations.isEmpty());
    }

    @Test
    void updatePartialAclValid() {
        UpdateUserRequest req = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, null, null, Map.of("p1", Set.of(100)), null, null);
        var violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "Partial allowed only should be valid, got: " + violations);
    }

    @Test
    void updateProfileLeadingSpaceRejected() {
        UpdateUserRequest req = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, null, Set.of(" p1"), null, null, null);
        assertFalse(validator.validate(req).isEmpty(), "leading space in update should fail");
    }

    @Test
    void updateMap501Rejected() {
        Map<String, Set<Integer>> big = new HashMap<>();
        for (int i = 0; i < 501; i++) big.put("k" + i, Set.of(1));
        UpdateUserRequest req = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, null, null, big, null, null);
        assertFalse(validator.validate(req).isEmpty(), "501 keys in update should fail");
    }

    @Test
    void createJsonDeserializationNullToEmpty() throws Exception {
        String json = "{\"name\":\"juser\",\"password\":\"password123456\",\"enabled\":true,\"admin\":false,\"debug\":false,\"mapExcluded\":false,\"maxConnections\":1,\"allowedEcmRate\":-1}";
        CreateUserRequest req = mapper.readValue(json, CreateUserRequest.class);
        assertEquals(Set.of(), req.profiles());
        assertEquals(Map.of(), req.allowedServices());
        assertEquals(Map.of(), req.blockedServices());
        assertEquals(Set.of(), req.allowedConnectors());
    }

    @Test
    void createJsonWithAclDeserialized() throws Exception {
        String json = "{\"name\":\"juser\",\"password\":\"password123456\",\"enabled\":true,\"admin\":false,\"debug\":false,\"mapExcluded\":false,\"maxConnections\":1,\"allowedEcmRate\":-1,\"profiles\":[\"p1\"],\"allowedServices\":{\"p1\":[100,200]},\"blockedServices\":{\"p1\":[300]},\"allowedConnectors\":[\"c1\"]}";
        CreateUserRequest req = mapper.readValue(json, CreateUserRequest.class);
        assertEquals(Set.of("p1"), req.profiles());
        assertEquals(Set.of(100, 200), req.allowedServices().get("p1"));
        assertEquals(Set.of(300), req.blockedServices().get("p1"));
        assertEquals(Set.of("c1"), req.allowedConnectors());
    }

    @Test
    void updateJsonMissingAclIsNull() throws Exception {
        String json = "{\"displayName\":\"New\"}";
        UpdateUserRequest req = mapper.readValue(json, UpdateUserRequest.class);
        assertNull(req.profiles());
        assertNull(req.allowedServices());
        assertNull(req.blockedServices());
        assertNull(req.allowedConnectors());
        assertEquals("New", req.displayName());
    }

    @Test
    void updateJsonEmptyAclIsEmpty() throws Exception {
        String json = "{\"profiles\":[],\"allowedServices\":{},\"blockedServices\":{},\"allowedConnectors\":[]}";
        UpdateUserRequest req = mapper.readValue(json, UpdateUserRequest.class);
        assertEquals(Set.of(), req.profiles());
        assertEquals(Map.of(), req.allowedServices());
        assertEquals(Map.of(), req.blockedServices());
        assertEquals(Set.of(), req.allowedConnectors());
    }

    @Test
    void userResponseNeverContainsHashAndExposesAcl() throws Exception {
        UserResponse resp = new UserResponse("u1", "U1", "a@b.c", true, false, false, false, 1, null, -1, 1L,
            Set.of("p1"), Map.of("p1", Set.of(100)), Map.of("p1", Set.of(200)), Set.of("c1"));
        String json = mapper.writeValueAsString(resp);
        assertFalse(json.contains("password"));
        assertFalse(json.contains("$2a$"));
        assertTrue(json.contains("profiles"));
        assertTrue(json.contains("allowedServices"));
        assertTrue(json.contains("blockedServices"));
        assertTrue(json.contains("allowedConnectors"));
        // round-trip
        UserResponse round = mapper.readValue(json, UserResponse.class);
        assertEquals(Set.of("p1"), round.profiles());
        assertEquals(Set.of(100), round.allowedServices().get("p1"));
        assertEquals(Set.of(200), round.blockedServices().get("p1"));
        assertEquals(Set.of("c1"), round.allowedConnectors());
    }

    @Test
    void userResponseNullAclBecomesEmpty() {
        UserResponse resp = new UserResponse("u1", "U1", null, true, false, false, false, 1, null, -1, 1L, null, null, null, null);
        assertEquals(Set.of(), resp.profiles());
        assertEquals(Map.of(), resp.allowedServices());
        assertEquals(Map.of(), resp.blockedServices());
        assertEquals(Set.of(), resp.allowedConnectors());
    }

    // -- hardening final: Map value null + name pattern --

    @Test
    void createAllowedServicesValueNullRejected() {
        Map<String, Set<Integer>> allowed = new HashMap<>();
        allowed.put("p1", null);
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, allowed, null, null);
        var v = validator.validate(req);
        assertFalse(v.isEmpty(), "Map value null should be rejected (allowedServices {p1:null})");
    }

    @Test
    void createBlockedServicesValueNullRejected() {
        Map<String, Set<Integer>> blocked = new HashMap<>();
        blocked.put("p1", null);
        CreateUserRequest req = new CreateUserRequest("u1", "U1", "password123456", null,
            true, false, false, false, 1, null, -1, null, null, blocked, null);
        var v = validator.validate(req);
        assertFalse(v.isEmpty(), "Map value null should be rejected (blockedServices {p1:null})");
    }

    @Test
    void updateAllowedServicesValueNullRejected() {
        Map<String, Set<Integer>> allowed = new HashMap<>();
        allowed.put("p1", null);
        UpdateUserRequest req = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, null, null, allowed, null, null);
        var v = validator.validate(req);
        assertFalse(v.isEmpty(), "Update Map value null should be rejected");
    }

    @Test
    void updateBlockedServicesValueNullRejected() {
        Map<String, Set<Integer>> blocked = new HashMap<>();
        blocked.put("p1", null);
        UpdateUserRequest req = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, null, null, null, blocked, null);
        var v = validator.validate(req);
        assertFalse(v.isEmpty(), "Update Map value null should be rejected");
    }

    @Test
    void createJsonMapValueNullRejected() throws Exception {
        String json = "{\"name\":\"u1\",\"password\":\"password123456\",\"enabled\":true,\"admin\":false,\"debug\":false,\"mapExcluded\":false,\"maxConnections\":1,\"allowedEcmRate\":-1,\"allowedServices\":{\"p1\":null}}";
        CreateUserRequest req = mapper.readValue(json, CreateUserRequest.class);
        var v = validator.validate(req);
        assertFalse(v.isEmpty(), "JSON {p1:null} should be rejected after deserialization");
    }

    @Test
    void createNamePatternValid() {
        for (String n : new String[]{"a","A1","user_1","my-user","a.b","a-b_c.d"}) {
            CreateUserRequest req = new CreateUserRequest(n, "U", "password123456", null,
                true, false, false, false, 1, null, -1, null, null, null, null);
            assertTrue(validator.validate(req).isEmpty(), "Valid name " + n + " should pass");
        }
    }

    @Test
    void createNamePatternRejectsSpaces() {
        CreateUserRequest req = new CreateUserRequest("bad name", "U", "password123456", null,
            true, false, false, false, 1, null, -1, null, null, null, null);
        assertFalse(validator.validate(req).isEmpty(), "Name with space should be rejected");
    }

    @Test
    void createNamePatternRejectsSlashControlNulNewline() {
        for (String bad : new String[]{"bad/name","bad\nname","bad\u0000name","bad\u0001name","bad name "}) {
            CreateUserRequest req = new CreateUserRequest(bad, "U", "password123456", null,
                true, false, false, false, 1, null, -1, null, null, null, null);
            assertFalse(validator.validate(req).isEmpty(), "Bad name should be rejected: " + bad.replace("\n","\\n").replace("\u0000","\\0"));
        }
    }

    @Test
    void createNamePatternRejectsColonAllowsAt() {
        CreateUserRequest colon = new CreateUserRequest("bad:name", "U", "password123456", null,
            true, false, false, false, 1, null, -1, null, null, null, null);
        assertFalse(validator.validate(colon).isEmpty(), "colon not allowed in login name");
        CreateUserRequest at = new CreateUserRequest("@STB@", "U", "@REC@", null,
            true, false, false, false, 1, null, -1, null, null, null, null);
        assertTrue(validator.validate(at).isEmpty(), "@ is allowed in login name");
    }

    @Test
    void listenerDtoFullRoundtripWithAllFields() throws Exception {
        String json = """
            {
              "profileName": "test_profile",
              "protocol": "cccam",
              "port": 12000,
              "bindAddress": "0.0.0.0",
              "enabled": true,
              "desKeyRef": "file:/run/secrets/des",
              "allowList": "127.0.0.1,192.168.1.0/24",
              "denyList": "10.0.0.1",
              "cardMode": "wildcard",
              "strictPolicy": true,
              "reshareCap": 3,
              "maxCards": 512,
              "maxInflight": 32,
              "maxPerIp": 10,
              "nodeId": "0102030405060708",
              "idleTimeoutSec": 120,
              "version": 1
            }
            """;
        AdminConfigResource.ListenerDto dto = mapper.readValue(json, AdminConfigResource.ListenerDto.class);
        assertTrue(validator.validate(dto).isEmpty());
        assertEquals("test_profile", dto.profileName());
        assertEquals("cccam", dto.protocol());
        assertEquals(12000, dto.port());
        assertEquals("0.0.0.0", dto.bindAddress());
        assertTrue(dto.enabled());
        assertEquals("file:/run/secrets/des", dto.desKeyRef());
        assertEquals("127.0.0.1,192.168.1.0/24", dto.allowList());
        assertEquals("10.0.0.1", dto.denyList());
        assertEquals("wildcard", dto.cardMode());
        assertTrue(dto.strictPolicy());
        assertEquals(3, dto.reshareCap());
        assertEquals(512, dto.maxCards());
        assertEquals(32, dto.maxInflight());
        assertEquals(10, dto.maxPerIp());
        assertEquals("0102030405060708", dto.nodeId());
        assertEquals(120, dto.idleTimeoutSec());
        assertEquals(1L, dto.version());
    }

    @Test
    void connectorDtoFullRoundtripWithAllNewFields() throws Exception {
        String json = """
            {
              "profileName": "default",
              "protocol": "newcamd",
              "enabled": true,
              "debug": false,
              "host": "127.0.0.1",
              "port": 10000,
              "username": "cwsuser",
              "passwordRef": "file:/run/secrets/pass",
              "desKeyRef": "file:/run/secrets/des",
              "connectTimeoutMs": 5000,
              "reconnectDelayMs": 5000,
              "keepAliveSec": 60,
              "minDelayMs": 0,
              "maxQueue": 32,
              "newcamdMaxMsgSize": 512,
              "newcamdClientIdRef": null,
              "radegastResponseTimeoutMs": null,
              "cccamEnabled": false,
              "cccamNodeId": null,
              "cccamVersion": null,
              "cacheexMode": 0,
              "cacheexFilter": null,
              "cacheexMaxSend": null,
              "chameleonProfiles": null,
              "conaxReaderIndex": null,
              "priority": 50,
              "maxCapacity": 1000,
              "version": 1,
              "proxyName": null,
              "caidProfileMap": [],
              "cardDiscovery": true,
              "reshareGrant": 2,
              "newcamdDialect": "legacy",
              "reshareCap": 5,
              "newcamdPasswordEncoding": "md5crypt"
            }
            """;
        AdminConfigResource.ConnectorDto dto = mapper.readValue(json, AdminConfigResource.ConnectorDto.class);
        assertTrue(validator.validate(dto).isEmpty());
        assertTrue(dto.cardDiscovery());
        assertEquals(2, dto.reshareGrant());
        assertEquals("legacy", dto.newcamdDialect());
        assertEquals(5, dto.reshareCap());
        assertEquals("md5crypt", dto.newcamdPasswordEncoding());
    }

    @Test
    void connectorDtoInvalidPasswordEncodingRejected() throws Exception {
        String json = """
            {
              "profileName": "default",
              "protocol": "newcamd",
              "enabled": true,
              "debug": false,
              "host": "127.0.0.1",
              "port": 15000,
              "version": 1,
              "newcamdPasswordEncoding": "invalid_encoding"
            }
            """;
        AdminConfigResource.ConnectorDto dto = mapper.readValue(json, AdminConfigResource.ConnectorDto.class);
        assertFalse(validator.validate(dto).isEmpty());
    }
}
