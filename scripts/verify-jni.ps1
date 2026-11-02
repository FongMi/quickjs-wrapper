[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $JavaJar,
    [Parameter(Mandatory)][AllowEmptyString()][string[]] $Symbols
)

$ErrorActionPreference = 'Stop'
$javap = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/javap.exe' } else { 'bin/javap' }) } else { 'javap' }
$expected = @('QuickJS', 'SharedMemory' | ForEach-Object {
    $class = $_
    $declarations = & $javap -private -classpath $JavaJar "com.fongmi.quickjs.$class"
    if ($LASTEXITCODE) { throw "Java native API inspection failed: $class" }
    $declarations | ForEach-Object {
        if ($_ -match '\bnative\s+\S+\s+(\w+)\(') { "Java_com_fongmi_quickjs_${class}_" + $Matches[1] }
    }
})
if (!$expected.Count) { throw 'Java API JAR has no native declarations.' }
$actual = @($Symbols | ForEach-Object {
    if ($_ -match '\b(Java_com_fongmi_quickjs_\w+)\b') { $Matches[1] }
} | Sort-Object -Unique)
$expected = @($expected | Sort-Object -Unique)
$difference = @(Compare-Object $expected $actual)
if ($difference.Count) {
    $difference | Format-Table | Out-String | Write-Output
    throw 'Java/native JNI exports do not match.'
}
if ($Symbols -match 'Java_com_whl_') { throw 'Previous JNI namespace is present.' }
Write-Output "Verified $($expected.Count) matching JNI exports."
