package server.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import logging.ActivityLog;
import server.Protocol;
import server.auth.AuthService;
import server.auth.SessionTokenStore;
import server.room.RoomCreationException;
import server.room.RoomCreator;

/**
 * The REST half of the wire protocol - everything that isn't realtime
 * (login, register, room creation - see Protocol's class comment) sits here
 * instead of on the WebSocket. Built on the JDK's own com.sun.net.httpserver
 * rather than adding a web-framework dependency, matching this project's
 * existing "avoid new dependencies for a small text protocol" choices
 * (SnapshotCodec, ActivityLog). Runs embedded in the same process/JVM as
 * KungFuChessServerService for local/offline runs, or standalone as its own
 * process (server.ApiGateway) in the Docker Compose deployment - see
 * Server_Design.md's "Step 4" and server.RoomCreator for how room creation
 * is made to work either way.
 */
public class HttpApiServer {
    private final HttpServer httpServer;

    public HttpApiServer(int port, AuthService authService, SessionTokenStore sessionTokenStore,
                          RoomCreator roomCreator, ActivityLog activityLog) throws IOException {
        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.createContext("/api/login", authHandler(Protocol.LOGIN, authService, sessionTokenStore, activityLog));
        httpServer.createContext("/api/register", authHandler(Protocol.REGISTER, authService, sessionTokenStore, activityLog));
        httpServer.createContext("/api/rooms", roomsHandler(sessionTokenStore, roomCreator, activityLog));
    }

    public void start() {
        httpServer.start();
    }

    public void stop() {
        httpServer.stop(0);
    }

    public int getPort() {
        return httpServer.getAddress().getPort();
    }

    private static HttpHandler authHandler(String mode, AuthService authService,
                                            SessionTokenStore sessionTokenStore, ActivityLog activityLog) {
        return exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Protocol.ERROR + "|expected POST");
                return;
            }
            Map<String, String> form = parseForm(readBody(exchange));
            String username = form.getOrDefault("username", "");
            String password = form.getOrDefault("password", "");

            AuthService.Outcome outcome = authService.handleAuth(mode + " " + username + " " + password);
            if (outcome.isMalformed()) {
                respond(exchange, 400, Protocol.ERROR + "|expected username and password");
                return;
            }

            var result = outcome.getResult();
            if (!result.isSuccess()) {
                respond(exchange, 401, Protocol.ERROR + "|" + result.getFailureReason());
                return;
            }

            String token = sessionTokenStore.issue(outcome.getUsername(), result.getRating());
            activityLog.log("HTTP " + mode + " ok for " + outcome.getUsername());
            respond(exchange, 200, Protocol.AUTH_OK + "|" + token + "|" + result.getRating());
        };
    }

    private static HttpHandler roomsHandler(SessionTokenStore sessionTokenStore, RoomCreator roomCreator, ActivityLog activityLog) {
        return exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Protocol.ERROR + "|expected POST");
                return;
            }
            Map<String, String> form = parseForm(readBody(exchange));
            Optional<SessionTokenStore.Principal> principal = sessionTokenStore.validate(form.getOrDefault("token", ""));
            if (principal.isEmpty()) {
                respond(exchange, 401, Protocol.ERROR + "|invalid or expired token");
                return;
            }

            String username = principal.get().username();
            String code;
            try {
                code = roomCreator.createRoom(username);
            } catch (RoomCreationException e) {
                activityLog.log("HTTP create_room failed for " + username + ": " + e.getMessage());
                respond(exchange, 503, Protocol.ERROR + "|room service unavailable");
                return;
            }
            activityLog.log("HTTP create_room ok for " + username + " -> room=" + code);
            respond(exchange, 200, Protocol.ROOM_CREATED + "|" + code);
        };
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    // Minimal application/x-www-form-urlencoded parser - no servlet/web dependency for a two-field body.
    private static Map<String, String> parseForm(String body) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            fields.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return fields;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
