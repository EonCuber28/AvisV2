# Builds the standalone Avis executable for Windows into dist\.
#
#   .\build.ps1             native build -> dist\avis-windows-<arch>.exe (no console window)
#   .\build.ps1 -Console    same, but keep a console window for log output
#   .\build.ps1 -Trace      run Avis on the JVM under the GraalVM tracing agent and save the FFM
#                           metadata it records into native-image-config\<os>-<arch>\ (commit it)
#   .\build.ps1 -Pgo        build a profiling copy of Avis and run it; use it for a while, quit it normally, and
#                           the profile it records is saved into pgo-profiles\<os>-<arch>\ (commit it). Later
#                           builds optimise with that profile
#   .\build.ps1 -Clean      delete build\ and dist\ first, then build (combines with -Trace and -Pgo)
#   .\build.ps1 -GlslCheck  only build the shaders with glslangValidator (check shaders\OpenGL,
#                           compile shaders\Vulkan to SPIR-V), no build
#
# build.sh's --trace, --pgo, --clean, --console and --glsl-check spellings work too.
#
# Needs: a JDK 17+ to start Gradle (Oracle GraalVM 25 itself is downloaded automatically) and
#        Visual Studio 2022 (or Build Tools) with the "Desktop development with C++" workload,
#        and glslangValidator (ships with the Vulkan SDK) to build the shaders.
param(
    [switch]$Trace,
    [switch]$Pgo,
    [switch]$Console,
    [switch]$Clean,
    [switch]$GlslCheck,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs = @()
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# accept build.sh's --flags as well
switch ($GradleArgs) {
    '--trace' { $Trace = $true }
    '--pgo' { $Pgo = $true }
    '--clean' { $Clean = $true }
    '--console' { $Console = $true }
    '--glsl-check' { $GlslCheck = $true }
}
$GradleArgs = @($GradleArgs | Where-Object { $_ -notin '--trace', '--pgo', '--clean', '--console', '--glsl-check' })
$cleanTask = if ($Clean) { @('clean') } else { @() }

function Fail($message) {
    Write-Host "`nerror: $message" -ForegroundColor Red
    exit 1
}

if (@($Trace, $Pgo, $GlslCheck | Where-Object { $_ }).Count -gt 1) {
    Fail 'only one of -Trace, -Pgo and -GlslCheck can be used at a time.'
}

function Assert-ShaderValidator {
    $hasValidator = (Get-Command glslangValidator.exe -ErrorAction SilentlyContinue) -or
        ($env:VULKAN_SDK -and (Test-Path (Join-Path $env:VULKAN_SDK 'Bin\glslangValidator.exe'))) -or
        ($GradleArgs | Where-Object { $_ -like '-PglslangValidator=*' })
    if (-not $hasValidator) {
        Fail ('glslangValidator not found (it builds the shaders). Install the Vulkan SDK ' +
              '(https://vulkan.lunarg.com/sdk/home) or a glslang release (https://github.com/KhronosGroup/glslang/releases).')
    }
}

function Get-JavaMajor($java) {
    $out = & $java -version 2>&1 | Out-String
    if ($out -match 'version "(\d+)(\.(\d+))?') {
        if ($Matches[1] -eq '1') { return [int]$Matches[3] } else { return [int]$Matches[1] }
    }
    return 0
}

# Gradle 9 needs Java 17+ to start; the project's own JDK (Oracle GraalVM 25) is provisioned by Gradle
function Find-GradleJava {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\java.exe' }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    $roots = @("$env:USERPROFILE\.jdks", "$env:USERPROFILE\.gradle\jdks",
               "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft")
    foreach ($root in $roots) {
        if (Test-Path $root) {
            $candidates += Get-ChildItem $root -Directory | ForEach-Object { Join-Path $_.FullName 'bin\java.exe' }
        }
    }
    foreach ($java in $candidates) {
        if ((Test-Path $java) -and (Get-JavaMajor $java) -ge 17) {
            $env:JAVA_HOME = Split-Path (Split-Path $java)
            return $true
        }
    }
    return $false
}

if (-not (Find-GradleJava)) {
    Fail 'no JDK 17+ found to run Gradle. Install one (any vendor) or point JAVA_HOME at it.'
}
Write-Host "Using $env:JAVA_HOME to run Gradle"

if ($GlslCheck) {
    Assert-ShaderValidator
    # --rerun-tasks so the shaders are always rebuilt, even when Gradle thinks the task is up to date;
    # --continue so one backend's errors don't hide the other's
    & .\gradlew.bat @cleanTask compileShaders --rerun-tasks --continue @GradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "`nShaders:"
    Get-Content build\reports\shaders\OpenGL.txt, build\reports\shaders\Vulkan.txt | ForEach-Object { "  $_" }
    exit 0
}

if ($Trace) {
    # the agent run uses GRAALVM_HOME, so point it at the GraalVM 25 toolchain
    $env:GRAALVM_HOME = (& .\gradlew.bat -q graalHome | Select-Object -Last 1)
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & .\gradlew.bat @cleanTask -Pagent run @GradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & .\gradlew.bat metadataCopy --task run @GradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "`nFFM metadata saved under native-image-config\. Commit it, then run build.bat"
    exit 0
}

# native-image needs the MSVC C++ toolchain; it finds the Visual Studio install by itself
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$vcTools = if (Test-Path $vswhere) {
    & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
}
if (-not $vcTools) {
    Fail ('MSVC C++ build tools not found. Install "Build Tools for Visual Studio 2022" with the ' +
          '"Desktop development with C++" workload: https://visualstudio.microsoft.com/visual-cpp-build-tools/')
}

# the native build runs glslangValidator over the shaders
Assert-ShaderValidator

if ($Console) { $GradleArgs += '-Pconsole' }

if ($Pgo) {
    & .\gradlew.bat @cleanTask -PpgoInstrument nativeRun @GradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & .\gradlew.bat pgoCopy @GradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "`nPGO profile saved under pgo-profiles\. Commit it, then run build.bat"
    exit 0
}

& .\gradlew.bat @cleanTask dist @GradleArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "`nBuilt:"
Get-ChildItem dist\avis-*.exe | ForEach-Object { '  {0}  ({1:N1} MB)' -f $_.FullName, ($_.Length / 1MB) }
