# Starts Minecraft and the patched GZDoom for a test in the background: nothing comes to the front, nothing is heard,
# the real keyboard and mouse are not used, and the player's own GZDoom settings are not written to.
#   powershell -File tools\go.ps1 [map]
# GZDoom runs from run\ with a copy of the settings (gzdoom-test.ini), windowed and placed off the screen. It is
# driven by console commands written to run\mc-test.txt (see testCommands in mcbridge.cpp) and looked at through
# its own "screenshot" command; tools\t.py does both.
param([string]$map = "1")
$root = Split-Path -Parent $PSScriptRoot
$run = Join-Path $root "run"
$log = Join-Path $root "mc\run\logs\latest.log"

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class TestWin {
    public delegate bool EnumProc(IntPtr hwnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc proc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd, int cmd);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int w, int h, uint flags);
    public static IntPtr Find(uint pid) {
        IntPtr found = IntPtr.Zero;
        EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); if (p == pid && IsWindowVisible(h)) { found = h; return false; } return true; }, IntPtr.Zero);
        return found;
    }
}
"@

Copy-Item (Join-Path $run "gzdoom-mc.ini") (Join-Path $run "gzdoom-test.ini") -Force
Remove-Item (Join-Path $run "mc-test.txt"), (Join-Path $run "mc-test.log") -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $run "shots") | Out-Null

# Minecraft: its window never shown, no sound
$env:PASSTHROUGH_BACKGROUND = "1"
if (-not $env:PASSTHROUGH_KIT) { $env:PASSTHROUGH_KIT = "starter" }
$started = Get-Date
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"`"$root\tools\build-mc.cmd`" runClient > `"$root\run\minecraft.log`" 2>&1`"" -WindowStyle Hidden
$deadline = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 300
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*fabric.dli.config*' } | ForEach-Object {
        $h = [TestWin]::Find([uint32]$_.ProcessId)
        if ($h -ne [IntPtr]::Zero) { [TestWin]::ShowWindow($h, 0) | Out-Null }
    }
    if ((Test-Path $log) -and ((Get-Item $log).LastWriteTime -gt $started)) {
        $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
        if ($text -match "weather clear|Crash report saved|Mixin apply|InvalidInjection") { break }
    }
}
Select-String -Path $log -Pattern "creating world|opening world|joined the game|Mixin apply|InvalidInjection|Crash report" | ForEach-Object { $_.Line }

$arguments = "-iwad `"$root\..\DOOM2.WAD`" -file `"$root\companion`" -config gzdoom-test.ini " +
    "+vid_preferbackend 0 +vid_fullscreen 0 +win_w 1280 +win_h 720 +win_x 6000 +win_y 200 +snd_mastervolume 0 " +
    "+i_pauseinbackground 0 +vid_activeinbackground 1 +cl_run 0 +screenshot_dir `"$run\shots`" " +
    "+bind f +use +bind space +jump +bind r mc_toggle +unbind q +unbind e -skill 2 -warp $map"
$front = [TestWin]::GetForegroundWindow()
$doom = Start-Process -FilePath (Join-Path $root "gzdoom\build\RelWithDebInfo\gzdoom.exe") -ArgumentList $arguments -WorkingDirectory $run -PassThru
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 100
    $h = [TestWin]::Find([uint32]$doom.Id)
    if ($h -ne [IntPtr]::Zero) {
        # (not minimised first, as the Zelda tests do: GZDoom, started minimised, draws its scene wrong for good.
        # It is put off the screen and under everything, and whatever had the front gets it back.)
        [TestWin]::SetWindowPos($h, [IntPtr]1, 6000, 200, 0, 0, 0x0011) | Out-Null
        if ($front -ne [IntPtr]::Zero) { [TestWin]::SetForegroundWindow($front) | Out-Null }
        break
    }
}
Start-Sleep -Seconds 8
"both started (gzdoom pid $($doom.Id))"
