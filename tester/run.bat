@echo off
cd /d "%~dp0"
java -ea -cp "lib/*;lib" com.rfl.RflPluginLauncher
if errorlevel 1 pause
