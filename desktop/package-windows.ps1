param(
    [string]$Version = "4.0.0"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME must point to a JDK 21 installation."
}

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gradle = Join-Path $repo "gradlew.bat"
$jlink = Join-Path $env:JAVA_HOME "bin\jlink.exe"
$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
$runtime = Join-Path $repo "desktop\build\runtime-release"
$packageRoot = Join-Path $repo "desktop\build\package"
$image = Join-Path $packageRoot "TradingJournal"
$input = Join-Path $repo "desktop\build\install\desktop\lib"
$release = Join-Path $repo "release"
$zip = Join-Path $release "TradingJournal-Windows-v$Version.zip"

foreach ($tool in @($gradle, $jlink, $jpackage)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required tool not found: $tool" }
}

function Remove-BuildTarget([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    if (-not $resolved.StartsWith($repo + [IO.Path]::DirectorySeparatorChar)) {
        throw "Refusing to remove a path outside the repository: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

Push-Location $repo
try {
    & $gradle --no-daemon :desktop:test :desktop:installDist
    if ($LASTEXITCODE -ne 0) { throw "Gradle desktop build failed." }

    Remove-BuildTarget $runtime
    & $jlink `
        --module-path (Join-Path $env:JAVA_HOME "jmods") `
        --add-modules "java.base,java.compiler,java.desktop,java.naming,java.net.http,java.security.jgss,java.sql,jdk.crypto.ec,jdk.httpserver,jdk.jfr,jdk.jsobject,jdk.unsupported,jdk.xml.dom" `
        --strip-debug --compress=2 --no-header-files --no-man-pages `
        --output $runtime
    if ($LASTEXITCODE -ne 0) { throw "jlink runtime build failed." }

    Remove-BuildTarget $image
    New-Item -ItemType Directory -Force -Path $packageRoot, $release | Out-Null
    & $jpackage `
        --type app-image --input $input --dest $packageRoot `
        --name "TradingJournal" --main-jar "desktop.jar" `
        --main-class "com.tradingpnl.desktop.TradingJournalDesktopKt" `
        --runtime-image $runtime --app-version $Version `
        --description "Offline-first trading journal with Google Drive sync" `
        --vendor "Trading Journal"
    if ($LASTEXITCODE -ne 0) { throw "jpackage app-image build failed." }

    # The app ships its own UI font assets and uses Windows system fallbacks.
    Remove-BuildTarget (Join-Path $image "runtime\lib\fonts")
    Remove-Item -LiteralPath (Join-Path $image "runtime\lib\jvm.lib") -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
    Compress-Archive -Path (Join-Path $image "*") -DestinationPath $zip -CompressionLevel Optimal

    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash.ToLowerInvariant()
    Write-Host "Windows package: $zip"
    Write-Host "SHA-256: $hash"
}
finally {
    Pop-Location
}
