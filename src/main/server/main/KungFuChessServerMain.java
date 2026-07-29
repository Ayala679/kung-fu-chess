package server.main;

import server.controller.KungFuChessServerController;
import server.service.KungFuChessServerService;

/** Entry point for the WS Gateway process - reads env, wires KungFuChessServerService/KungFuChessServerController, starts. No logic of its own. */
public class KungFuChessServerMain {
    public static final int DEFAULT_PORT = 8887;
    public static final int DEFAULT_HTTP_PORT = 8888;
    private static final String DEFAULT_DB_PATH = "data/kungfuchess.db";
    private static final String DEFAULT_LOG_PATH = "logs/server.log";

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : envInt("KFC_WS_PORT", DEFAULT_PORT);
        int httpPort = envInt("KFC_HTTP_PORT", DEFAULT_HTTP_PORT);
        String dbUrlOrPath = System.getenv().getOrDefault("KFC_DB_URL", DEFAULT_DB_PATH);
        String logPath = System.getenv().getOrDefault("KFC_LOG_PATH", DEFAULT_LOG_PATH);
        String redisUrl = System.getenv("KFC_REDIS_URL");
        String natsUrl = System.getenv("KFC_NATS_URL");

        KungFuChessServerService service = new KungFuChessServerService(httpPort, dbUrlOrPath, logPath, redisUrl, natsUrl);
        KungFuChessServerController controller = new KungFuChessServerController(port, service);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                controller.stopAll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        controller.start();
    }

    private static int envInt(String name, int defaultValue) {
        String value = System.getenv(name);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }
}
