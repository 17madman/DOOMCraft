# Builds the release: dist\minecraft-in-doom-<version>\ and the zip of it, ready to attach to a GitHub release.
#   powershell -File tools\package.ps1 [-Companion] [-SkipBuild]
# What goes in: the patched GZDoom as built (and the runtime files of the official release it needs), the mod's jar,
# Play.cmd, the README, licence and changelog, and the source (GZDoom's is the GPL's: a patch and the new files; the mod's
# whole). What stays out: any WAD, the developer's own settings, the debug symbols, and two things that are other
# people's characters: the companion (a Sanrio character; -Companion puts him in) and the Link skin in the mod's jar
# (it is for the Zelda project, not this one).
param([switch]$Companion, [switch]$SkipBuild)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$version = (Select-String -Path (Join-Path $root "mc\gradle.properties") -Pattern "^version=(.+)$").Matches[0].Groups[1].Value
$name = "minecraft-in-doom-$version"
$dist = Join-Path $root "dist"
$out = Join-Path $dist $name

if (-not $SkipBuild) {
    & cmd /c "`"$root\tools\build-mc.cmd`" build" | Select-Object -Last 3
    if ($LASTEXITCODE -ne 0) { throw "the mod did not build" }
}
$jar = Join-Path $root "mc\build\libs\passthrough-$version.jar"
if (-not (Test-Path $jar)) { throw "$jar is missing" }
$bin = Join-Path $root "gzdoom\build\RelWithDebInfo"
if (-not (Test-Path (Join-Path $bin "gzdoom.exe"))) { throw "gzdoom.exe is not built" }

if (Test-Path $out) { [IO.Directory]::Delete($out, $true) }
New-Item -ItemType Directory -Force $out, "$out\gzdoom", "$out\minecraft-mod", "$out\wads", "$out\source" | Out-Null

# GZDoom: what was built, without the symbols; the official release's licence texts
Get-ChildItem $bin | Where-Object { $_.Extension -ne ".pdb" -and $_.Extension -ne ".ini" } | ForEach-Object {
    Copy-Item $_.FullName "$out\gzdoom" -Recurse
}
Copy-Item (Join-Path $root "tools\gzdoom-release\licenses.zip") "$out\gzdoom"

# the mod, without the skin that is another project's
Copy-Item $jar "$out\minecraft-mod"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\minecraft-mod\passthrough-$version.jar", "Update")
@($zip.Entries | Where-Object { $_.FullName -like "*textures/entity/link.png" }) | ForEach-Object { $_.Delete() }
$zip.Dispose()

Copy-Item (Join-Path $root "Play.cmd") $out
New-Item -ItemType Directory -Force "$out\tools" | Out-Null
Copy-Item (Join-Path $root "tools\play.ps1") "$out\tools"
Copy-Item (Join-Path $root "README.md"), (Join-Path $root "LICENSE"), (Join-Path $root "CHANGELOG.md") $out
Set-Content "$out\wads\PUT YOUR WAD FILES HERE.txt" "A handy place for your WAD files (DOOM.WAD, DOOM2.WAD, TNT.WAD, PLUTONIA.WAD from your own copy of the game, the shareware DOOM1.WAD, Freedoom, add-ons). Play.cmd opens here the first time; it takes a WAD from anywhere." -Encoding ascii

if ($Companion) {
    Copy-Item (Join-Path $root "companion") "$out\companion" -Recurse
    Add-Content "$out\README.md" "`r`n## Companion`r`n`r`nThe dog that follows you (``mc_companion 0`` sends him away) uses the model `"Pom Pom Purin 1`" by MarisolPerez (Sketchfab, CC Attribution), reduced for Doom. Pompompurin is a Sanrio character.`r`n"
}

# source. GZDoom's changes: the patch against the commit it was built from, and the files that are new
Push-Location (Join-Path $root "gzdoom")
$commit = (& git rev-parse --short HEAD).Trim()
& git diff HEAD --output="$out\source\gzdoom-4.14.2-$commit-minecraft.patch"
$new = & git ls-files --others --exclude-standard
foreach ($file in $new) {
    if ($file -like "build/*" -or $file -like "build\*") { continue }
    $target = Join-Path "$out\source\gzdoom-new-files" $file
    New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
    Copy-Item $file $target
}
Pop-Location
Copy-Item (Join-Path $root "tools\build-gzdoom.cmd") "$out\source"
# the mod's
$mod = "$out\source\minecraft-mod"
New-Item -ItemType Directory -Force $mod | Out-Null
foreach ($item in "src", "gradle", "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat") {
    Copy-Item (Join-Path $root "mc\$item") $mod -Recurse
}
Get-ChildItem $mod -Recurse -Filter "link.png" | Remove-Item
Set-Content "$out\source\README.txt" @"
GZDoom: check out https://github.com/ZDoom/gzdoom at commit $commit (4.14.2), apply
gzdoom-4.14.2-$commit-minecraft.patch (git apply), copy gzdoom-new-files over it, and build it as GZDoom is built
(build-gzdoom.cmd is how it was built here: Visual Studio 2022, CMake).

The Minecraft mod: minecraft-mod is a Fabric Loom project (gradlew build; JDK 25).
"@ -Encoding ascii

$archive = Join-Path $dist "$name-win64.zip"
if (Test-Path $archive) { [IO.File]::Delete($archive) }
[IO.Compression.ZipFile]::CreateFromDirectory($out, $archive, [IO.Compression.CompressionLevel]::Optimal, $true)
"{0}  ({1:N1} MB)" -f $archive, ((Get-Item $archive).Length / 1MB)
