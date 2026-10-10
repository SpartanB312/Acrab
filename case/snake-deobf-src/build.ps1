param([string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$sourceRoot = Join-Path $PSScriptRoot 'src'
if (-not (Test-Path -LiteralPath $sourceRoot)) { $sourceRoot = Join-Path $PSScriptRoot 'deobf\src' }
if (-not (Test-Path -LiteralPath $sourceRoot)) { throw 'Source tree not found.' }
if ($JdkHome) {
    $javac = Join-Path $JdkHome 'bin\javac.exe'
    $jar = Join-Path $JdkHome 'bin\jar.exe'
} else {
    $javac = (Get-Command javac -ErrorAction Stop).Source
    $jar = (Get-Command jar -ErrorAction Stop).Source
}
$classes = Join-Path $PSScriptRoot 'build\classes'
$distribution = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force -Path $classes, $distribution | Out-Null
$sources = @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& $javac --release 21 -encoding UTF-8 -Xlint:all -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE." }
& $jar --create --file (Join-Path $distribution 'snake-deobf.jar') --main-class snake.Snake -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE." }
Write-Output (Join-Path $distribution 'snake-deobf.jar')
