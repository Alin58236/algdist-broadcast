@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

set "JAR=%CD%\target\bcastnode-1.0-SNAPSHOT.jar"
set "CONFIG_ARG=%~1"
set "MODE=%~2"
set "START_ID=%~2"
set "END_ID=%~3"

if "%CONFIG_ARG%"=="" goto :usage
if "%MODE%"=="" goto :usage

set "CONFIG=%CD%\%CONFIG_ARG%"

if not exist "%JAR%" (
  echo ERROR: Jar not found: "%JAR%"
  exit /b 2
)
if not exist "%CONFIG%" (
  echo ERROR: Config not found: "%CONFIG%"
  exit /b 2
)

if not exist runlogs mkdir runlogs

if /I "%MODE%"=="all" (
  set "START_ID=-2147483648"
  set "END_ID=2147483647"
) else (
  if "%END_ID%"=="" goto :usage
)

echo Config="%CONFIG%"
echo Jar="%JAR%"
echo Launching ids in range %START_ID%..%END_ID%
echo.

set /a FOUND=0

REM Read config lines, skip first, strip comments, take 3rd token as ID
for /f "usebackq skip=1 delims=" %%L in ("%CONFIG%") do (
  set "RAW=%%L"
  for /f "delims=#" %%A in ("!RAW!") do set "RAW=%%A"
  for /f "tokens=1,2,3" %%I in ("!RAW!") do (
    if not "%%K"=="" (
      set /a FOUND+=1
      set /a ID=%%K
      echo Found id=%%K

      if !ID! GEQ %START_ID% if !ID! LEQ %END_ID% (
        echo Starting node id !ID!
        start "" /B java -DLOG_NODE_INDEX=!ID! -jar "%JAR%" "%CONFIG%" !ID!
      ) else (
        echo Skipping !ID! (not in range)
      )
      echo.
    )
  )
)

if %FOUND% EQU 0 (
  echo ERROR: No node ids found (expected lines: ip port id)
  exit /b 2
)

exit /b 0

:usage
echo Usage:
echo   %~nx0 ^<config.txt^> all
echo   %~nx0 ^<config.txt^> ^<startId^> ^<endId^>
exit /b 2
