package com.bowman.cspng.users.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de vérification bcrypt + MD5 legacy.
 */
class PasswordVerifierBcryptTest {

    @Test
    void bcryptVerifyMatchesCorrectPassword() {
        String plain = "mySecurePassword123";
        String hash = PasswordHasherTest.TEST_HASHER.hash(plain);
        assertTrue(PasswordVerifier.verify(plain, hash));
    }

    @Test
    void bcryptVerifyRejectsWrongPassword() {
        String plain = "mySecurePassword123";
        String wrong = "wrongPassword456";
        String hash = PasswordHasherTest.TEST_HASHER.hash(plain);
        assertFalse(PasswordVerifier.verify(wrong, hash));
    }

    @Test
    void bcryptVerifyRejectsNullPassword() {
        assertFalse(PasswordVerifier.verify(null, "$2a$12$..."));
    }

    @Test
    void bcryptVerifyRejectsNullHash() {
        assertFalse(PasswordVerifier.verify("password", null));
    }

    @Test
    void legacyMd5StillWorks() {
        // Golden vector from legacy test
        String plain = "testPassword123";
        String salt = "salt1234";
        String hash = MD5Crypt.crypt(plain, salt);
        assertTrue(PasswordVerifier.verify(plain, hash));
    }

    @Test
    void legacyMd5RejectsWrongPassword() {
        String plain = "testPassword123";
        String salt = "salt1234";
        String hash = MD5Crypt.crypt(plain, salt);
        assertFalse(PasswordVerifier.verify("wrongPassword", hash));
    }

    @Test
    void argon2idFailsClosed() {
        String hash = "$argon2id$v=19$m=65536,t=3,p=4$c2FsdHlzYWx0$hashvalue";
        assertFalse(PasswordVerifier.verify("anything", hash));
    }
}
