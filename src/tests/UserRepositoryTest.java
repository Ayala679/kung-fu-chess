package tests;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import server.auth.UserRepository;

class UserRepositoryTest {

    @Test void testRegisterThenAuthenticateSucceedsWithTheStartingRating(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());

        UserRepository.AuthResult registered = repo.register("alice", "hunter2");
        assertTrue(registered.isSuccess());
        assertEquals(UserRepository.STARTING_RATING, registered.getRating());

        UserRepository.AuthResult loggedIn = repo.authenticate("alice", "hunter2");
        assertTrue(loggedIn.isSuccess());
        assertEquals(UserRepository.STARTING_RATING, loggedIn.getRating());
    }

    @Test void testRegisterRejectsAnAlreadyTakenUsername(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());

        repo.register("alice", "hunter2");
        UserRepository.AuthResult second = repo.register("alice", "somethingElse");

        assertFalse(second.isSuccess());
        assertEquals("username taken", second.getFailureReason());
    }

    @Test void testAuthenticateRejectsAnUnknownUsername(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());

        UserRepository.AuthResult result = repo.authenticate("nobody", "whatever");

        assertFalse(result.isSuccess());
        assertEquals("unknown username", result.getFailureReason());
    }

    @Test void testAuthenticateRejectsTheWrongPassword(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());
        repo.register("alice", "hunter2");

        UserRepository.AuthResult result = repo.authenticate("alice", "wrongpassword");

        assertFalse(result.isSuccess());
        assertEquals("wrong password", result.getFailureReason());
    }

    @Test void testUpdateRatingPersistsAndIsReturnedOnNextAuthenticate(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());
        repo.register("alice", "hunter2");

        repo.updateRating("alice", 1234);

        UserRepository.AuthResult loggedIn = repo.authenticate("alice", "hunter2");
        assertEquals(1234, loggedIn.getRating());
    }

    @Test void testAccountsSurviveReopeningTheSameDatabaseFile(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("users.db").toString();
        new UserRepository(dbPath).register("alice", "hunter2");

        UserRepository reopened = new UserRepository(dbPath);
        UserRepository.AuthResult result = reopened.authenticate("alice", "hunter2");

        assertTrue(result.isSuccess());
    }
}
