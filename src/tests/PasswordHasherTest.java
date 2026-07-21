package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import server.auth.PasswordHasher;

class PasswordHasherTest {
    @Test void testMatchesReturnsTrueForTheCorrectPassword() {
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash("hunter2", salt);
        assertTrue(PasswordHasher.matches("hunter2", salt, hash));
    }

    @Test void testMatchesReturnsFalseForTheWrongPassword() {
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash("hunter2", salt);
        assertFalse(PasswordHasher.matches("wrongpassword", salt, hash));
    }

    @Test void testSameSaltAndPasswordAlwaysHashTheSameWay() {
        String salt = PasswordHasher.newSalt();
        assertEquals(PasswordHasher.hash("hunter2", salt), PasswordHasher.hash("hunter2", salt));
    }

    @Test void testDifferentSaltsProduceDifferentHashesForTheSamePassword() {
        String saltA = PasswordHasher.newSalt();
        String saltB = PasswordHasher.newSalt();
        assertNotEquals(saltA, saltB);
        assertNotEquals(PasswordHasher.hash("hunter2", saltA), PasswordHasher.hash("hunter2", saltB));
    }

    @Test void testNewSaltIsNotConstant() {
        assertNotEquals(PasswordHasher.newSalt(), PasswordHasher.newSalt());
    }
}
