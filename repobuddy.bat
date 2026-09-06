@echo off
setlocal
java -jar "%~dp0repo-buddy-cli\build\libs\repo-buddy-cli.jar" %*
exit /b %ERRORLEVEL%
