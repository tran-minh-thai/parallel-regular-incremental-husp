@echo off
REM ============================================================================
REM  P-RIncHUSP - run the full experiment suite (S1-S11) on Windows.
REM
REM  Usage:
REM    - double-click this file, or
REM    - open a Command Prompt in the project folder and type:  RUN_ALL_WINDOWS.bat
REM
REM  Arguments (optional, any order):
REM    <number>   heap in GB, e.g.  RUN_ALL_WINDOWS.bat 32   (default: 3/4 of RAM)
REM    absolute   run the suite the study reports: each dataset's regularity bound is its
REM               declared constant B (see DatasetCatalog), not the relative rho*N:
REM               RUN_ALL_WINDOWS.bat absolute    (combine:  RUN_ALL_WINDOWS.bat 24 absolute)
REM    resume     continue an interrupted run, skipping work already finished:
REM               RUN_ALL_WINDOWS.bat resume      (combine:  RUN_ALL_WINDOWS.bat 32 resume)
REM
REM  Requires JDK 11+ and Maven on PATH. The first run needs internet so Maven can
REM  fetch its build plugins into ~/.m2; later runs work offline.
REM
REM  Datasets are not in this repository. Fetch them first - see RUNNING.md, or run
REM  fetch_datasets.sh under Git Bash / WSL.
REM ============================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0"
title P-RIncHUSP - Full experiment

echo.
echo ============================================================
echo   P-RIncHUSP  -  full experiment suite
echo ============================================================
echo.

REM ---- 1) Check that Maven and Java are on PATH ----
where mvn >nul 2>&1
if errorlevel 1 (
  echo [ERROR] 'mvn' not found on PATH.
  echo         Install Maven ^(and JDK 11+^), then reopen the Command Prompt.
  goto :fail
)
where java >nul 2>&1
if errorlevel 1 (
  echo [ERROR] 'java' not found on PATH. Install JDK 11+ and reopen the Command Prompt.
  goto :fail
)

REM ---- 2) Read arguments: 'resume' anywhere, heap = first numeric argument ----
set "PROGARGS="
echo %*| findstr /i "absolute" >nul 2>&1 && set "PROGARGS=--absolute"
echo %*| findstr /i "resume" >nul 2>&1 && set "PROGARGS=%PROGARGS% --resume"
set "RESUMEARG="
if defined PROGARGS set RESUMEARG=-D"prog.args=%PROGARGS%"

set "HEAPG="
for %%A in (%*) do (echo %%A| findstr /r "^[0-9][0-9]*$" >nul 2>&1 && if not defined HEAPG set "HEAPG=%%A")
if defined HEAPG goto :heapdone
for /f "usebackq delims=" %%r in (`powershell -NoProfile -Command "[math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB)"`) do set "RAMGB=%%r"
if not defined RAMGB set "RAMGB=8"
REM Keep the heap below physical RAM: if it approaches RAM the JVM swaps instead of
REM failing, which makes every timing meaningless.
set /a HEAPG=RAMGB*3/4
if %HEAPG% GTR 32 set HEAPG=32
if %HEAPG% LSS 4 set HEAPG=4
:heapdone

echo Directory  : %CD%
echo CPU cores  : %NUMBER_OF_PROCESSORS%
echo Heap (Xmx) : %HEAPG%g     ^| Stack (Xss): 4m
echo %*| findstr /i "absolute" >nul 2>&1 && echo Mode       : ABSOLUTE ^(declared per-dataset bound B^)
echo %*| findstr /i "resume" >nul 2>&1 && echo Resume     : ON ^(skips work already finished^)
echo.
java -version
echo.

REM ---- 3) Check the benchmark datasets are present ----
set "MISSING="
for %%D in (SIGN LEVIATHAN BIBLE C8T1S5I8N5K FIFA KOSARAK) do (
  if not exist "datasets\%%D_seq.txt" set "MISSING=!MISSING! %%D"
)
if not "%MISSING%"=="" (
  echo [WARNING] Missing dataset files:%MISSING%
  echo           Missing datasets are SKIPPED; the suite still runs on the rest.
  echo           To fetch them all, see the download command in RUNNING.md.
  echo.
)

REM ---- 4) Prepare results\ and a timestamp for the log name ----
if not exist results mkdir results
for /f "usebackq delims=" %%t in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"`) do set "STAMP=%%t"
set "LOG=results\run_log_all_%STAMP%.txt"

REM ---- 5) Compile ----
echo [Step 1/2] mvn -q compile  ...
call mvn -q compile
if errorlevel 1 (
  echo [ERROR] Compilation failed. See the messages above.
  goto :fail
)
echo           Compiled.
echo.

REM ---- 6) Run the suite. Maven runs in this console so -D arguments pass through
REM         correctly; PowerShell's Tee-Object copies the output to the log file. ----
echo [Step 2/2] Running test.ExperimentOfficial
echo            Log     : %LOG%
echo            Results : results\run_^<timestamp^>_^<hash^>\  (a DONE file marks completion)
echo            Note    : this can take several hours. If it stops, rerun with 'resume'.
echo.
mvn -q exec:exec -Dexec.mainClass=test.ExperimentOfficial -DheapSize=%HEAPG%g -DstackSize=4m %RESUMEARG% 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath '%LOG%'"

echo.
set "RC=1"
for /d %%f in (results\run_*) do if exist "%%f\DONE" set "RC=0"
echo ============================================================
if "%RC%"=="0" (
  echo   FINISHED ^(DONE marker present^). Result files:
  for /d %%f in (results\run_*) do if exist "%%f\DONE" echo       %%f\results.csv
) else (
  echo   No DONE marker -^> the run did not finish, or it hit an error.
  echo   Continue with:  RUN_ALL_WINDOWS.bat %HEAPG% resume
)
echo   Full log : %LOG%
echo ============================================================
echo.
pause
exit /b %RC%

:fail
echo.
pause
exit /b 1
