param(
    [string]$BaseDir = "C:\projects",
    [string]$TokenFile = "$env:USERPROFILE\.relay_token",
    [string]$Address = "0.0.0.0",
    [int]$Port = 8090
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $TokenFile)) {
    $bytes = New-Object 'System.Byte[]' 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    ([System.BitConverter]::ToString($bytes) -replace '-','').ToLower() | Set-Content -NoNewline -Path $TokenFile
    icacls $TokenFile /inheritance:r /grant:r "$($env:USERNAME):(R)" | Out-Null
    Write-Host "generated token file at $TokenFile"
}

Write-Host "token file: $TokenFile"
Write-Host "listening : ${Address}:${Port}"
Write-Host "base dir  : $BaseDir"

java -jar "$PSScriptRoot\..\build\libs\relay-server-1.0.0.jar" `
    --server.address=$Address `
    --server.port=$Port `
    --relay.auth-token-file=$TokenFile `
    "--relay.base-dir=$BaseDir"
