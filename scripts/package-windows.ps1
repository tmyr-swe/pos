# Windows build: self-contained app image, portable zip and (when WiX is
# installed) an .msi installer. Run this on Windows, not on Linux.
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

[xml]$pom = Get-Content "pom.xml"
$version = $pom.project.version
$appName = "Chegg POS"
$modules = "java.base,java.desktop,java.logging,java.management,java.naming,java.sql,java.prefs,jdk.unsupported"

mvn.cmd -q -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }

Remove-Item -Recurse -Force "target\jpackage-input", "dist\windows" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "target\jpackage-input", "dist\windows" | Out-Null
Copy-Item "target\chegg-pos.jar" "target\jpackage-input\chegg-pos.jar"

$common = @(
    "--name", $appName,
    "--app-version", $version,
    "--input", "target\jpackage-input",
    "--main-jar", "chegg-pos.jar",
    "--main-class", "com.chegg.pos.Main",
    "--icon", "packaging\chegg-pos.ico",
    "--description", "Chegg point of sale",
    "--vendor", "Chegg",
    "--add-modules", $modules,
    "--java-options", "--enable-native-access=ALL-UNNAMED",
    "--java-options", "-Dfile.encoding=UTF-8"
)

jpackage @common --type app-image --dest "dist\windows"
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed." }

New-Item -ItemType Directory -Force -Path "dist" | Out-Null
Compress-Archive -Path "dist\windows\$appName" `
    -DestinationPath "dist\chegg-pos-$version-windows-x64.zip" -Force

$wix = Get-Command candle.exe -ErrorAction SilentlyContinue
if ($wix) {
    jpackage @common --type msi --dest "dist" --win-shortcut --win-menu --win-dir-chooser
    if ($LASTEXITCODE -ne 0) { throw "jpackage msi failed." }
} else {
    Write-Host "WiX Toolset not found: skipped .msi (app image and zip are ready)."
}

Write-Host "Windows build ready in $Root\dist"
