<#
.SYNOPSIS
Validates an immutable finalized-bar dataset or creates a local golden-strategy evidence report.

.DESCRIPTION
The manifest and registration are strict JSON artifacts. The registration must bind the exact
manifest SHA-256 and name the complete trial family before this script runs. Reports are written
with CREATE_NEW and are never overwritten. A successful local report still has
alphaCertified=false and requires independent provenance review plus 30-90 days paper/shadow.

.EXAMPLE
.\scripts\golden-strategy-certification.ps1 -Manifest .\evidence\btc.manifest.json

.EXAMPLE
.\scripts\golden-strategy-certification.ps1 -Manifest .\evidence\btc.manifest.json `
  -Registration .\evidence\sma.registration.json -Report .\evidence\sma.report.json `
  -GeneratedAtEpochMillis 1786665600000
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Manifest,

    [string] $Registration,
    [string] $Report,
    [long] $GeneratedAtEpochMillis = 0,
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $repository 'target\crypto-trading.jar'

if (-not $SkipBuild) {
    & (Join-Path $repository 'mvnw.cmd') -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed' }
}
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Missing $jar; run without -SkipBuild first"
}

$launcher = 'org.springframework.boot.loader.launch.PropertiesLauncher'
$main = '-Dloader.main=com.tj.crypto.research.certification.GoldenCertificationCli'
if ([string]::IsNullOrWhiteSpace($Registration) -and [string]::IsNullOrWhiteSpace($Report)) {
    & java $main -cp $jar $launcher verify $Manifest
    exit $LASTEXITCODE
}
if ([string]::IsNullOrWhiteSpace($Registration) -or [string]::IsNullOrWhiteSpace($Report)) {
    throw 'Registration and Report must be supplied together'
}
if ($GeneratedAtEpochMillis -le 0) {
    throw 'GeneratedAtEpochMillis must be explicitly supplied for reproducible evidence'
}
if (Test-Path -LiteralPath $Report) {
    throw 'Report already exists; immutable evidence is never overwritten'
}
& java $main -cp $jar $launcher certify $Manifest $Registration $Report $GeneratedAtEpochMillis
exit $LASTEXITCODE
