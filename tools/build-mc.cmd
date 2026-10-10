@echo off
rem Runs Gradle for the Fabric mod with the project's own JDK 25 and Gradle home (nothing is installed system-wide).
rem   tools\build-mc.cmd build       -> mc\build\libs\passthrough-*.jar
rem   tools\build-mc.cmd runClient   -> starts the Minecraft dev client (game dir mc\run, offline account)
setlocal enableextensions
for /d %%J in ("%~dp0jdk-25*") do set "JAVA_HOME=%%J"
set "GRADLE_USER_HOME=%~dp0gradle-home"
set "TASK=%*"
if "%TASK%"=="" set "TASK=build"
pushd "%~dp0..\mc"
call "%~dp0..\mc\gradlew.bat" %TASK%
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
