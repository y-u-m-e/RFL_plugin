@echo off
cd /d "%~dp0"
rem Use the Java that comes with RuneLite, so testers need nothing extra installed.
set "JAVA=%LOCALAPPDATA%\RuneLite\jre\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"
"%JAVA%" -ea -cp "lib/*;lib" com.rfl.RflPluginLauncher
if errorlevel 1 pause
