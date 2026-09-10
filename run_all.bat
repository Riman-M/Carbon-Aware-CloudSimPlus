@echo off
REM ===================================================================
REM  Full experiment matrix for the carbon-aware CloudSim Plus study.
REM
REM  Run from the project root in cmd.exe (not PowerShell):
REM      run_all.bat
REM
REM  Every run regenerates data\ in place, so the trace and the results
REM  always match. Each result file is named for its configuration and
REM  passed explicitly, so nothing overwrites anything.
REM
REM  Expect roughly 45-60 minutes total.
REM ===================================================================

setlocal
set PREP=python scripts\prep_traces.py
set CC=data\carboncast
set VMS=data\AzureVMTraces\vmtable_with_header.csv
set COMMON=--out data --hours 720 --requests 1000 --deadline-margin 48
set AZURE=--vm-src %VMS% --min-duration-h 2

REM Capacity sweeps differ by region set because per-region caps are the
REM total divided by the number of regions, and because the Azure workload
REM (47.2 mean concurrency) is far heavier than the synthetic one (21.0).
REM Multiples of the region count avoid losing slots to integer division.
set CAPS_US=-Dsweep.caps=56,84,140,350
set CAPS_EU=-Dsweep.caps=40,80,160,400
set HOME_CELLS=-Dsweep.caps=84,350 -Dsweep.margins=6,48 -Dsweep.homeRegion=all

if not exist results\archive mkdir results\archive
if exist results\*.csv move /Y results\*.csv results\archive\ >nul 2>&1

echo.
echo ############ 1/6  us / January / all categories / full sweep ############
%PREP% --mode real --carbon-src %CC% --region-set us %AZURE% %COMMON%
if errorlevel 1 goto :failed
call mvn -q exec:java "-Dsweep.regionSets=us" "%CAPS_US%" "-Dexec.args=data results/us_h0_allcat_full.csv"
if errorlevel 1 goto :failed

echo.
echo ############ 2/6  us / July / all categories / full sweep ############
%PREP% --mode real --carbon-src %CC% --region-set us %AZURE% %COMMON% --start-hour 4320
if errorlevel 1 goto :failed
call mvn -q exec:java "-Dsweep.regionSets=us" "%CAPS_US%" "-Dexec.args=data results/us_h4320_allcat_full.csv"
if errorlevel 1 goto :failed

echo.
echo ############ 3/6  us / January / all categories / home sweep ############
REM Home sweep runs WaitAwhile once per region. Without it, "time" is pinned
REM to regions.get(0) -- BPAT, the cleanest grid in the set -- and every
REM space-vs-time comparison silently reports the best case as the typical one.
%PREP% --mode real --carbon-src %CC% --region-set us %AZURE% %COMMON%
if errorlevel 1 goto :failed
call mvn -q exec:java "-Dsweep.regionSets=us" %HOME_CELLS% "-Dexec.args=data results/us_h0_allcat_home.csv"
if errorlevel 1 goto :failed

echo.
echo ############ 4/6  us / July / all categories / home sweep ############
%PREP% --mode real --carbon-src %CC% --region-set us %AZURE% %COMMON% --start-hour 4320
if errorlevel 1 goto :failed
call mvn -q exec:java "-Dsweep.regionSets=us" %HOME_CELLS% "-Dexec.args=data results/us_h4320_allcat_home.csv"
if errorlevel 1 goto :failed

echo.
echo ############ 5/6  us / January / delay-insensitive / home sweep ############
REM Azure labels only 159,497 of 2.7M VMs delay-insensitive. That is the
REM population a deadline-margin study can defend, so the finding has to
REM survive on it as well as on the full mix.
%PREP% --mode real --carbon-src %CC% --region-set us %AZURE% %COMMON% --vm-category Delay-insensitive
if errorlevel 1 goto :failed
call mvn -q exec:java "-Dsweep.regionSets=us" %HOME_CELLS% "-Dexec.args=data results/us_h0_delayins_home.csv"
if errorlevel 1 goto :failed

echo.
echo ############ 6/6  eu / January / all categories / full sweep ############
REM Kept as the degenerate control: SE at 39 gCO2/kWh against 183 for the
REM next cleanest makes "send everything to SE" trivially optimal, which is
REM exactly why the eu numbers look so much better than the us ones.
%PREP% --mode real --carbon-src %CC% --region-set eu %AZURE% %COMMON%
if errorlevel 1 goto :failed
call mvn -q exec:java "-Dsweep.regionSets=eu" "%CAPS_EU%" "-Dexec.args=data results/eu_h0_allcat_full.csv"
if errorlevel 1 goto :failed

echo.
echo ================= all runs complete =================
dir /b results\*.csv
goto :eof

:failed
echo.
echo *** FAILED at the step above. Nothing after it has run. ***
exit /b 1
