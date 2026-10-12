@echo off
rem Start OpenPnP from this repo's build with the AI bridge enabled.
rem   start-openpnp.bat        -> real machine config (%USERPROFILE%\.openpnp2)
rem   start-openpnp.bat sim    -> simulation config (%USERPROFILE%\openpnp-sim)
setlocal
set CONFIG=%USERPROFILE%\.openpnp2
if /I "%1"=="sim" set CONFIG=%USERPROFILE%\openpnp-sim
if "%JAVA_HOME%"=="" set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot
cd /d "%~dp0.."
"%JAVA_HOME%\bin\java" --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED -DconfigDir="%CONFIG%" -DaiBridge.port=8999 -jar target\openpnp-gui-0.0.1-alpha-SNAPSHOT.jar
