package tests;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import logging.ActivityLog;
import server.auth.AuthService;
import server.auth.UserRepository;

class AuthServiceTest {

    @Test void testRegisterCommandCreatesAnAccountWithTheStartingRating(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());
        AuthService service = new AuthService(repo, new ActivityLog(tempDir.resolve("log.txt").toString()));

        AuthService.Outcome outcome = service.handleAuth("register alice hunter2");

        assertFalse(outcome.isMalformed());
        assertEquals("alice", outcome.getUsername());
        assertTrue(outcome.getResult().isSuccess());
        assertEquals(UserRepository.STARTING_RATING, outcome.getResult().getRating());
    }

    @Test void testLoginCommandAuthenticatesAgainstAnExistingAccount(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());
        repo.register("alice", "hunter2");
        AuthService service = new AuthService(repo, new ActivityLog(tempDir.resolve("log.txt").toString()));

        AuthService.Outcome outcome = service.handleAuth("login alice hunter2");

        assertFalse(outcome.isMalformed());
        assertTrue(outcome.getResult().isSuccess());
    }

    @Test void testLoginCommandFailsWithTheWrongPassword(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());
        repo.register("alice", "hunter2");
        AuthService service = new AuthService(repo, new ActivityLog(tempDir.resolve("log.txt").toString()));

        AuthService.Outcome outcome = service.handleAuth("login alice wrongpassword");

        assertFalse(outcome.isMalformed());
        assertFalse(outcome.getResult().isSuccess());
        assertEquals("wrong password", outcome.getResult().getFailureReason());
    }

    @Test void testAnUnknownCommandIsReportedAsMalformed(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());
        AuthService service = new AuthService(repo, new ActivityLog(tempDir.resolve("log.txt").toString()));

        assertTrue(service.handleAuth("play").isMalformed());
        assertTrue(service.handleAuth("login onlyusername").isMalformed());
    }
}
