$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
$mode = ''; $project = ''; $port = ''; $new = $false; $stop = $false; $hostPg = $false
for ($i = 0; $i -lt $args.Count; $i++) {
    switch ($args[$i]) {
        '--bundled-pg' { if ($mode) { throw 'Select exactly one mode.' }; $mode = 'bundled' }
        '--external-pg' { if ($mode) { throw 'Select exactly one mode.' }; $mode = 'external' }
        '--new-install' { $new = $true }
        '--stop' { $stop = $true }
        '--host-pg' { $hostPg = $true }
        '--project' { $i++; if ($i -ge $args.Count) { throw 'Missing project.' }; $project = $args[$i] }
        '--port' { $i++; if ($i -ge $args.Count) { throw 'Missing port.' }; $port = $args[$i] }
        default { throw 'Unknown option. Select --bundled-pg or --external-pg. Secrets belong in .env/hidden input.' }
    }
}
if (-not $mode) { throw 'Select --bundled-pg or --external-pg.' }
function Run-Docker { & docker @args; if ($LASTEXITCODE -ne 0) { throw 'Docker operation failed; data/configuration retained.' } }
Run-Docker compose version | Out-Null
Run-Docker info | Out-Null
$names = @('COMPOSE_FILE', 'COMPOSE_ENV_FILES', 'COMPOSE_PROJECT_NAME', 'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD', 'JDBC_TEST_MASTER_KEY', 'JDBC_TEST_PORT', 'JDBC_TEST_BIND_ADDRESS', 'JDBC_TEST_MAX_CONCURRENT_RUNS', 'JDBC_TEST_RUN_TIMEOUT', 'JAVA_TOOL_OPTIONS')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process'); [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
$lock = Join-Path (Get-Location) '.docker-launch.lock'
$locked = $false
try {
    $image = if ($env:JDBC_TEST_SETUP_IMAGE) { $env:JDBC_TEST_SETUP_IMAGE } else { 'jdbc-test:setup' }
    if (-not $env:JDBC_TEST_SETUP_IMAGE) { Run-Docker build -t $image . }
    $network = if ($env:JDBC_TEST_SETUP_NETWORK) { $env:JDBC_TEST_SETUP_NETWORK } else { 'bridge' }
    $interactive = if ([Console]::IsInputRedirected) { '-i' } else { '-it' }
    function Helper {
        $inputMode = if ($args[0] -eq 'docker-info') { '-i' } else { $interactive }
        Run-Docker run --rm $inputMode --mount "type=bind,source=$((Get-Location).Path),target=/deployment" --network $network --add-host 'host.docker.internal:host-gateway' --workdir /deployment --entrypoint java $image '-Dloader.main=com.jdbctest.server.deploy.UnifiedLauncher' -cp /app/server/jdbc-test-server.jar org.springframework.boot.loader.launch.PropertiesLauncher @args
    }
    $options = @('--mode', $mode)
    if ($project) { $options += @('--project', $project) }
    $project = Helper docker-info @options --project-only
    function Assert-Empty {
        $volumes = Run-Docker volume ls -q --filter "label=com.docker.compose.project=$project"
        $containers = Run-Docker ps -aq --filter "label=com.docker.compose.project=$project"
        $directoryContainers = Run-Docker ps -aq --filter "label=com.docker.compose.project.working_dir=$((Get-Location).Path)"
        if ($volumes -or $containers -or $directoryContainers) { throw 'Existing project/path/volume state: restore the original .env and Key.' }
        foreach ($volume in @("${project}_postgres_data", "${project}_jdbc_test_data")) { & docker volume inspect $volume *> $null; if ($LASTEXITCODE -eq 0) { throw 'Existing volume: restore original .env and Key.' } }
    }
    $candidate = "$lock.$([Guid]::NewGuid().ToString('N'))"
    [IO.Directory]::CreateDirectory($candidate) | Out-Null
    try { [IO.Directory]::Move($candidate, $lock); $locked = $true } catch { [IO.Directory]::Delete($candidate); throw 'Another Docker launch is running.' }
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetOwner($owner); $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($owner, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
    Set-Acl -LiteralPath $lock -AclObject $acl
    if (-not (Test-Path '.env')) {
        if ($stop) { throw 'Restore original .env before stopping its project.' }
        Assert-Empty
        $initOptions = @('--mode', $mode, '--project', $project, '--docker-state-checked')
        if ($new) { $initOptions += '--new-install' }
        if ($port) { $initOptions += @('--port', $port) }
        Helper init-docker @initOptions
        Assert-Empty
        $fileAcl = New-Object Security.AccessControl.FileSecurity
        $fileAcl.SetOwner($owner); $fileAcl.SetAccessRuleProtection($true, $false)
        $fileAcl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($owner, 'FullControl', 'Allow')))
        $file = Join-Path $lock 'platform.env'
        Set-Acl -LiteralPath $file -AclObject $fileAcl
        [IO.File]::Move($file, (Join-Path (Get-Location) '.env'))
    }
    $options = @('--mode', $mode, '--project', $project)
    if ((Helper docker-info @options --needs-key) -eq 'yes') { Assert-Empty }
    if ($new) { $options += '--new-install' }
    if ($stop) { $options += '--stop' }
    if ($port) { $options += @('--port', $port) }
    Helper prepare-docker @options --docker-state-checked
    $fileAcl = New-Object Security.AccessControl.FileSecurity
    $fileAcl.SetOwner($owner); $fileAcl.SetAccessRuleProtection($true, $false)
    $fileAcl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($owner, 'FullControl', 'Allow')))
    Set-Acl -LiteralPath (Join-Path $lock 'compose.env') -AclObject $fileAcl
    $compose = if ($mode -eq 'bundled') { 'docker-compose.yml' } else { 'docker-compose.external.yml' }
    $composeOptions = @('--env-file', '.docker-launch.lock/compose.env', '-p', $project, '-f', $compose)
    if ($hostPg) { $composeOptions += @('-f', 'docker-compose.host-gateway.yml') }
    Run-Docker compose @composeOptions config --quiet
    if ($stop) { Run-Docker compose @composeOptions stop; Write-Output 'Stopped. Configuration, Key and data retained.' }
    else { Run-Docker compose @composeOptions up -d --build --wait; Write-Output 'Ready. Website binding:'; Run-Docker compose @composeOptions port jdbc-test 8080 }
} finally {
    if ($locked) { foreach ($file in @('compose.env', 'platform.env')) { if (Test-Path (Join-Path $lock $file)) { Remove-Item -LiteralPath (Join-Path $lock $file) } }; [IO.Directory]::Delete($lock) }
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
