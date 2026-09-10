@echo off
REM ===================================================================
REM  Runs 5 and 6, re-scaled. Run from the project root in cmd.exe.
REM  Runs 1-4 already completed; nothing here touches their output.
REM ===================================================================

setlocal
set PREP=python scripts\prep_traces.py
set CC=data\carboncast
set VMS=data\AzureVMTraces\vmtable_with_header.csv
set AZURE=--vm-src %VMS% --min-duration-h 2
set HOME_CELLS=-Dsweep.caps=84,350 -Dsweep.margins=6,48 -Dsweep.homeRegion=all

echo.
echo ############ 5/6  us / January / delay-insensitive / home sweep ############
REM --requests 100, not 1000. Delay-insensitive VMs average 340.7h of runtime
REM against 34h for the full mix, so 1000 of them demand 340,691 VM-hours and
REM need ~473 concurrent slots -- ten times what the capacity sweep provides.
REM Holding total demand constant instead of request count keeps this run
REM directly comparable to run 3: 100 x 340.7h = 34,070 VM-hours, 47.3 mean
REM concurrency, against run 3's 33,975 and 47.2. The capacity axis therefore
REM means the same thing in both, and any difference is the workload class
REM rather than a change in how hard the system is being pushed.
%PREP% --mode real --carbon-src %CC% --region-set us %AZURE% ^
  --out data --hours 720 --requests 100 --deadline-margin 48 ^
  --vm-category Delay-insensitive
if errorlevel 1 goto :failed
call mvn -q exec:java "-Dsweep.regionSets=us" %HOME_CELLS% "-Dexec.args=data results/us_h0_delayins_home.csv"
if errorlevel 1 goto :failed

echo.
echo ############ 6/6  eu / January / all categories / full sweep ############
REM Caps are 55/85/140/350 rather than 40/80/160/400: the eu set has 5 regions,
REM so a total of 40 is 8 per region and only 40 concurrent slots against the
REM Azure workload's 47.2 mean concurrency -- infeasible before the simulation
REM starts. These four give 86%/56%/34%/13% utilisation, matching the us runs.
%PREP% --mode real --carbon-src %CC% --region-set eu %AZURE% ^
  --out data --hours 720 --requests 1000 --deadline-margin 48
if errorlevel 1 goto :failed
call mvn -q exec:java "-Dsweep.regionSets=eu" "-Dsweep.caps=55,85,140,350" "-Dexec.args=data results/eu_h0_allcat_full.csv"
if errorlevel 1 goto :failed

echo.
echo ================= done =================
dir /b results\*.csv
goto :eof

:failed
echo.
echo *** FAILED at the step above. ***
exit /b 1
