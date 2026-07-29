package server.controller;

import java.nio.charset.StandardCharsets;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;

import logging.ActivityLog;
import server.GatewayCommandEnvelope;
import server.service.MatchmakerService;

/**
 * The Matchmaker's endpoints: subscribes to {@code "matchmaker.play"} (a
 * {@link GatewayCommandEnvelope} {@code server.GameServerShardController}
 * forwards here whenever it sees a "play" command) and, independently, to
 * the very same {@code "gateway.disconnect"} subject {@code
 * server.GameServerShardController} already subscribes to (plain NATS
 * pub/sub naturally supports more than one subscriber per subject, so the
 * WS Gateway never has to know or care that two different services now care
 * about disconnects). Holds no decision logic of its own - every message is
 * decoded to its raw fields and handed to {@link MatchmakerService}.
 */
public class MatchmakerController {
    private final Dispatcher dispatcher;
    private final ActivityLog activityLog;

    public MatchmakerController(Connection natsConnection, MatchmakerService service, ActivityLog activityLog) {
        this.activityLog = activityLog;
        this.dispatcher = natsConnection.createDispatcher();
        dispatcher.subscribe("matchmaker.play", msg -> handlePlay(service, new String(msg.getData(), StandardCharsets.UTF_8)));
        dispatcher.subscribe("gateway.disconnect", msg -> handleDisconnect(service, new String(msg.getData(), StandardCharsets.UTF_8)));
    }

    private void handlePlay(MatchmakerService service, String encoded) {
        try {
            GatewayCommandEnvelope envelope = GatewayCommandEnvelope.decode(encoded);
            service.handlePlay(envelope.connectionId(), envelope.username(), envelope.rating());
        } catch (RuntimeException e) {
            activityLog.log("handlePlay(\"" + encoded + "\") failed unexpectedly: " + e);
        }
    }

    private void handleDisconnect(MatchmakerService service, String connectionId) {
        try {
            service.handleDisconnect(connectionId);
        } catch (RuntimeException e) {
            activityLog.log("handleDisconnect(\"" + connectionId + "\") failed unexpectedly: " + e);
        }
    }

    public void stop() {
        dispatcher.unsubscribe("matchmaker.play");
        dispatcher.unsubscribe("gateway.disconnect");
    }
}
