@echo off
rem Starts the server of this folder: stxt-press.jar with server.stxt, both next to this script, wherever the folder is.
rem Java 17 or later: that of JAVA_HOME if it is set, or the java of the PATH. Ctrl+C stops it.
setlocal
cd /d "%~dp0"

if not exist stxt-press.jar (
    echo start.bat: no stxt-press.jar in %CD%; in the project of stxt-press, mvn package puts it here 1>&2
    exit /b 1
)

set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
"%JAVA%" -jar stxt-press.jar start --config server.stxt
