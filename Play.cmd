@echo off
rem Play: pick any WAD and Doom starts on it, at its own title screen (New Game, Load Game, Save Game). See tools\play.ps1.
rem   Play.cmd                 a file window opens: pick your WAD
rem   drag a WAD onto this     that WAD
rem   Play.cmd last            the same one as last time
rem Start Minecraft (with the mod) first.
powershell -NoProfile -ExecutionPolicy Bypass -STA -File "%~dp0tools\play.ps1" %*
