@echo off
rem Builds the patched GZDoom (gzdoom\build\RelWithDebInfo\gzdoom.exe) with Visual Studio 2022. The first time it
rem fetches what it needs: GZDoom's source (git) and the official release's sound libraries.
rem No vcpkg: GZDoom's repo carries prebuilt ZMusic and libvpx import libraries for MSVC (gzdoom\bin\windows).
setlocal enableextensions

set "CMAKE_DIR=%ProgramFiles%\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin"
if not exist "%CMAKE_DIR%\cmake.exe" set "CMAKE_DIR=%ProgramFiles(x86)%\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin"
where cmake >nul 2>nul || set "PATH=%CMAKE_DIR%;%PATH%"

rem GZDoom's source with this project's changes on it, if it isn't here yet
if not exist "%~dp0..\gzdoom" (
	call "%~dp0gzdoom-changes.cmd" apply || goto failed
)
rem the official release, for the sound libraries the game needs beside it
if not exist "%~dp0gzdoom-release\openal32.dll" (
	echo downloading the GZDoom 4.14.2 release for its sound libraries...
	if not exist "%~dp0gzdoom-release" mkdir "%~dp0gzdoom-release"
	curl -L -f -o "%~dp0gzdoom-release\release.zip" https://github.com/ZDoom/gzdoom/releases/download/g4.14.2/gzdoom-4-14-2-windows.zip || goto failed
	tar -xf "%~dp0gzdoom-release\release.zip" -C "%~dp0gzdoom-release" || goto failed
	del "%~dp0gzdoom-release\release.zip"
)

if not exist "%~dp0..\gzdoom\build" mkdir "%~dp0..\gzdoom\build"
pushd "%~dp0..\gzdoom\build"

if not exist gzdoom.sln (
	cmake -A x64 -S .. -B . || goto fail
)
cmake --build . --config RelWithDebInfo -- -maxcpucount -verbosity:minimal || goto fail

popd
rem the sound libraries and data the game needs beside it come from the official release (tools\gzdoom-release)
for %%F in (openal32.dll sndfile.dll) do if not exist "%~dp0..\gzdoom\build\RelWithDebInfo\%%F" if exist "%~dp0gzdoom-release\%%F" copy /Y "%~dp0gzdoom-release\%%F" "%~dp0..\gzdoom\build\RelWithDebInfo\" >nul
for %%D in (soundfonts fm_banks) do if not exist "%~dp0..\gzdoom\build\RelWithDebInfo\%%D" if exist "%~dp0gzdoom-release\%%D" xcopy /E /I /Y "%~dp0gzdoom-release\%%D" "%~dp0..\gzdoom\build\RelWithDebInfo\%%D" >nul
echo BUILD OK
exit /b 0

:fail
popd
:failed
echo BUILD FAILED
exit /b 1
