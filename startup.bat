@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM Always treat this .bat's folder as project root
cd /d "%~dp0"

set "JAR=%CD%\target\bcastnode-1.0-SNAPSHOT.jar"
set "CONFIG_ARG=%~1"
set "MODE=%~2"
set "START=%~2"
set "END=%~3"

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

REM Count nodes: skip first line, ignore blank and comments
set /a M=0
set /a LINE=0
for /f "usebackq delims=" %%L in ("%CONFIG%") do (
  set /a LINE+=1
  if !LINE! GTR 1 (
    set "RAW=%%L"
    for /f "delims=#" %%A in ("!RAW!") do set "RAW=%%A"
    for /f "tokens=1" %%T in ("!RAW!") do set /a M+=1
  )
)

if /I "%MODE%"=="all" (
  set /a START=0
  set /a END=M-1
) else (
  if "%END%"=="" goto :usage
)

echo Config="%CONFIG%"
echo Jar="%JAR%"
echo Nodes in config (M)=%M%
echo Launching indices %START%..%END%

for /L %%i in (%START%,1,%END%) do (
  if %%i GEQ 0 if %%i LSS %M% (
    echo Starting node %%i
    REM Important: no cmd /c, start java directly
    start "" /B java -DNODE_INDEX=%%i -jar "%JAR%" "%CONFIG%" %%i
  ) else (
    echo Skipping %%i (out of range)
  )
)

exit /b 0

:usage
echo Usage:
echo   %~nx0 ^<config.txt^> all
echo   %~nx0 ^<config.txt^> ^<startIdx^> ^<endIdx^>
exit /b 2
