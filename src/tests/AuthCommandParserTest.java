package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import server.auth.AuthCommandParser;

class AuthCommandParserTest {

    @Test void testParsesALoginCommand() {
        AuthCommandParser.ParsedCommand parsed = AuthCommandParser.parse("login alice hunter2");

        assertNotNull(parsed);
        assertEquals(AuthCommandParser.Mode.LOGIN, parsed.mode());
        assertEquals("alice", parsed.username());
        assertEquals("hunter2", parsed.password());
    }

    @Test void testParsesARegisterCommand() {
        AuthCommandParser.ParsedCommand parsed = AuthCommandParser.parse("register bob pw");

        assertNotNull(parsed);
        assertEquals(AuthCommandParser.Mode.REGISTER, parsed.mode());
        assertEquals("bob", parsed.username());
        assertEquals("pw", parsed.password());
    }

    @Test void testPasswordMayContainSpaces() {
        // split(..., 3) - only the first two tokens are command/username, everything else is the password.
        AuthCommandParser.ParsedCommand parsed = AuthCommandParser.parse("login alice a password with spaces");

        assertNotNull(parsed);
        assertEquals("a password with spaces", parsed.password());
    }

    @Test void testUnknownVerbIsRejected() {
        assertNull(AuthCommandParser.parse("play"));
    }

    @Test void testMissingPasswordIsRejected() {
        assertNull(AuthCommandParser.parse("login onlyusername"));
    }

    @Test void testEmptyMessageIsRejected() {
        assertNull(AuthCommandParser.parse(""));
    }
}
