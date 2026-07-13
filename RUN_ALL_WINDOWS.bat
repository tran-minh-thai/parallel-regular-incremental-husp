@echo off
REM ============================================================================
REM  P-RIncHUSP - Chay TOAN BO thuc nghiem (official suite S1-S4) tren Windows.
REM
REM  Cach dung:
REM    - Nhap DOUBLE-CLICK file nay, HOAC
REM    - Mo Command Prompt (cmd) tai thu muc du an va go:  RUN_ALL_WINDOWS.bat
REM
REM  Tham so (o bat ky vi tri, deu tuy chon):
REM    <so>       heap GB, vi du:  RUN_ALL_WINDOWS.bat 32   (mac dinh: tu dong theo RAM)
REM    resume     chay TIEP lan chay dang do (bo qua phan da xong):  RUN_ALL_WINDOWS.bat resume
REM               (ket hop:  RUN_ALL_WINDOWS.bat 32 resume)
REM
REM  Yeu cau: da cai JDK 11+ va Maven (mvn) tren PATH. Lan chay dau CAN INTERNET
REM  de Maven tai cac plugin build ve ~/.m2 (cac lan sau chay offline duoc).
REM ============================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0"
title P-RIncHUSP - Full experiment

echo.
echo ============================================================
echo   P-RIncHUSP  -  Chay TOAN BO thuc nghiem (official suite)
echo ============================================================
echo.

REM ---- 1) Kiem tra Maven va Java co tren PATH khong ----
where mvn >nul 2>&1
if errorlevel 1 (
  echo [LOI] Khong tim thay 'mvn' tren PATH.
  echo        Hay cai Maven ^(va JDK 11+^) roi mo lai Command Prompt.
  goto :fail
)
where java >nul 2>&1
if errorlevel 1 (
  echo [LOI] Khong tim thay 'java' tren PATH. Hay cai JDK 11+ roi mo lai cmd.
  goto :fail
)

REM ---- 2) Doc tham so: 'resume' (bat ky vi tri) + heap (tham so SO dau tien) ----
set "RESUMEARG="
echo %*| findstr /i "resume" >nul 2>&1 && set "RESUMEARG=-Dprog.args=--resume"

set "HEAPG="
for %%A in (%*) do (echo %%A| findstr /r "^[0-9][0-9]*$" >nul 2>&1 && if not defined HEAPG set "HEAPG=%%A")
if defined HEAPG goto :heapdone
for /f "usebackq delims=" %%r in (`powershell -NoProfile -Command "[math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB)"`) do set "RAMGB=%%r"
if not defined RAMGB set "RAMGB=8"
set /a HEAPG=RAMGB*3/4
if %HEAPG% GTR 32 set HEAPG=32
if %HEAPG% LSS 4 set HEAPG=4
:heapdone

echo Thu muc    : %CD%
echo So CPU core: %NUMBER_OF_PROCESSORS%
echo Heap (Xmx) : %HEAPG%g     ^| Stack (Xss): 4m
if defined RESUMEARG echo Resume     : ON ^(bo qua phan da chay xong^)
echo.
java -version
echo.

REM ---- 3) Kiem tra du lieu official suite ----
set "MISSING="
for %%D in (SIGN LEVIATHAN BIBLE FIFA KOSARAK) do (
  if not exist "datasets\%%D_seq.txt" set "MISSING=!MISSING! %%D"
)
if not "%MISSING%"=="" (
  echo [CANH BAO] Thieu file dataset:%MISSING%
  echo            Cac dataset thieu se bi BO QUA; suite van chay tiep.
  echo            Nho copy DAY DU thu muc datasets\ tu may nguon.
  echo.
)

REM ---- 4) Chuan bi thu muc results + moc thoi gian cho ten log ----
if not exist results mkdir results
for /f "usebackq delims=" %%t in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"`) do set "STAMP=%%t"
set "LOG=results\run_log_all_%STAMP%.txt"

REM ---- 5) Bien dich ----
echo [Buoc 1/2] mvn -q compile  ...
call mvn -q compile
if errorlevel 1 (
  echo [LOI] Bien dich that bai. Xem thong bao loi phia tren.
  goto :fail
)
echo            Bien dich OK.
echo.

REM ---- 6) Chay suite: mvn chay TRONG CMD (truyen tham so -D... dung), tee dau ra ra log ----
echo [Buoc 2/2] Chay test.ExperimentOfficial
echo            Log      : %LOG%
echo            Ket qua  : results\run_^<thoi-gian^>_^<hash^>\  (co DONE khi chay xong)
echo            Luu y    : co the mat NHIEU GIO. Neu loi/tat may, chay lai voi 'resume'.
echo.
mvn -q exec:exec -Dexec.mainClass=test.ExperimentOfficial -DheapSize=%HEAPG%g -DstackSize=4m %RESUMEARG% 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath '%LOG%'"

echo.
set "RC=1"
for /d %%f in (results\run_*) do if exist "%%f\DONE" set "RC=0"
echo ============================================================
if "%RC%"=="0" (
  echo   HOAN TAT ^(co marker DONE^). File ket qua:
  for /d %%f in (results\run_*) do if exist "%%f\DONE" echo       %%f\results.csv
) else (
  echo   CHUA co marker DONE -^> chua chay xong hoac gap loi.
  echo   Chay lai voi 'resume' de tiep tuc:  RUN_ALL_WINDOWS.bat %HEAPG% resume
)
echo   Log day du : %LOG%
echo ============================================================
echo.
pause
exit /b %RC%

:fail
echo.
pause
exit /b 1
