package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import server.auth.InMemorySessionTokenStore;
import server.auth.SessionTokenStore;

class InMemorySessionTokenStoreTest {

    @Test void testIssuedTokenValidatesToThePrincipalItWasIssuedFor() {
        SessionTokenStore store = new InMemorySessionTokenStore();
        String token = store.issue("alice", 1200);

        var principal = store.validate(token);

        assertTrue(principal.isPresent());
        assertEquals("alice", principal.get().username());
        assertEquals(1200, principal.get().rating());
    }

    @Test void testUnknownTokenDoesNotValidate() {
        SessionTokenStore store = new InMemorySessionTokenStore();

        assertTrue(store.validate("nope-not-a-real-token").isEmpty());
    }

    // Regression test for the "polite" release path (see server.Protocol.LOGOUT) added
    // alongside the token's own TTL - previously a token could only ever expire, never
    // be explicitly given up early.
    @Test void testRevokedTokenNoLongerValidates() {
        SessionTokenStore store = new InMemorySessionTokenStore();
        String token = store.issue("alice", 1200);

        store.revoke(token);

        assertTrue(store.validate(token).isEmpty());
    }

    @Test void testRevokingAnUnknownTokenIsSafe() {
        SessionTokenStore store = new InMemorySessionTokenStore();

        assertDoesNotThrow(() -> store.revoke("never-issued"));
    }
}
