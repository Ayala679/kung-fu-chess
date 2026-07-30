package server.controller;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;

import logging.ActivityLog;
import server.GameAllocatorClient;
import server.service.GameAllocatorService;

/**
 * The Game Allocator's endpoint: one NATS request-reply subject,
 * {@link GameAllocatorClient#SUBJECT}, mirroring {@link
 * GameServerShardController}'s own create_room request-reply shape exactly. Holds no
 * decision logic of its own - every request is answered by delegating to
 * {@link GameAllocatorService#assignShard()}. The two callers that actually
 * establish new rooms - {@link RemoteRoomCreator} (REST) and {@link
 * server.MatchmakerService} (quick-match) - ask it via {@link
 * GameAllocatorClient#assignShard}, then send their real room-establishing
 * request straight to that shard's own subject.
 *
 * Takes an already-connected {@link Connection} rather than connecting
 * itself from a raw URL - matching {@link MatchmakerController}/{@link
 * GameServerShardController} (both wired by their own {@code XxxMain}, which
 * owns the connection's lifecycle) - since as of "Not done yet"'s load-aware
 * allocation, {@link GameAllocatorService} itself also needs that same
 * connection to query shard load, so the connection has to be established
 * before either object exists.
 */
public class GameAllocatorController {
    private final Dispatcher dispatcher;
    // assignShard() (see GameAllocatorService) can take up to LOAD_QUERY_TIMEOUT even in the
    // healthy case - handling each "allocator.assign" request on its own executor thread, rather
    // than inline on the single NATS dispatcher thread, lets concurrent room-allocation requests
    // (from different rooms being created/matched at once) actually run in parallel instead of
    // queuing up behind one another.
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ActivityLog activityLog;

    public GameAllocatorController(Connection natsConnection, GameAllocatorService service, ActivityLog activityLog) {
        this.activityLog = activityLog;
        this.dispatcher = natsConnection.createDispatcher(msg -> executor.submit(() -> {
            try {
                String shardId = service.assignShard();
                natsConnection.publish(msg.getReplyTo(), shardId.getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                activityLog.log("allocator.assign failed unexpectedly: " + e);
            }
        }));
        dispatcher.subscribe(GameAllocatorClient.SUBJECT);
    }

    public void stop() {
        dispatcher.unsubscribe(GameAllocatorClient.SUBJECT);
        executor.shutdown();
    }
}
