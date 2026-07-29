package server.service;

import java.io.IOException;

import io.nats.client.Connection;
import io.nats.client.Nats;

import logging.ActivityLog;
import server.auth.AuthService;
import server.auth.RedisSessionTokenStore;
import server.auth.SessionTokenStore;
import server.auth.UserRepository;
import server.controller.HttpApiServer;
import server.room.RemoteRoomCreator;
import server.room.RoomCreator;

/**
 * The API Gateway's wiring: everything non-realtime (login, register, room
 * creation - see HttpApiServer, the actual REST endpoint layer) as its own
 * process - see {@code server.main.ApiGatewayMain} for the entry point. Only makes
 * sense in the distributed (Docker Compose) topology - see
 * Server_Design.md's "Step 4" - so unlike KungFuChessServerService, KFC_REDIS_URL
 * and KFC_NATS_URL are required here, not optional: this class has no
 * local/offline fallback mode of its own. Auth (UserRepository/
 * AuthService/SessionTokenStore) works identically to how
 * KungFuChessServerService already does it - Postgres and Redis are already
 * genuine shared, multi-process-safe stores, nothing new. Room creation is
 * the one thing that can't be done locally - see RemoteRoomCreator, which
 * sends a real NATS request to whichever process owns the Lobby (a
 * GameServerShardController, handled inside GameServerShardMain).
 */
public class ApiGateway {
    private final HttpApiServer httpApiServer;
    private final Connection natsConnection;

    public ApiGateway(int httpPort, String dbUrlOrPath, String logPath, String redisUrl, String natsUrl) {
        if (redisUrl == null) throw new IllegalArgumentException("KFC_REDIS_URL is required - server.ApiGateway only runs in the distributed topology");
        if (natsUrl == null) throw new IllegalArgumentException("KFC_NATS_URL is required - server.ApiGateway only runs in the distributed topology");

        UserRepository userRepository = new UserRepository(dbUrlOrPath);
        ActivityLog activityLog = new ActivityLog(logPath);
        AuthService authService = new AuthService(userRepository, activityLog);
        SessionTokenStore sessionTokenStore = new RedisSessionTokenStore(redisUrl);

        try {
            this.natsConnection = Nats.connect(natsUrl);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not connect to NATS at " + natsUrl, e);
        }
        RoomCreator roomCreator = new RemoteRoomCreator(natsConnection);

        try {
            this.httpApiServer = new HttpApiServer(httpPort, authService, sessionTokenStore, roomCreator, activityLog);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start HTTP API server on port " + httpPort, e);
        }
    }

    public void start() {
        httpApiServer.start();
        System.out.println("Kung Fu Chess API Gateway listening on port " + httpApiServer.getPort());
    }

    public void stop() throws InterruptedException {
        httpApiServer.stop();
        natsConnection.close();
    }
}
