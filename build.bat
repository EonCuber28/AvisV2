@echo off
rem Builds the standalone Avis executable for Windows into dist\. See build.ps1 for options (-Clean, -Console, -Trace, -Pgo, -GlslCheck).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
exit /b %ERRORLEVEL%
