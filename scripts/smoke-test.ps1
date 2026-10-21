param([string] $BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
$uri = [Uri] $BaseUrl
if ($uri.Host -notin @('localhost', '127.0.0.1', '::1')) {
    throw 'This script creates disposable data and accepts only a loopback server.'
}
$root = Split-Path -Parent $PSScriptRoot
$settings = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
function Request([string] $Method, [string] $Path, $Body = $null, [string] $Token = '') {
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path"; ErrorAction = 'Stop' }
    if ($Token) { $parameters['Headers'] = @{ Authorization = "Bearer $Token" } }
    if ($null -ne $Body) {
        $parameters['ContentType'] = 'application/json'
        $parameters['Body'] = ConvertTo-Json -InputObject $Body -Depth 5
    }
    $response = Invoke-RestMethod @parameters
    if (-not $response.success) { throw "Request failed: $Method $Path" }
    return $response.data
}
function ExpectStatus([int] $Expected, [scriptblock] $Action) {
    try { & $Action | Out-Null } catch {
        if ($null -ne $_.Exception.Response -and [int] $_.Exception.Response.StatusCode -eq $Expected) { return }
        throw
    }
    throw "Expected HTTP $Expected"
}
function RegisterMember {
    return Request 'POST' '/api/v1/auth/register' @{
        email = "$([Guid]::NewGuid())@tempo.test"
        password = [Guid]::NewGuid().ToString()
        firstName = 'Demo'; lastName = 'Member'
    }
}

$health = Request 'GET' '/health'
if ($health.status -ne 'UP') { throw 'Dependencies are not ready.' }
$admin = Request 'POST' '/api/v1/auth/login' @{ email = 'admin@tempo.africa'; password = $settings['SEED_ADMIN_PASSWORD'] }
$owner = RegisterMember
$waiter = RegisterMember
$space = Request 'POST' '/api/v1/spaces' @{ name = "Demo $([Guid]::NewGuid())"; location = 'UTC'; description = 'Disposable smoke test'; openingTime = '08:00:00'; closingTime = '20:00:00' } $admin.accessToken
$resource = Request 'POST' "/api/v1/spaces/$($space.id)/resources" @{ name = 'Desk'; type = 'DESK' } $admin.accessToken
$date = [DateTime]::UtcNow.Date.AddDays(1)
$slot = @{ resourceId = $resource.id; startTime = $date.AddHours(9).ToString("yyyy-MM-ddTHH:mm:ss'Z'"); endTime = $date.AddHours(10).ToString("yyyy-MM-ddTHH:mm:ss'Z'") }
$booking = Request 'POST' '/api/v1/bookings' $slot $owner.accessToken
ExpectStatus 409 { Request 'POST' '/api/v1/bookings' $slot $waiter.accessToken }
ExpectStatus 403 { Request 'DELETE' "/api/v1/bookings/$($booking.id)" $null $waiter.accessToken }
$entry = Request 'POST' '/api/v1/waitlist' $slot $waiter.accessToken
$null = Request 'DELETE' "/api/v1/bookings/$($booking.id)" $null $owner.accessToken
ExpectStatus 409 { Request 'POST' '/api/v1/bookings' $slot $owner.accessToken }
$claimed = Request 'POST' "/api/v1/waitlist/offers/$($entry.id)/claim" $null $waiter.accessToken
if ($claimed.status -ne 'CONFIRMED') { throw 'Waitlist claim did not confirm the hold.' }
$snapshot = Request 'GET' "/api/v1/spaces/$($space.id)/availability?date=$($date.ToString('yyyy-MM-dd'))" $null $owner.accessToken
if ($snapshot.resources[0].bookedSlots.Count -ne 1) { throw 'Unexpected availability snapshot.' }
$null = Request 'DELETE' "/api/v1/bookings/$($claimed.id)" $null $waiter.accessToken
$null = Request 'POST' '/api/v1/auth/logout' @{ refreshToken = $waiter.refreshToken } $waiter.accessToken
ExpectStatus 401 { Request 'GET' '/api/v1/users/me' $null $waiter.accessToken }
$null = Request 'DELETE' "/api/v1/resources/$($resource.id)" $null $admin.accessToken
$null = Request 'DELETE' "/api/v1/spaces/$($space.id)" $null $admin.accessToken
Write-Host 'PASS: health, authentication, conflict, ownership, hold, claim, availability and logout.'
Write-Host 'This sequential smoke test does not replace the concurrent PostgreSQL tests or WebSocket tests.'
