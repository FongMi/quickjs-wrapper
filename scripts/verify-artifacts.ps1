[CmdletBinding()]
param(
    [string] $SdkDirectory = $env:ANDROID_HOME,
    [string] $NdkVersion = '29.0.14206865',
    [string] $JavaJar,
    [string] $RuntimeAar
)

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
if (!$JavaJar) { $JavaJar = Join-Path $repository 'build/libs/wrapper-java-0.1.0-SNAPSHOT.jar' }
if (!$RuntimeAar) { $RuntimeAar = Join-Path $repository 'runtime/build/outputs/aar/runtime-release.aar' }
if (!$SdkDirectory) { $SdkDirectory = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$platform = if ($IsWindows) { 'windows-x86_64/bin/llvm-readelf.exe' } else { 'linux-x86_64/bin/llvm-readelf' }
$readelf = Join-Path $SdkDirectory "ndk/$NdkVersion/toolchains/llvm/prebuilt/$platform"
if (!(Test-Path -LiteralPath $readelf)) { throw 'Install the selected NDK to verify native ELF alignment.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem

$api = [IO.Compression.ZipFile]::OpenRead($JavaJar)
try {
    if (!($api.Entries | Where-Object FullName -eq 'com/fongmi/quickjs/QuickJS.class')) { throw 'Java API class is missing.' }
    if (!$api.GetEntry('com/fongmi/quickjs/QuickJSContext.class')) { throw 'Synchronous context API is missing.' }
    if ($api.Entries | Where-Object { $_.FullName.StartsWith('com/whl/') }) { throw 'Java API JAR contains the previous namespace.' }
    if (!$api.GetEntry('META-INF/proguard/quickjs.pro')) { throw 'Java API JAR is missing its Android consumer rules.' }
    if ($api.Entries | Where-Object { $_.FullName -match '\.(so|dll|dylib)$' }) { throw 'Java API JAR contains a native runtime.' }
} finally { $api.Dispose() }

$javap = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/javap.exe' } else { 'bin/javap' }) } else { 'javap' }
$declarations = & $javap -private -classpath $JavaJar com.fongmi.quickjs.QuickJS
if ($LASTEXITCODE) { throw 'Java native API inspection failed.' }
$expectedJni = @($declarations | ForEach-Object {
    if ($_ -match '\bnative\s+\S+\s+(\w+)\(') { 'Java_com_fongmi_quickjs_QuickJS_' + $Matches[1] }
})
if (!$expectedJni.Count) { throw 'Java API JAR has no native declarations.' }

$verificationDirectory = Join-Path $repository 'build/artifact-verification'
New-Item -ItemType Directory -Force -Path $verificationDirectory | Out-Null
$runtime = [IO.Compression.ZipFile]::OpenRead($RuntimeAar)
try {
    $nativeEntries = @($runtime.Entries | Where-Object { $_.FullName -match '^jni/.+\.so$' })
    $expected = @('jni/arm64-v8a/libquickjs-wrapper.so', 'jni/armeabi-v7a/libquickjs-wrapper.so')
    if (Compare-Object $expected @($nativeEntries | Select-Object -ExpandProperty FullName)) {
        throw 'Runtime AAR must contain exactly the two expected QuickJS native libraries.'
    }
    $classes = $runtime.GetEntry('classes.jar')
    if ($classes) {
        $stream = $classes.Open()
        $classArchive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Read)
        try {
            if ($classArchive.Entries | Where-Object { $_.FullName.EndsWith('.class') }) {
                throw 'Runtime AAR must not contain Java API classes.'
            }
        } finally { $classArchive.Dispose(); $stream.Dispose() }
    }
    foreach ($entry in $nativeEntries) {
        $abi = $entry.FullName.Split('/')[1]
        $nativeFile = Join-Path $verificationDirectory "$abi.so"
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $nativeFile, $true)
        $programHeaders = & $readelf --program-headers --wide $nativeFile
        if ($LASTEXITCODE) { throw "ELF inspection failed for $abi." }
        $loads = @($programHeaders | Where-Object { $_ -match '^\s*LOAD\s' })
        if (!$loads.Count) { throw "No ELF load segments for $abi." }
        foreach ($load in $loads) {
            $alignment = ($load.Trim() -split '\s+')[-1]
            if ([Convert]::ToInt64($alignment.Substring(2), 16) -lt 16384) {
                throw "$abi ELF segment alignment is below 16 KB: $load"
            }
        }
        $header = & $readelf --file-header $nativeFile
        if ($LASTEXITCODE) { throw "ELF header inspection failed for $abi." }
        $machine = @($header | Where-Object { $_ -match '^\s*Machine:' })[0].Trim()
        $expectedMachine = if ($abi -eq 'arm64-v8a') { 'AArch64' } else { 'ARM' }
        if ($machine -notmatch ":\s+$expectedMachine$") { throw "Unexpected ABI: $abi $machine" }
        $symbols = & $readelf --dyn-syms --wide $nativeFile
        if ($LASTEXITCODE) { throw "JNI inspection failed for $abi." }
        $actualJni = @($symbols | ForEach-Object {
            if ($_ -match '\b(Java_com_fongmi_quickjs_QuickJS_\w+)$') { $Matches[1] }
        })
        if (Compare-Object $expectedJni $actualJni) { throw "Java/native JNI exports do not match for $abi." }
        if ($symbols -match 'Java_com_whl_') { throw "Previous JNI namespace is present for $abi." }
        Write-Output "$abi verified: $machine, $($expectedJni.Count) JNI methods, all LOAD segments aligned to at least 16 KB"
    }
} finally { $runtime.Dispose() }

Get-FileHash -Algorithm SHA256 -LiteralPath $JavaJar, $RuntimeAar | Select-Object Path, Hash
