$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if ($args.Count -eq 0) { throw 'Use ./jdbc-test.ps1 dev | build | docker --bundled-pg/--external-pg; bundle: start' }
$command = $args[0]
$rest = @($args | Select-Object -Skip 1)
switch ($command) {
    'docker' { & (Join-Path $PSScriptRoot 'deploy/docker/launch.ps1') @rest; exit $LASTEXITCODE }
    { $_ -in 'dev', 'build' } {
        if (-not (Test-Path 'pom.xml')) { throw 'dev/build require the source checkout; use start in a deployment bundle.' }
        if ($command -eq 'dev' -and -not (Test-Path '.env')) {
            throw 'Missing source-root .env. Copy .env.example to .env without overwriting an existing file, fill SPRING_DATASOURCE_URL/USERNAME/PASSWORD, then run ./jdbc-test.ps1 dev. No build was started.'
        }
        if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Install JDK 21 and put java on PATH.' }
        if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { throw 'JDK 21 including javac is required.' }
        if ((Test-Path 'target') -and ((Get-Item 'target').Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Build target must not be a symlink.' }
        $bootstrapClasses = Join-Path (Join-Path $PSScriptRoot 'target') ".jdbc-bootstrap.$([Guid]::NewGuid().ToString('N'))"
        [IO.Directory]::CreateDirectory($bootstrapClasses) | Out-Null
        try {
            & javac --release 21 -d $bootstrapClasses deploy/Bootstrap.java jdbc-test-server/src/main/java/com/jdbctest/server/deploy/LaunchProcesses.java
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            & java -cp $bootstrapClasses Bootstrap @args
            $status = $LASTEXITCODE
        } finally { Remove-Item -LiteralPath $bootstrapClasses -Recurse -Force }
        exit $status
    }
    'start' {
        if (-not (Test-Path 'server/jdbc-test-server.jar')) { throw 'start requires the complete deployment bundle; use dev in source.' }
        if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Install Java 21 runtime and put java on PATH.' }
        & java '-Dloader.main=com.jdbctest.server.deploy.UnifiedLauncher' -cp server/jdbc-test-server.jar org.springframework.boot.loader.launch.PropertiesLauncher @args
    }
    default { throw 'Unknown command. Use dev | build | docker --bundled-pg/--external-pg; bundle: start' }
}
exit $LASTEXITCODE
