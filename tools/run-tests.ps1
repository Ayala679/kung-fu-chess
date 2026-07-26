# Compiles the project + unit tests, runs them with JUnit 5, and produces a
# JaCoCo HTML coverage report. No Maven/Gradle needed - this project isn't
# built with Maven day to day (see README), so this pulls the same JUnit 5 +
# JaCoCo tooling as plain jars instead.
#
# Usage (from the repo root):
#   powershell -File tools/run-tests.ps1
#
# Output:
#   out/coverage-html/index.html  <- open this in a browser

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $root "tools"
$lib = Join-Path $root "lib"
$out = Join-Path $root "out"
$src = Join-Path $root "src"
$mainSrc = Join-Path $src "main"
$testSrc = Join-Path $src "tests"

& (Join-Path $tools "fetch-libs.ps1")
$libJars = (Get-ChildItem $lib -Filter *.jar | ForEach-Object { $_.FullName }) -join ";"

$junitJar = Join-Path $tools "junit-console.jar"
$jacocoAgent = Join-Path $tools "jacocoagent.jar"
$jacocoCli = Join-Path $tools "jacococli.jar"

$junitVersion = "1.10.2"
$jacocoVersion = "0.8.11"

if (-not (Test-Path $junitJar)) {
    Write-Host "Downloading JUnit console launcher..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$junitVersion/junit-platform-console-standalone-$junitVersion.jar" -OutFile $junitJar
}
if (-not (Test-Path $jacocoAgent)) {
    Write-Host "Downloading JaCoCo agent..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/$jacocoVersion/org.jacoco.agent-$jacocoVersion-runtime.jar" -OutFile $jacocoAgent
}
if (-not (Test-Path $jacocoCli)) {
    Write-Host "Downloading JaCoCo CLI..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/$jacocoVersion/org.jacoco.cli-$jacocoVersion-nodeps.jar" -OutFile $jacocoCli
}

$mainClasses = Join-Path $out "classes"
$testClasses = Join-Path $out "test-classes"
Remove-Item -Recurse -Force $mainClasses -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $testClasses -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $mainClasses | Out-Null
New-Item -ItemType Directory -Force $testClasses | Out-Null

Write-Host "Compiling main sources..."
Push-Location $mainSrc
& javac -encoding UTF-8 -cp "$libJars" -d $mainClasses "@sources.txt"
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Main source compilation failed" }
Pop-Location

Write-Host "Compiling tests..."
& javac -encoding UTF-8 -cp "$mainClasses;$junitJar;$libJars" -d $testClasses (Get-ChildItem (Join-Path $testSrc "*.java") | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed" }

$execFile = Join-Path $out "jacoco.exec"
Remove-Item $execFile -ErrorAction SilentlyContinue

Write-Host "Running tests..."
& java "-javaagent:$jacocoAgent=destfile=$execFile" -cp "$mainClasses;$testClasses;$junitJar;$libJars" org.junit.platform.console.ConsoleLauncher --scan-classpath="$testClasses" --details=tree
$testExit = $LASTEXITCODE

Write-Host "Generating coverage report..."
$reportDir = Join-Path $out "coverage-html"
& java -jar $jacocoCli report $execFile --classfiles $mainClasses --sourcefiles $mainSrc --html $reportDir --name "Kung Fu Chess Coverage"

Write-Host ""
Write-Host "Coverage report: $reportDir\index.html"
if ($testExit -ne 0) {
    Write-Host "WARNING: one or more tests failed (exit code $testExit)"
}
exit $testExit
