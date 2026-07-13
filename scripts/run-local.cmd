@echo off
setlocal

call gradlew.bat --no-daemon bootRun --args="--spring.profiles.active=local"
exit /b %ERRORLEVEL%
