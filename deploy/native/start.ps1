# Compatibility entry shipped in previous bundles; no independent startup logic.
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'jdbc-test.ps1') start @args
exit $LASTEXITCODE
