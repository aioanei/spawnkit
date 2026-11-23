# Starts a local SpawnKit server with the settings from .env in the repository root (see .env.example).
# Values are set for this process only and are never printed; only the names of the loaded settings are shown.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) { throw "Create $envFile from .env.example first." }
foreach ($line in Get-Content $envFile) {
    $text = $line.Trim()
    if ($text -eq '' -or $text.StartsWith('#')) { continue }
    $split = $text.IndexOf('=')
    if ($split -lt 1) { continue }
    $name = $text.Substring(0, $split).Trim()
    $value = $text.Substring($split + 1).Trim().Trim('"')
    if ($value -eq '') { continue }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    Write-Host "Loaded $name"
}
& (Join-Path $root 'gradlew.bat') ':server:installDist'
if ($LASTEXITCODE -ne 0) { throw 'The server did not build.' }
& (Join-Path $root 'server\build\install\server\bin\server.bat')
