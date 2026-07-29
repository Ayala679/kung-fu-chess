package server;

/**
 * Internal service-to-service envelope for a completed match, published by
 * the standalone {@code server.MatchmakerService} process to {@code
 * "shard.seat_pair"} once {@link MatchQueue} pairs two waiting players -
 * {@code server.GameServerShardController} subscribes and calls {@code
 * Lobby.seatMatchedPair(...)}. Mirrors {@link GatewayCommandEnvelope}'s
 * pipe-delimited style.
 */
public record SeatPairEnvelope(String connectionIdA, String usernameA, int ratingA,
                                String connectionIdB, String usernameB, int ratingB) {
    public String encode() {
        return connectionIdA + "|" + usernameA + "|" + ratingA + "|" + connectionIdB + "|" + usernameB + "|" + ratingB;
    }

    public static SeatPairEnvelope decode(String encoded) {
        String[] parts = encoded.split("\\|", 6);
        return new SeatPairEnvelope(parts[0], parts[1], Integer.parseInt(parts[2]), parts[3], parts[4], Integer.parseInt(parts[5]));
    }
}
