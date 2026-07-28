package server;

import java.io.IOException;

import io.nats.client.Connection;
import io.nats.client.Nats;

import logging.ActivityLog;
import server.auth.AuthController;
import server.auth.RedisSessionTokenStore;
import server.auth.SessionTokenStore;
import server.auth.UserRepository;

/**
 * The API Gateway, standalone: everything non-realtime (login, register,
 * room creation - see HttpApiServer) as its own process, separate from the
 * WebSocket Gateway / Game Server Shard (KungFuChessServer). Only makes
 * sense in the distributed (Docker Compose) topology - see
 * Server_Design.md's "Step 4" - so unlike KungFuChessServer, KFC_REDIS_URL
 * and KFC_NATS_URL are required here, not optional: this class has no
 * local/offline fallback mode of its own. Auth (UserRepository/
 * AuthController/SessionTokenStore) works identically to how
 * KungFuChessServer already does it - Postgres and Redis are already
 * genuine shared, multi-process-safe stores, nothing new. Room creation is
 * the one thing that can't be done locally - see RemoteRoomCreator, which
 * sends a real NATS request to whichever process owns the Lobby
 * (RoomCreationResponder, inside KungFuChessServer).
 */
public class ApiGateway {
    private static final int DEFAULT_HTTP_PORT = 8888;
    private static final String DEFAULT_DB_PATH = "data/kungfuchess.db";
    private static final String DEFAULT_LOG_PATH = "logs/api-gateway.log";

    private final HttpApiServer httpApiServer;
    private final Connection natsConnection;

    public ApiGateway(int httpPort, String dbUrlOrPath, String logPath, String redisUrl, String natsUrl) {
        if (redisUrl == null) throw new IllegalArgumentException("KFC_REDIS_URL is required - server.ApiGateway only runs in the distributed topology");
        if (natsUrl == null) throw new IllegalArgumentException("KFC_NATS_URL is required - server.ApiGateway only runs in the distributed topology");

        UserRepository userRepository = new UserRepository(dbUrlOrPath);
        ActivityLog activityLog = new ActivityLog(logPath);
        AuthController authController = new AuthController(userRepository, activityLog);
        SessionTokenStore sessionTokenStore = new RedisSessionTokenStore(redisUrl);

        try {
            this.natsConnection = Nats.connect(natsUrl);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not connect to NATS at " + natsUrl, e);
        }
        RoomCreator roomCreator = new RemoteRoomCreator(natsConnection);

        try {
            this.httpApiServer = new HttpApiServer(httpPort, authController, sessionTokenStore, roomCreator, activityLog);
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

    public static void main(String[] args) {
        int httpPort = envInt("KFC_HTTP_PORT", DEFAULT_HTTP_PORT);
        String dbUrlOrPath = System.getenv().getOrDefault("KFC_DB_URL", DEFAULT_DB_PATH);
        String logPath = System.getenv().getOrDefault("KFC_LOG_PATH", DEFAULT_LOG_PATH);
        String redisUrl = System.getenv("KFC_REDIS_URL");
        String natsUrl = System.getenv("KFC_NATS_URL");

        ApiGateway gateway = new ApiGateway(httpPort, dbUrlOrPath, logPath, redisUrl, natsUrl);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                gateway.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        gateway.start();
    }

    private static int envInt(String name, int defaultValue) {
        String value = System.getenv(name);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }
}
