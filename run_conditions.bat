@echo off
REM ===================================================================
REM  Robustness conditions for Section 5.4: season and workload class.
REM
REM  These two conditions were last run before the bucket-parsing fix, so
REM  their figures in the paper do not come from the current code. This
REM  script regenerates them. Run from the project root in cmd.exe.
REM
REM  Roughly 35 minutes.
REM ===================================================================

setlocal enabledelayedexpansion
set PREP=python scripts\prep_traces.py
set CC=data\carboncast
set VMS=data\AzureVMTraces\vmtable_with_header.csv
set AZURE=--vm-src %VMS% --min-duration-h 2
set CELLS=-Dsweep.caps=84,350 -Dsweep.margins=6,48 -Dsweep.homeRegion=all

if not exist results\robust mkdir results\robust

REM --- Season -------------------------------------------------------
REM Three seeds rather than five: the seasonal comparison is against the
REM January run already in results\robust\, and the question is whether the
REM ordering survives, not what the coefficient is to two decimal places.
for %%s in (1 2 3) do (
  echo.
  echo ######## seed %%s : July window, all categories ########
  %PREP% --mode real --carbon-src %CC% --region-set us %AZURE% ^
    --out data --hours 720 --requests 1000 --deadline-margin 48 ^
    --start-hour 4320 --seed %%s
  if errorlevel 1 goto :failed
  call mvn -q exec:java "-Dsweep.regionSets=us" %CELLS% ^
    "-Dexec.args=data results/robust/us_july_home_seed%%s.csv"
  if errorlevel 1 goto :failed
)

REM --- Workload class -----------------------------------------------
REM 90 requests, not 1000. Delay-insensitive virtual machines average
REM 340.7 h of runtime against 31 h for the general mixture, so 1000 of them
REM would demand ten times the capacity the sweep provides. Holding total
REM demand near 30,600 VM-hours instead keeps capacity utilisation matched to
REM the main runs, so that any difference is attributable to the workload
REM class and not to a change in how hard the system is pushed.
for %%s in (1 2 3) do (
  echo.
  echo ######## seed %%s : delay-insensitive class, January ########
  %PREP% --mode real --carbon-src %CC% --region-set us %AZURE% ^
    --out data --hours 720 --requests 90 --deadline-margin 48 ^
    --vm-category Delay-insensitive --seed %%s
  if errorlevel 1 goto :failed
  call mvn -q exec:java "-Dsweep.regionSets=us" %CELLS% ^
    "-Dexec.args=data results/robust/us_delayins_home_seed%%s.csv"
  if errorlevel 1 goto :failed
)

echo.
echo ================= conditions complete =================
echo Check the reported demand in VM-hours for each run. If the
echo delay-insensitive runs land far from ~30,600 VM-hours, adjust
echo --requests rather than the capacity levels, so that utilisation
echo stays comparable with the main sweep.
dir /b results\robust\*.csv
goto :eof

:failed
echo.
echo *** FAILED at the step above. ***
exit /b 1
