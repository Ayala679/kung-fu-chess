package server.controller;

import java.nio.charset.StandardCharsets;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;

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
 */
public class GameAllocatorController {
    private final Connection natsConnection;
    private final Dispatcher dispatcher;

    public GameAllocatorController(String natsUrl, GameAllocatorService service) {
        if (natsUrl == null) throw new IllegalArgumentException("KFC_NATS_URL is required - server.GameAllocatorController only runs in the distributed topology");

        try {
            this.natsConnection = Nats.connect(natsUrl);
        } catch (Exception e) {
            throw new IllegalStateException("Could not connect to NATS at " + natsUrl, e);
        }
        this.dispatcher = natsConnection.createDispatcher(msg -> {
            String shardId = service.assignShard();
            natsConnection.publish(msg.getReplyTo(), shardId.getBytes(StandardCharsets.UTF_8));
        });
        dispatcher.subscribe(GameAllocatorClient.SUBJECT);
    }

    public void stop() throws InterruptedException {
        dispatcher.unsubscribe(GameAllocatorClient.SUBJECT);
        natsConnection.close();
    }
}
