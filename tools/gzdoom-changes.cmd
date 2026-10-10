@echo off
rem GZDoom's own source is not kept in this repository (it is GZDoom's): only what this project changes in it, in
rem gzdoom-changes\ (a patch against the commit it is built from, and the files that are new).
rem   tools\gzdoom-changes.cmd apply     a fresh clone of GZDoom at that commit + gzdoom-changes\ -> gzdoom\
rem                                      (tools\build-gzdoom.cmd does this by itself when there is no gzdoom\)
rem   tools\gzdoom-changes.cmd export    gzdoom\ (the working checkout) -> gzdoom-changes\   (before a commit)
setlocal enableextensions
set "ROOT=%~dp0.."
set "CHANGES=%ROOT%\gzdoom-changes"
set "CHECKOUT=%ROOT%\gzdoom"

if /i "%~1"=="apply" goto apply
if /i "%~1"=="export" goto export
echo usage: tools\gzdoom-changes.cmd apply ^| export
exit /b 1

:apply
if exist "%CHECKOUT%" (
	echo gzdoom\ is there already
	exit /b 1
)
set /p COMMIT=<"%CHANGES%\COMMIT"
git clone https://github.com/ZDoom/gzdoom.git "%CHECKOUT%" || exit /b 1
pushd "%CHECKOUT%"
git checkout %COMMIT% || goto failed
git apply "%CHANGES%\gzdoom.patch" || goto failed
popd
xcopy /E /Y /I /Q "%CHANGES%\new-files" "%CHECKOUT%" >nul || exit /b 1
echo gzdoom\ is GZDoom %COMMIT% with this project's changes
exit /b 0

:export
pushd "%CHECKOUT%" || exit /b 1
for /f %%C in ('git rev-parse HEAD') do set "COMMIT=%%C"
if exist "%CHANGES%" rmdir /S /Q "%CHANGES%"
mkdir "%CHANGES%"
git diff HEAD --output="%CHANGES%\gzdoom.patch" || goto failed
for /f "delims=" %%F in ('git ls-files --others --exclude-standard') do call :new "%%F"
popd
>"%CHANGES%\COMMIT" echo %COMMIT%
echo exported the changes against GZDoom %COMMIT%
exit /b 0

rem one file that is new in the checkout (not GZDoom's): copied under new-files\, except what was built
:new
set "FILE=%~1"
if /i "%FILE:~0,6%"=="build/" exit /b 0
set "FILE=%FILE:/=\%"
for %%P in ("%CHANGES%\new-files\%FILE%") do if not exist "%%~dpP" mkdir "%%~dpP"
copy /Y "%FILE%" "%CHANGES%\new-files\%FILE%" >nul
exit /b 0

:failed
popd
exit /b 1
