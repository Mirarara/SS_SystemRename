param(
    [string]$StarsectorPath = "D:\Game\SS198\Starsector"
)

$ErrorActionPreference = "Stop"
$modRoot = $PSScriptRoot
$corePath = Join-Path $StarsectorPath "starsector-core"
$jdkPath = Join-Path $StarsectorPath "jdk-27+22\bin"
$classesPath = Join-Path $modRoot "build\classes"
$jarPath = Join-Path $modRoot "jars\SystemRename.jar"
$sourcePath = Join-Path $modRoot "src\systemrename\rulecmd\SystemRename.java"

New-Item -ItemType Directory -Force -Path $classesPath, (Split-Path $jarPath) | Out-Null
Remove-Item -Recurse -Force -LiteralPath $classesPath
New-Item -ItemType Directory -Force -Path $classesPath | Out-Null

& (Join-Path $jdkPath "javac.exe") --release 17 -cp (Join-Path $corePath "starfarer.api.jar") -d $classesPath $sourcePath
if ($LASTEXITCODE -ne 0) { throw "Compilation failed." }

& (Join-Path $jdkPath "java.exe") -ea -cp "$classesPath;$(Join-Path $corePath 'starfarer.api.jar')" systemrename.rulecmd.SystemRename
if ($LASTEXITCODE -ne 0) { throw "Logic self-check failed." }

& (Join-Path $jdkPath "jar.exe") --create --file $jarPath -C $classesPath .
if ($LASTEXITCODE -ne 0) { throw "JAR creation failed." }

Write-Host "Built $jarPath"
