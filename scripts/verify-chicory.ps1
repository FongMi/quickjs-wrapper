[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repository $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
$project = Join-Path $repository 'vendor/chicory/android-runtime'
& $gradle --project-dir $project --no-daemon --no-configuration-cache clean jar verifyPortableRuntime
if ($LASTEXITCODE) { throw 'Chicory runtime build or regression failed.' }
$bundled = (Get-FileHash (Join-Path $repository 'libs/chicory-runtime-android.jar') -Algorithm SHA256).Hash
$rebuilt = (Get-FileHash (Join-Path $project 'build/libs/chicory-runtime-android.jar') -Algorithm SHA256).Hash
if ($bundled -ne $rebuilt) { throw 'Bundled Chicory runtime does not match the pinned source.' }
Write-Output "Verified Chicory runtime SHA-256: $($rebuilt.ToLowerInvariant())"
