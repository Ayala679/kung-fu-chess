package server;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.nats.client.Connection;
import io.nats.client.Message;

/**
 * Small shared helper for asking the standalone {@code server.GameAllocatorController}
 * process which shard a brand-new room should go to - used by both {@link
 * RemoteRoomCreator} (REST room creation) and {@link MatchmakerService} (quick-match
 * seating), so the NATS request-reply boilerplate isn't duplicated. Mirrors
 * {@link RemoteRoomCreator}'s own request-reply shape.
 */
public final class GameAllocatorClient {
    public static final String SUBJECT = "allocator.assign";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private GameAllocatorClient() {}

    /** @throws IllegalStateException if the Game Allocator didn't respond in time. */
    public static String assignShard(Connection connection) {
        Message reply;
        try {
            reply = connection.request(SUBJECT, new byte[0], TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Game Allocator to assign a shard", e);
        }
        if (reply == null) {
            throw new IllegalStateException("Game Allocator did not respond within " + TIMEOUT);
        }
        return new String(reply.getData(), StandardCharsets.UTF_8);
    }
}
