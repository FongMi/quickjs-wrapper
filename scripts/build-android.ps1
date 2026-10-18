[CmdletBinding()]
param(
    [string] $SdkDirectory = $env:ANDROID_HOME,
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $NdkVersion = '29.0.14206865',
    [switch] $ThinLto
)

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
if (!$SdkDirectory) { $SdkDirectory = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (!(Test-Path -LiteralPath (Join-Path $SdkDirectory "ndk/$NdkVersion"))) {
    throw "Install Android NDK $NdkVersion or set -NdkVersion."
}
$env:ANDROID_HOME = $SdkDirectory
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
Push-Location $repository
try {
    $gradle = if ($IsWindows) { './gradlew.bat' } else { './gradlew' }
    & $gradle --no-daemon '-PandroidRuntime=true' "-PndkVersion=$NdkVersion" "-PquickjsThinLto=$($ThinLto.IsPresent.ToString().ToLowerInvariant())" jar ':runtime:assembleRelease'
    if ($LASTEXITCODE) { throw 'Android runtime AAR build failed.' }
    & "$PSScriptRoot/verify-artifacts.ps1" -SdkDirectory $SdkDirectory -NdkVersion $NdkVersion
} finally { Pop-Location }
