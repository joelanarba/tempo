$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
foreach ($command in @('java', 'mvn', 'docker')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "$command is required. Install Java 21, Maven 3.9+ and Docker with Compose."
    }
}

if (-not (Test-Path -LiteralPath $envFile)) {
    $lines = Get-Content -LiteralPath (Join-Path $root '.env.example')
    foreach ($name in @('POSTGRES_PASSWORD', 'JWT_SECRET', 'SEED_ADMIN_PASSWORD')) {
        $size = if ($name -eq 'SEED_ADMIN_PASSWORD') { 24 } else { 48 }
        $bytes = New-Object byte[] $size
        $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
        $value = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
        $lines = $lines | ForEach-Object { if ($_ -match "^$name=") { "$name=$value" } else { $_ } }
    }
    [System.IO.File]::WriteAllLines($envFile, [string[]] $lines, [System.Text.UTF8Encoding]::new($false))
    Write-Host 'Created .env with local credentials. Keep that file private.'
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
foreach ($name in @('POSTGRES_PASSWORD', 'JWT_SECRET', 'SEED_ADMIN_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace($settings[$name])) { throw "Set $name in .env before starting." }
}
if ([System.Text.Encoding]::UTF8.GetByteCount($settings['SEED_ADMIN_PASSWORD']) -gt 72) {
    throw 'SEED_ADMIN_PASSWORD must fit within the BCrypt limit of 72 UTF-8 bytes.'
}
foreach ($name in @('DATABASE_URL', 'REDIS_URL')) {
    $connection = $settings[$name]
    if ([string]::IsNullOrWhiteSpace($connection)) { throw "Set $name in .env before starting." }
    if ($name -eq 'DATABASE_URL' -and $connection.StartsWith('jdbc:')) { $connection = $connection.Substring(5) }
    if (([Uri] $connection).Host -notin @('localhost', '127.0.0.1', '::1')) {
        throw 'The local startup script accepts only loopback database and Redis hosts.'
    }
}
foreach ($name in @('DATABASE_URL', 'DATABASE_USERNAME', 'REDIS_URL', 'JWT_SECRET', 'SEED_ADMIN_PASSWORD', 'WEBSOCKET_ALLOWED_ORIGINS', 'SERVER_PORT')) {
    [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
}
[Environment]::SetEnvironmentVariable('DATABASE_PASSWORD', $settings['POSTGRES_PASSWORD'], 'Process')
$previousLocation = Get-Location
try {
    Set-Location -LiteralPath $root
    docker compose --env-file $envFile -f (Join-Path $root 'compose.yaml') up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Dependency startup failed.' }
    mvn -ntp '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Application packaging failed.' }
    java -jar (Join-Path $root 'target/tempo.jar') '--spring.profiles.active=setup'
} finally {
    Set-Location -LiteralPath $previousLocation
}
