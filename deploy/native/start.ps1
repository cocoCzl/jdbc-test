$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Set-Location $PSScriptRoot
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java 21 is required. Install Java 21 and add java to PATH.'
}
if (-not (Test-Path 'config/application.properties' -PathType Leaf)) {
    throw 'Copy config/application.properties.example to config/application.properties, then set the PostgreSQL connection and master key.'
}
foreach ($artifact in @('server/jdbc-test-server.jar', 'worker/jdbc-test-worker.jar', 'web/index.html')) {
    if (-not (Test-Path $artifact -PathType Leaf)) {
        throw "Missing $artifact. Extract the complete server deployment archive."
    }
}
# JAVA_TOOL_OPTIONS is consumed by Java itself and inherited by Worker JVMs.
& java -jar server/jdbc-test-server.jar '--spring.profiles.active=native' '--spring.config.additional-location=file:./config/application.properties' @args
exit $LASTEXITCODE
