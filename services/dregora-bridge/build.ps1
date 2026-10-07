param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$taskOldJavaHome = $env:JAVA_HOME
$taskOldGradleHome = $env:GRADLE_USER_HOME
try {
    $taskBundledJdk = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.toolchain/jdk8') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($taskBundledJdk) { $env:JAVA_HOME = $taskBundledJdk.FullName }
    if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) {
        throw 'Set JAVA_HOME to a JDK 8 directory before building.'
    }
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.gradle-user'
    $taskBuildArgs = @('-p', $PSScriptRoot, 'build', '--no-daemon', '--console=plain')
    if ($Offline) { $taskBuildArgs += '--offline' }
    & (Join-Path $PSScriptRoot 'gradlew.bat') @taskBuildArgs
    if ($LASTEXITCODE -ne 0) { throw "Bridge build failed with exit code $LASTEXITCODE" }
} finally {
    $env:JAVA_HOME = $taskOldJavaHome
    $env:GRADLE_USER_HOME = $taskOldGradleHome
}
