package server.controller;

import java.nio.charset.StandardCharsets;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;

import logging.ActivityLog;
import server.GatewayCommandEnvelope;
import server.SeatPairEnvelope;
import server.room.RemoteRoomCreator;
import server.service.GameServerShardService;

/**
 * A Game Server Shard's endpoints, standalone: never sees a real {@code
 * WebSocket} - every command arrives from a separate {@code
 * KungFuChessServerController} (WS Gateway) process as a plain NATS message
 * carrying a {@code connectionId} (see {@link GatewayCommandEnvelope}), and
 * every reply/broadcast goes back out through a {@link
 * RemoteOutboundConnection} for that same connectionId, published on
 * {@code "conn.<connectionId>.out"} - the Gateway is already subscribed to
 * it and relays verbatim to the real socket. Holds no decision logic of its
 * own - every message is decoded to its raw fields and handed to {@link
 * GameServerShardService}.
 *
 * As of "Step 6a", more than one shard process can run at once, each with
 * its own identity (KFC_SHARD_ID) - see Server_Design.md's "Step 6a" for
 * the full routing picture (why every subject below is per-shard except
 * reconnect/disconnect, and how the WS Gateway learns which shard owns a
 * connection). Reconnect/disconnect stay broadcast to every shard - a
 * non-owning shard's {@code Lobby.tryReconnect}/{@code handleDisconnect}
 * simply finds nothing local and no-ops, since {@code ReconnectRegistry} is
 * Redis-shared but {@code Lobby.rooms} is not, so only the true owner ever
 * matches.
 */
public class GameServerShardController {
    private final Dispatcher dispatcher;
    private final ActivityLog activityLog;
    private final String shardId;
    private final GameServerShardService service;

    public GameServerShardController(Connection natsConnection, GameServerShardService service, String shardId, ActivityLog activityLog) {
        this.activityLog = activityLog;
        this.shardId = shardId;
        this.service = service;
        this.dispatcher = natsConnection.createDispatcher();
        dispatcher.subscribe("shard." + shardId + ".command", msg -> handleCommand(new String(msg.getData(), StandardCharsets.UTF_8)));
        dispatcher.subscribe("gateway.reconnect", msg -> handleReconnect(new String(msg.getData(), StandardCharsets.UTF_8)));
        dispatcher.subscribe("gateway.disconnect", msg -> handleDisconnect(new String(msg.getData(), StandardCharsets.UTF_8)));
        dispatcher.subscribe("shard." + shardId + ".seat_pair", msg -> handleSeatPair(new String(msg.getData(), StandardCharsets.UTF_8)));
        dispatcher.subscribe(RemoteRoomCreator.subjectFor(shardId), msg -> handleCreateRoom(natsConnection, msg));
        dispatcher.subscribe(loadSubject(shardId), msg -> handleLoadQuery(natsConnection, msg));
        dispatcher.subscribe(checkoutSubject(shardId), msg -> handleCheckout(natsConnection, msg));
    }

    /** The subject GameAllocatorService requests on to ask this shard's current load - see handleLoadQuery. */
    public static String loadSubject(String shardId) {
        return "shard." + shardId + ".load";
    }

    /** The subject KungFuChessServerService requests on before letting a connection currently associated with this shard send a fresh "play"/"join_room" - see handleCheckout. */
    public static String checkoutSubject(String shardId) {
        return "shard." + shardId + ".checkout";
    }

    // Empty request, replies with this shard's own Lobby.activeGameCount() as plain text - the load-aware half of "Step 6a" (see Server_Design.md's "Not done yet").
    private void handleLoadQuery(Connection natsConnection, Message msg) {
        try {
            String count = String.valueOf(service.activeGameCount());
            natsConnection.publish(msg.getReplyTo(), count.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // No reply at all here would leave GameAllocatorService waiting out the
            // full LOAD_QUERY_TIMEOUT with nothing in this shard's own log explaining
            // why - logging it (matching every sibling handler in this class) at
            // least makes that failure diagnosable instead of silent.
            activityLog.log("load query failed unexpectedly: " + e);
        }
    }

    private void handleCommand(String encoded) {
        try {
            GatewayCommandEnvelope envelope = GatewayCommandEnvelope.decode(encoded);
            service.handleCommand(envelope);
        } catch (RuntimeException e) {
            activityLog.log("handleCommand(\"" + encoded + "\") failed unexpectedly: " + e);
        }
    }

    private void handleSeatPair(String encoded) {
        try {
            service.handleSeatPair(SeatPairEnvelope.decode(encoded));
        } catch (RuntimeException e) {
            activityLog.log("handleSeatPair(\"" + encoded + "\") failed unexpectedly: " + e);
        }
    }

    // "connectionId|username".
    private void handleReconnect(String encoded) {
        try {
            String[] parts = encoded.split("\\|", 2);
            service.handleReconnect(parts[0], parts[1]);
        } catch (RuntimeException e) {
            activityLog.log("handleReconnect(\"" + encoded + "\") failed unexpectedly: " + e);
        }
    }

    // payload is just the connectionId.
    private void handleDisconnect(String connectionId) {
        try {
            service.handleDisconnect(connectionId);
        } catch (RuntimeException e) {
            activityLog.log("handleDisconnect(\"" + connectionId + "\") failed unexpectedly: " + e);
        }
    }

    // The other end of RemoteRoomCreator - already routed to this specific shard by GameAllocatorClient.
    private void handleCreateRoom(Connection natsConnection, Message msg) {
        try {
            String username = new String(msg.getData(), StandardCharsets.UTF_8);
            String code = service.createRoom(username);
            natsConnection.publish(msg.getReplyTo(), code.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // No reply here leaves RemoteRoomCreator waiting out its full 5s timeout
            // with nothing logged anywhere explaining why - log it, matching every
            // sibling handler in this class, instead of failing silently.
            activityLog.log("create_room failed unexpectedly: " + e);
        }
    }

    // Payload is the connectionId; replies "1" (may proceed - see checkoutForNewGame) or "0" (still actively playing here).
    private void handleCheckout(Connection natsConnection, Message msg) {
        try {
            String connectionId = new String(msg.getData(), StandardCharsets.UTF_8);
            boolean canLeave = service.checkoutForNewGame(connectionId);
            natsConnection.publish(msg.getReplyTo(), (canLeave ? "1" : "0").getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            activityLog.log("checkout failed unexpectedly: " + e);
        }
    }

    public void stop() {
        dispatcher.unsubscribe("shard." + shardId + ".command");
        dispatcher.unsubscribe("gateway.reconnect");
        dispatcher.unsubscribe("gateway.disconnect");
        dispatcher.unsubscribe("shard." + shardId + ".seat_pair");
        dispatcher.unsubscribe(RemoteRoomCreator.subjectFor(shardId));
        dispatcher.unsubscribe(loadSubject(shardId));
        dispatcher.unsubscribe(checkoutSubject(shardId));
    }
}
