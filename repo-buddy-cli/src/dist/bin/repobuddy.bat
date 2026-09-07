@echo off
setlocal
set "JAR=%~dp0..\lib\repobuddy.jar"

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_BIN (
  where java >nul 2>nul
  if errorlevel 1 (
    echo RepoBuddy requires Java 17 or newer. Set JAVA_HOME or add java to PATH. 1>&2
    exit /b 127
  )
  set "JAVA_BIN=java"
)

if not exist "%JAR%" (
  echo RepoBuddy installation is incomplete: "%JAR%" was not found. 1>&2
  exit /b 2
)

"%JAVA_BIN%" -jar "%JAR%" %*
exit /b %ERRORLEVEL%
