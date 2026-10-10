# The one launcher. It starts Minecraft (if it isn't running already), waits for its world, starts the patched
# GZDoom on a WAD of your choosing at Doom's own title screen (New Game, Load Game and Save Game are Doom's, as
# always), and closes Minecraft again when you quit Doom. Started by Play.cmd.
#
#   Play.cmd                    a file window opens: pick your WAD (it opens where you picked last time)
#   Play.cmd C:\games\DOOM2.WAD that WAD (or drag a WAD file onto Play.cmd)
#   Play.cmd last               the same one as last time, without asking
#   Play.cmd kit [...]          start with the kit (diamond tools, bow, blocks, TNT) instead of a wooden pickaxe
#
# Minecraft: built from source (there is an mc\ folder here), its development copy is started, with the mod. From a
# release zip there is no Minecraft to start: it is yours, in your launcher, and this waits until you have started
# the installation that has the mod in it.
#
# Any WAD will do. A game itself (DOOM.WAD, DOOM2.WAD, TNT.WAD, PLUTONIA.WAD, the shareware DOOM1.WAD, Freedoom,
# ...) is started as it is. An add-on (a level set, a mod: a "PWAD" or a .pk3) needs the game it was made for: you
# are asked for that once, and it is remembered.
$ErrorActionPreference = "Stop"
$kit = $false
$wad = ""
foreach ($word in $args) {
    if ($word -ieq "kit") { $kit = $true } elseif ($wad -eq "") { $wad = [string]$word }
}
$here = Split-Path -Parent $PSScriptRoot
$run = Join-Path $here "run"
New-Item -ItemType Directory -Force $run | Out-Null
$memory = Join-Path $run "last-wad.txt"      # line 1: what was played last; line 2: the game last used under an add-on

$exe = @("gzdoom\gzdoom.exe", "gzdoom\build\RelWithDebInfo\gzdoom.exe") | ForEach-Object { Join-Path $here $_ } |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $exe) { Write-Host "gzdoom.exe is missing (expected in the gzdoom folder beside this script)."; Read-Host "Enter to close"; exit 1 }

$last = @("", "")
if (Test-Path $memory) { $lines = @(Get-Content $memory); for ($i = 0; $i -lt 2 -and $i -lt $lines.Count; $i++) { $last[$i] = $lines[$i] } }

function Pick([string]$title, [string]$startAt) {
    Add-Type -AssemblyName System.Windows.Forms
    $dialog = New-Object System.Windows.Forms.OpenFileDialog
    $dialog.Title = $title
    $dialog.Filter = "Doom files (*.wad;*.pk3;*.ipk3;*.pk7)|*.wad;*.pk3;*.ipk3;*.pk7|All files (*.*)|*.*"
    if ($startAt -and (Test-Path $startAt)) {
        $dialog.InitialDirectory = Split-Path -Parent $startAt
        $dialog.FileName = Split-Path -Leaf $startAt
    } else {
        $wads = Join-Path $here "wads"
        $dialog.InitialDirectory = if (Test-Path $wads) { $wads } else { $here }
    }
    if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { return $dialog.FileName }
    return $null
}

# A game itself says "IWAD" in its first four bytes; an add-on says "PWAD" (or is a .pk3).
function IsGame([string]$path) {
    if ([IO.Path]::GetExtension($path) -eq ".ipk3") { return $true }
    try {
        $stream = [IO.File]::OpenRead($path)
        $bytes = New-Object byte[] 4
        [void]$stream.Read($bytes, 0, 4)
        $stream.Close()
        return [Text.Encoding]::ASCII.GetString($bytes) -eq "IWAD"
    } catch { return $false }
}

if ($wad -eq "last") {
    $wad = $last[0]
    if (-not $wad -or -not (Test-Path $wad)) { $wad = "" }
}
if ($wad -eq "") { $wad = Pick "Pick the WAD to play" $last[0] }
if (-not $wad) { exit 0 }
if (-not (Test-Path $wad)) { Write-Host "$wad is not there."; Read-Host "Enter to close"; exit 1 }
$wad = (Resolve-Path $wad).Path

$game = $wad
$addon = $null
if (-not (IsGame $wad)) {
    # an add-on: on top of which game?
    $addon = $wad
    $game = $last[1]
    if (-not $game -or -not (Test-Path $game) -or -not (IsGame $game)) {
        # one beside the add-on, or in wads\, saves asking
        $near = @((Split-Path -Parent $wad), (Join-Path $here "wads"), $here, (Split-Path -Parent $here)) | Where-Object { Test-Path $_ } |
            ForEach-Object { Get-ChildItem $_ -File -Filter "*.wad" -ErrorAction SilentlyContinue } |
            Where-Object { $_.Name -match "^(doom2|doom|plutonia|tnt|freedoom2|freedoom1)\.wad$" -and (IsGame $_.FullName) } | Select-Object -First 1
        $game = if ($near) { $near.FullName } else { Pick "$(Split-Path -Leaf $wad) is an add-on. Pick the game it is for (DOOM2.WAD, DOOM.WAD, ...)" "" }
    }
    if (-not $game) { exit 0 }
    $last[1] = $game
}
Set-Content $memory @($wad, $last[1]) -Encoding utf8

# What is loaded beside the game: the companion (if this copy has him) and the add-on. The shareware Doom takes
# nothing beside itself (GZDoom refuses).
$shareware = (Split-Path -Leaf $game) -ieq "doom1.wad"
$files = @()
if (-not $shareware) {
    $companion = Join-Path $here "companion"
    if (Test-Path (Join-Path $companion "zscript.txt")) { $files += "`"$companion`"" }
    if ($addon) { $files += "`"$addon`"" }
} elseif ($addon) {
    Write-Host "The shareware DOOM1.WAD cannot be played with add-ons: starting it alone."
}
$fileArgs = if ($files.Count -gt 0) { "-file " + ($files -join " ") } else { "" }

# (no map is named: Doom starts at its title screen, and its own menu starts, loads and saves games)
$arguments = "-iwad `"$game`" $fileArgs -config gzdoom-mc.ini " +
    "+vid_preferbackend 0 +vid_fullscreen 0 +win_w 1600 +win_h 900 +i_pauseinbackground 0 +vid_activeinbackground 1 " +
    "+bind f +use +bind space +jump +bind r mc_toggle +unbind q +unbind e"

# ---- Minecraft first. The mod listens on 127.0.0.1:25599 from the moment it is loaded.
$PORT = 25599
function Listener {
    $c = Get-NetTCPConnection -LocalPort $PORT -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue }
    return $null
}
$minecraft = Listener
if (-not $minecraft) {
    $gradle = Join-Path $here "mc\gradlew.bat"
    if (Test-Path $gradle) {
        Write-Host "Starting Minecraft (the first time this downloads and builds it: a few minutes)..."
        if (-not $env:PASSTHROUGH_KIT) { $env:PASSTHROUGH_KIT = if ($kit) { "full" } else { "starter" } }
        $build = Join-Path $here "tools\build-mc.cmd"
        $log = Join-Path $run "minecraft.log"
        Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"`"$build`" runClient > `"$log`" 2>&1`"" -WindowStyle Hidden
    } else {
        Write-Host "Minecraft isn't running. Start it now, with the installation that has the Passthrough mod in it;"
        Write-Host "Doom starts by itself as soon as Minecraft is in its world."
        if ($kit) { Write-Host "(For the kit, that installation needs the JVM argument -Dpassthrough.kit=full.)" }
    }
    $deadline = (Get-Date).AddMinutes(20)
    while (-not $minecraft -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $minecraft = Listener
    }
    if (-not $minecraft) { Write-Host "Minecraft did not come up."; Read-Host "Enter to close"; exit 1 }
}
# ... and its world: the window's title says "Singleplayer" once the player is in it
Write-Host "Waiting for Minecraft's world..."
$deadline = (Get-Date).AddMinutes(3)
while ((Get-Date) -lt $deadline) {
    $minecraft.Refresh()
    if ($minecraft.HasExited) { Write-Host "Minecraft closed."; Read-Host "Enter to close"; exit 1 }
    if ($minecraft.MainWindowTitle -match "Singleplayer") { break }
    Start-Sleep -Milliseconds 500
}
Start-Sleep -Seconds 2

# ---- Doom; and when it is quit, Minecraft with it
Write-Host "Doom is running. Minecraft closes when you quit Doom."
$doom = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $run -PassThru
$doom.WaitForExit()
$minecraft = Listener
if ($minecraft) {
    # asked to close, as by its window's X (it saves and goes); ended if it will not
    # (its window was hidden while Doom was attached: a moment for it to come back)
    Start-Sleep -Seconds 3
    $minecraft.Refresh()
    [void]$minecraft.CloseMainWindow()
    if (-not $minecraft.WaitForExit(20000)) { Stop-Process -Id $minecraft.Id -Force -ErrorAction SilentlyContinue }
}
