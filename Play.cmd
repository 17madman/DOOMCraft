@echo off
rem Play: pick any WAD. Minecraft is started (if it isn't running), then Doom on that WAD at its own title screen
rem (New Game, Load Game, Save Game); Minecraft closes when you quit Doom. See tools\play.ps1.
rem   Play.cmd                 a file window opens: pick your WAD
rem   drag a WAD onto this     that WAD
rem   Play.cmd last            the same one as last time
rem   Play.cmd kit             start with the kit (diamond tools, bow, blocks, TNT) instead of a wooden pickaxe
powershell -NoProfile -ExecutionPolicy Bypass -STA -File "%~dp0tools\play.ps1" %*
