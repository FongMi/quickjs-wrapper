[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $NativeDirectory,
    [string] $JavaJar,
    [string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
if (!$JavaJar) { $JavaJar = Join-Path $repository 'build/libs/wrapper-java-0.1.0-SNAPSHOT.jar' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $repository 'build/shared-memory-contract' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/javac.exe' } else { 'bin/javac' }) } else { 'javac' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' }) } else { 'java' }
& $javac --release 17 -cp $JavaJar -d $OutputDirectory (Join-Path $repository 'ci/SharedMemoryContract.java')
if ($LASTEXITCODE) { throw 'Shared-memory regression compilation failed.' }
$separator = [IO.Path]::PathSeparator
& $java '-Xcheck:jni' "-Djava.library.path=$NativeDirectory" -cp "$OutputDirectory$separator$JavaJar" com.fongmi.quickjs.SharedMemoryContract (Join-Path $repository 'ci/shared-memory.wasm')
if ($LASTEXITCODE) { throw 'Shared-memory regression failed.' }
