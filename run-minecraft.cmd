@echo off
rem Starts the Minecraft 26.3 dev client with the passthrough mod (game dir mc\run, offline account).
rem It opens an empty world by itself and waits for GZDoom. Leave the window open (not minimized).
rem You start with empty hands, as in a new survival world: wood from Doom's wooden walls, a pickaxe from that,
rem stone and iron from its other walls.
rem   run-minecraft.cmd kit    start with the kit instead (diamond tools, bow, blocks, TNT, spawn eggs)
rem (a project that starts Minecraft through this script and has set PASSTHROUGH_KIT itself keeps what it set)
if not defined PASSTHROUGH_KIT set "PASSTHROUGH_KIT=none"
if /I "%~1"=="kit" set "PASSTHROUGH_KIT=full"
call "%~dp0tools\build-mc.cmd" runClient
