@echo off
setlocal
java -jar "%~dp0repo-buddy-mcp\build\libs\repo-buddy-mcp.jar" %*
exit /b %ERRORLEVEL%
