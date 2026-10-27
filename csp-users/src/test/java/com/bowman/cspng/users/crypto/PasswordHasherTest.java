package com.bowman.cspng.users.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de hashage bcrypt.
 */
class PasswordHasherTest {

    static final PasswordHasher TEST_HASHER = new PasswordHasher(12);

    @Test
    void hashReturnsBcryptFormat() {
        String hash = TEST_HASHER.hash("testPassword123");
        assertNotNull(hash);
        assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$"),
            "Expected bcrypt hash, got: " + hash.substring(0, Math.min(10, hash.length())));
    }

    @Test
    void hashIsDifferentOnEachCall() {
        String h1 = TEST_HASHER.hash("testPassword123");
        String h2 = TEST_HASHER.hash("testPassword123");
        assertNotEquals(h1, h2, "Bcrypt salts should produce different hashes");
    }

    @Test
    void verifyReturnsTrueForMatchingPassword() {
        String plain = "myPassword456";
        String hash = TEST_HASHER.hash(plain);
        assertTrue(TEST_HASHER.verify(plain, hash));
    }

    @Test
    void verifyReturnsFalseForWrongPassword() {
        String hash = TEST_HASHER.hash("correctPassword");
        assertFalse(TEST_HASHER.verify("wrongPassword", hash));
    }

    @Test
    void hashRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> TEST_HASHER.hash(null));
    }

    @Test
    void hashRejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> TEST_HASHER.hash(""));
    }

    @Test
    void costDefaultIs12() {
        PasswordHasher h = new PasswordHasher();
        assertEquals(12, h.cost());
    }

    @Test
    void costBelow10IsClamped() {
        PasswordHasher h = new PasswordHasher(5);
        assertEquals(10, h.cost());
    }

    @Test
    void verifyLegacyMd5() {
        String plain = "legacyPass";
        String salt = "mySalt";
        String hash = MD5Crypt.crypt(plain, salt);
        assertTrue(TEST_HASHER.verify(plain, hash));
    }

    @Test
    void verifyRejectsLegacyMd5WrongPassword() {
        String plain = "legacyPass";
        String salt = "mySalt";
        String hash = MD5Crypt.crypt(plain, salt);
        assertFalse(TEST_HASHER.verify("wrongLegacyPass", hash));
    }
}
