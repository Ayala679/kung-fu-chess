package server;

/**
 * Internal service-to-service envelope for a WS command the Gateway
 * forwards to the Game Server Shard over NATS (subject "gateway.command")
 * - not part of Protocol.java, which is the client-facing wire protocol,
 * not this internal one. {@code rawCommand} is whatever the client itself
 * sent ("play", "join_room ABC123", "click 6 4", "rematch") - kept last
 * and un-split further so it can itself contain spaces.
 */
public record GatewayCommandEnvelope(String connectionId, String username, int rating, String rawCommand) {
    public String encode() {
        return connectionId + "|" + username + "|" + rating + "|" + rawCommand;
    }

    public static GatewayCommandEnvelope decode(String encoded) {
        String[] parts = encoded.split("\\|", 4);
        return new GatewayCommandEnvelope(parts[0], parts[1], Integer.parseInt(parts[2]), parts[3]);
    }
}
