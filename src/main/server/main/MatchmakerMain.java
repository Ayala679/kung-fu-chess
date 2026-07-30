package server.main;

import io.nats.client.Connection;
import io.nats.client.Nats;

import logging.ActivityLog;
import server.controller.MatchmakerController;
import server.service.MatchmakerService;

/** Entry point for the Matchmaker process - reads env, wires MatchmakerService/MatchmakerController, starts. No logic of its own. */
public class MatchmakerMain {
    private static final String DEFAULT_LOG_PATH = "logs/matchmaker.log";

    public static void main(String[] args) {
        String logPath = System.getenv().getOrDefault("KFC_LOG_PATH", DEFAULT_LOG_PATH);
        String natsUrl = System.getenv("KFC_NATS_URL");
        String redisUrl = System.getenv("KFC_REDIS_URL"); // optional - null keeps the quick-match queue in-memory (see MatchmakerService)
        if (natsUrl == null) throw new IllegalArgumentException("KFC_NATS_URL is required - server.MatchmakerMain only runs in the distributed topology");

        ActivityLog activityLog = new ActivityLog(logPath);
        Connection natsConnection;
        try {
            natsConnection = Nats.connect(natsUrl);
        } catch (Exception e) {
            throw new IllegalStateException("Could not connect to NATS at " + natsUrl, e);
        }

        MatchmakerService service = new MatchmakerService(natsConnection, activityLog, redisUrl);
        MatchmakerController controller = new MatchmakerController(natsConnection, service, activityLog);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            controller.stop();
            try {
                natsConnection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        System.out.println("Kung Fu Chess Matchmaker ready, listening on NATS");
    }
}
