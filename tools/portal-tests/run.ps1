param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$KotlinLibDirectory,
    [Parameter(Mandatory = $true)][string]$GsonJar,
    [string]$NodeExecutable = 'node'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$sourceDirectory = Join-Path $projectDirectory 'app/src/main/java/cn/local/bitcourse/portal'
$outputDirectory = Join-Path $projectDirectory 'app/build/portal-script-tests'
$classesDirectory = Join-Path $outputDirectory 'classes'
$javaExecutable = Join-Path $JavaHome 'bin/java.exe'
$compilerClasspath = Join-Path $KotlinLibDirectory '*'
$stdlib = @(Get-ChildItem -LiteralPath $KotlinLibDirectory -Filter 'kotlin-stdlib*.jar' -File)
$annotations = @(Get-ChildItem -LiteralPath $KotlinLibDirectory -Filter 'annotations*.jar' -File)
if (!(Test-Path -LiteralPath $javaExecutable -PathType Leaf)) { throw 'JDK java.exe not found.' }
if (!(Test-Path -LiteralPath $GsonJar -PathType Leaf)) { throw 'Gson jar not found.' }
if ($stdlib.Count -eq 0) { throw 'Kotlin standard library not found in compiler library directory.' }
New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null
$dependencyClasspath = (@($stdlib.FullName) + @($annotations.FullName) + @($GsonJar)) -join ';'

# Compile current production scripts every time; never run a checked-in script snapshot.
& $javaExecutable -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $dependencyClasspath `
    -d $classesDirectory (Join-Path $sourceDirectory 'PortalUrls.kt') (Join-Path $sourceDirectory 'PortalScripts.kt')
if ($LASTEXITCODE -ne 0) { throw 'Production portal script compilation failed.' }

$runtimeClasspath = "$classesDirectory;$dependencyClasspath"
$generatedJson = & $javaExecutable -cp $runtimeClasspath (Join-Path $PSScriptRoot 'DumpScripts.java')
if ($LASTEXITCODE -ne 0) { throw 'Script export failed.' }
$jsonFile = Join-Path $outputDirectory 'scripts.json'
[IO.File]::WriteAllText($jsonFile, ($generatedJson -join [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
& $NodeExecutable (Join-Path $PSScriptRoot 'check-scripts.cjs') $jsonFile
if ($LASTEXITCODE -ne 0) { throw 'Offline JavaScript tests failed.' }
