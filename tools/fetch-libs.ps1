# Downloads runtime (non-test) dependency jars into lib/ on first run.
# No Maven/Gradle - same on-demand-jar approach as tools/run-tests.ps1.
#
# Usage (from the repo root):
#   powershell -File tools/fetch-libs.ps1
#
# Currently fetches:
#   Java-WebSocket - used by server.KungFuChessServer and net.NetworkGameClient
#   (both the WebSocket server and client side live in this one dependency-free jar)
#   slf4j-api - Java-WebSocket's one runtime dependency (a logging facade only;
#   with no binding on the classpath it just falls back to a no-op logger)

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

Write-Host "Libraries ready in $lib"
