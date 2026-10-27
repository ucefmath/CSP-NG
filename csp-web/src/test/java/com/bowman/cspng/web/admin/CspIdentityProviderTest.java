package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour CspIdentityProvider.
 */
class CspIdentityProviderTest {

    private InMemoryUserDirectory directory;
    private CspIdentityProvider provider;

    @BeforeEach
    void setUp() {
        directory = new InMemoryUserDirectory();
        provider = new CspIdentityProvider(directory);
    }

    private UsernamePasswordAuthenticationRequest request(String user, String pass) {
        return new UsernamePasswordAuthenticationRequest(user,
            new io.quarkus.security.credential.PasswordCredential(pass.toCharArray()));
    }

    @Test
    void authenticateSuccessAdmin() throws Exception {
        directory.addWithPlainPassword("admin", "secretPass99", true, true, 5);
        SecurityIdentity identity = provider.authenticate(request("admin", "secretPass99"), null)
            .await().atMost(java.time.Duration.ofSeconds(5));
        assertNotNull(identity);
        assertTrue(identity.hasRole("user"));
        assertTrue(identity.hasRole("admin"));
        assertEquals("admin", identity.getPrincipal().getName());
    }

    @Test
    void authenticateSuccessNonAdmin() throws Exception {
        directory.addWithPlainPassword("user1", "userPass12", true, false, 1);
        SecurityIdentity identity = provider.authenticate(request("user1", "userPass12"), null)
            .await().atMost(java.time.Duration.ofSeconds(5));
        assertNotNull(identity);
        assertTrue(identity.hasRole("user"));
        assertFalse(identity.hasRole("admin"));
    }

    @Test
    void authenticateFailsWrongPassword() {
        directory.addWithPlainPassword("admin", "correctPass", true, true, 5);
        assertThrows(AuthenticationFailedException.class, () ->
            provider.authenticate(request("admin", "wrongPass"), null)
                .await().atMost(java.time.Duration.ofSeconds(5))
        );
    }

    @Test
    void authenticateFailsUserNotFound() {
        assertThrows(AuthenticationFailedException.class, () ->
            provider.authenticate(request("nonexistent", "password"), null)
                .await().atMost(java.time.Duration.ofSeconds(5))
        );
    }

    @Test
    void authenticateFailsDisabledUser() throws Exception {
        directory.addWithPlainPassword("disabled", "password1", false, true, 1);
        assertThrows(AuthenticationFailedException.class, () ->
            provider.authenticate(request("disabled", "password1"), null)
                .await().atMost(java.time.Duration.ofSeconds(5))
        );
    }

    @Test
    void getRequestTypeReturnsBasicAuth() {
        assertEquals(UsernamePasswordAuthenticationRequest.class, provider.getRequestType());
    }
}
