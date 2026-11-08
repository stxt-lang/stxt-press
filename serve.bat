@echo off
rem Starts the server of the folder server\ with the jar of target\, building the jar first if there is none.
rem   serve.bat            the jar as it is
rem   serve.bat --build    the jar built again from the sources (without the tests)
rem Ctrl+C stops it.
setlocal
cd /d "%~dp0"

set "JAR="
if not "%~1"=="--build" for %%f in (target\stxt-press-*.jar) do set "JAR=%%f"
if not defined JAR (
    call mvn -q package -DskipTests || exit /b 1
    for %%f in (target\stxt-press-*.jar) do set "JAR=%%f"
)
java -jar "%JAR%" serve --config server\server.stxt
