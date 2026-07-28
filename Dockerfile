# Builds and runs the Kung Fu Chess server (server.KungFuChessServer - the
# WebSocket game server + its REST API, see CLAUDE.md's "Networking"
# section and Server_Design.md). The Swing client is never containerized -
# it's a desktop GUI, run locally against this container's exposed ports.
#
# No Maven/Gradle in this project (see CLAUDE.md) - this mirrors the exact
# javac/@sources.txt invocation documented there. The lib jar
# URLs/versions below must be kept in sync with tools/fetch-libs.ps1 by
# hand (see Server_Design.md) - there's no cross-platform script runner in
# this image to share that file directly.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /build

RUN mkdir -p lib \
 && curl -fsSL -o lib/Java-WebSocket-1.5.6.jar https://repo1.maven.org/maven2/org/java-websocket/Java-WebSocket/1.5.6/Java-WebSocket-1.5.6.jar \
 && curl -fsSL -o lib/slf4j-api-2.0.13.jar https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar \
 && curl -fsSL -o lib/sqlite-jdbc-3.46.1.3.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar \
 && curl -fsSL -o lib/postgresql-42.7.4.jar https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar \
 && curl -fsSL -o lib/jedis-5.1.3.jar https://repo1.maven.org/maven2/redis/clients/jedis/5.1.3/jedis-5.1.3.jar \
 && curl -fsSL -o lib/commons-pool2-2.12.0.jar https://repo1.maven.org/maven2/org/apache/commons/commons-pool2/2.12.0/commons-pool2-2.12.0.jar \
 && curl -fsSL -o lib/jnats-2.17.2.jar https://repo1.maven.org/maven2/io/nats/jnats/2.17.2/jnats-2.17.2.jar

COPY src/main src/main
RUN cd src/main \
 && javac -encoding UTF-8 -cp "../../lib/Java-WebSocket-1.5.6.jar:../../lib/slf4j-api-2.0.13.jar:../../lib/sqlite-jdbc-3.46.1.3.jar:../../lib/postgresql-42.7.4.jar:../../lib/jedis-5.1.3.jar:../../lib/commons-pool2-2.12.0.jar:../../lib/jnats-2.17.2.jar" -d ../../out @sources.txt

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

COPY --from=build /build/out ./out
COPY --from=build /build/lib ./lib

EXPOSE 8887 8888
ENTRYPOINT ["java", "-cp", "out:lib/*", "server.KungFuChessServer"]
