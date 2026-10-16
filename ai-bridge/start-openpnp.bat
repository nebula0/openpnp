@echo off
rem Start OpenPnP from this repo's build with the AI bridge enabled.
rem   start-openpnp.bat        -> real machine config (%USERPROFILE%\.openpnp2)
rem   start-openpnp.bat sim    -> simulation config (%USERPROFILE%\openpnp-sim)
setlocal
set CONFIG=%USERPROFILE%\.openpnp2
if /I "%1"=="sim" set CONFIG=%USERPROFILE%\openpnp-sim
if "%JAVA_HOME%"=="" set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot
cd /d "%~dp0.."
rem Exceptions printed by OpenPnP (e.g. during shutdown) go to stderr, not to OpenPnP.log.
"%JAVA_HOME%\bin\java" --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED -DconfigDir="%CONFIG%" -DaiBridge.port=8999 -jar target\openpnp-gui-0.0.1-alpha-SNAPSHOT.jar 2>> "%CONFIG%\log\stderr.log"
rem The config folder is a git repo: keep what OpenPnP saved when it closed, so that changes
rem made in the GUI are recorded too. Nothing happens if nothing changed.
if exist "%CONFIG%\.git" (
  git -C "%CONFIG%" add -A
  git -C "%CONFIG%" commit -q -m "OpenPnP closed" >nul 2>nul
)
