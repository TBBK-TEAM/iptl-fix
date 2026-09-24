# Builds iptl-respawn-fix against the jars of an existing NeoForge/Youer server install.
# No Gradle, no internet, no mappings download: only javac + jar (JDK 21) are required.
#
#   .\build.ps1                          # server directory is the parent of the repo
#   .\build.ps1 -ServerRoot C:\server    # explicit server directory
#
[CmdletBinding()]
param(
    [string]$ServerRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$OutDir     = (Join-Path $PSScriptRoot 'dist'),
    [string]$JarName    = 'iptl-respawn-fix-1.0.0.jar'
)

$ErrorActionPreference = 'Stop'

function Find-ServerJar {
    param([string]$Base, [string]$Pattern)
    $hit = Get-ChildItem -Path $Base -Recurse -Filter $Pattern -ErrorAction SilentlyContinue |
           Select-Object -First 1
    if (-not $hit) { throw "Not found under '$Base': $Pattern" }
    return $hit.FullName
}

$libraries = Join-Path $ServerRoot 'libraries'
if (-not (Test-Path $libraries)) { throw "Not a server directory (no 'libraries'): $ServerRoot" }

$mixinJar = Find-ServerJar -Base $libraries -Pattern 'sponge-mixin-*.jar'
$mcJar    = Find-ServerJar -Base $libraries -Pattern 'server-*-srg.jar'

Write-Host "sponge-mixin : $mixinJar"
Write-Host "minecraft    : $mcJar"

$srcDir   = Join-Path $PSScriptRoot 'src'
$classDir = Join-Path $PSScriptRoot 'build\classes'

if (Test-Path $classDir) { Remove-Item -Recurse -Force $classDir }
New-Item -ItemType Directory -Force -Path $classDir | Out-Null
New-Item -ItemType Directory -Force -Path $OutDir   | Out-Null

$sources = Get-ChildItem -Path $srcDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $sources) { throw "No java sources found under $srcDir" }

# -proc:none: the Mixin annotation processor would need ASM on the classpath and generate a
# refmap that this (Mojang-mapped) runtime does not need.
& javac -proc:none --release 21 -encoding UTF-8 -cp "$mixinJar;$mcJar" -d $classDir @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed (exit $LASTEXITCODE)" }

$jarPath = Join-Path $OutDir $JarName
if (Test-Path $jarPath) { Remove-Item -Force $jarPath }

& jar --create --file $jarPath `
        -C $classDir . `
        -C $srcDir META-INF `
        -C $srcDir iptlfix.mixins.json
if ($LASTEXITCODE -ne 0) { throw "jar failed (exit $LASTEXITCODE)" }

Write-Host "built: $jarPath"
