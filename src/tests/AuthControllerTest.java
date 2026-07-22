package tests;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import logging.ActivityLog;
import server.auth.AuthController;
import server.auth.UserRepository;

class AuthControllerTest {

    @Test void testRegisterCommandCreatesAnAccountWithTheStartingRating(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());
        AuthController controller = new AuthController(repo, new ActivityLog(tempDir.resolve("log.txt").toString()));

        AuthController.Outcome outcome = controller.handleAuth("register alice hunter2");

        assertFalse(outcome.isMalformed());
        assertEquals("alice", outcome.getUsername());
        assertTrue(outcome.getResult().isSuccess());
        assertEquals(UserRepository.STARTING_RATING, outcome.getResult().getRating());
    }

    @Test void testLoginCommandAuthenticatesAgainstAnExistingAccount(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());
        repo.register("alice", "hunter2");
        AuthController controller = new AuthController(repo, new ActivityLog(tempDir.resolve("log.txt").toString()));

        AuthController.Outcome outcome = controller.handleAuth("login alice hunter2");

        assertFalse(outcome.isMalformed());
        assertTrue(outcome.getResult().isSuccess());
    }

    @Test void testLoginCommandFailsWithTheWrongPassword(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());
        repo.register("alice", "hunter2");
        AuthController controller = new AuthController(repo, new ActivityLog(tempDir.resolve("log.txt").toString()));

        AuthController.Outcome outcome = controller.handleAuth("login alice wrongpassword");

        assertFalse(outcome.isMalformed());
        assertFalse(outcome.getResult().isSuccess());
        assertEquals("wrong password", outcome.getResult().getFailureReason());
    }

    @Test void testAnUnknownCommandIsReportedAsMalformed(@TempDir Path tempDir) {
        UserRepository repo = new UserRepository(tempDir.resolve("users.db").toString());
        AuthController controller = new AuthController(repo, new ActivityLog(tempDir.resolve("log.txt").toString()));

        assertTrue(controller.handleAuth("play").isMalformed());
        assertTrue(controller.handleAuth("login onlyusername").isMalformed());
    }
}
