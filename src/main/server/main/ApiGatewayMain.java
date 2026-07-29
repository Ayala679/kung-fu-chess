package server.main;

import server.service.ApiGateway;

/** Entry point for the API Gateway process - reads env, wires ApiGateway, starts. No logic of its own. */
public class ApiGatewayMain {
    private static final int DEFAULT_HTTP_PORT = 8888;
    private static final String DEFAULT_DB_PATH = "data/kungfuchess.db";
    private static final String DEFAULT_LOG_PATH = "logs/api-gateway.log";

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
