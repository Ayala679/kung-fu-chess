# Downloads runtime (non-test) dependency jars into lib/ on first run.
# No Maven/Gradle - same on-demand-jar approach as tools/run-tests.ps1.
#
# Usage (from the repo root):
#   powershell -File tools/fetch-libs.ps1
#
# Currently fetches:
#   Java-WebSocket - used by server.KungFuChessServer and client.NetworkGameClient
#   (both the WebSocket server and client side live in this one dependency-free jar)
#   slf4j-api - Java-WebSocket's one runtime dependency (a logging facade only;
#   with no binding on the classpath it just falls back to a no-op logger)
#   sqlite-jdbc - server.auth.UserRepository, local/dev + unit tests
#   postgresql - server.auth.UserRepository, the Docker Compose deployment (see
#   Server_Design.md) - both jars sit on the classpath together since
#   DriverManager picks the right one from the jdbc: URL scheme at runtime
#   jedis (+ commons-pool2, a transitive dependency) - server.auth.RedisSessionTokenStore
#   / server.RedisReconnectRegistry, the Docker Compose deployment
#   jnats - bus.NatsBus, the Docker Compose deployment

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$lib = Join-Path $root "lib"
New-Item -ItemType Directory -Force $lib | Out-Null

$javaWebSocketVersion = "1.5.6"
$javaWebSocketJar = Join-Path $lib "Java-WebSocket-$javaWebSocketVersion.jar"

if (-not (Test-Path $javaWebSocketJar)) {
    Write-Host "Downloading Java-WebSocket $javaWebSocketVersion..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/java-websocket/Java-WebSocket/$javaWebSocketVersion/Java-WebSocket-$javaWebSocketVersion.jar" -OutFile $javaWebSocketJar
}

$slf4jVersion = "2.0.13"
$slf4jJar = Join-Path $lib "slf4j-api-$slf4jVersion.jar"

if (-not (Test-Path $slf4jJar)) {
    Write-Host "Downloading slf4j-api $slf4jVersion..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/$slf4jVersion/slf4j-api-$slf4jVersion.jar" -OutFile $slf4jJar
}

$sqliteVersion = "3.46.1.3"
$sqliteJar = Join-Path $lib "sqlite-jdbc-$sqliteVersion.jar"

if (-not (Test-Path $sqliteJar)) {
    Write-Host "Downloading sqlite-jdbc $sqliteVersion..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/$sqliteVersion/sqlite-jdbc-$sqliteVersion.jar" -OutFile $sqliteJar
}

$postgresVersion = "42.7.4"
$postgresJar = Join-Path $lib "postgresql-$postgresVersion.jar"

if (-not (Test-Path $postgresJar)) {
    Write-Host "Downloading postgresql $postgresVersion..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/postgresql/postgresql/$postgresVersion/postgresql-$postgresVersion.jar" -OutFile $postgresJar
}

$jedisVersion = "5.1.3"
$jedisJar = Join-Path $lib "jedis-$jedisVersion.jar"

if (-not (Test-Path $jedisJar)) {
    Write-Host "Downloading jedis $jedisVersion..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/redis/clients/jedis/$jedisVersion/jedis-$jedisVersion.jar" -OutFile $jedisJar
}

$commonsPool2Version = "2.12.0"
$commonsPool2Jar = Join-Path $lib "commons-pool2-$commonsPool2Version.jar"

if (-not (Test-Path $commonsPool2Jar)) {
    Write-Host "Downloading commons-pool2 $commonsPool2Version..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/apache/commons/commons-pool2/$commonsPool2Version/commons-pool2-$commonsPool2Version.jar" -OutFile $commonsPool2Jar
}

$jnatsVersion = "2.17.2"
$jnatsJar = Join-Path $lib "jnats-$jnatsVersion.jar"

if (-not (Test-Path $jnatsJar)) {
    Write-Host "Downloading jnats $jnatsVersion..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/io/nats/jnats/$jnatsVersion/jnats-$jnatsVersion.jar" -OutFile $jnatsJar
}

Write-Host "Libraries ready in $lib"
