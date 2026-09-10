@echo off
REM ===================================================================
REM  Seed-robustness pass. Run from the project root in cmd.exe.
REM
REM  --seed drives the reservoir sample drawn from the 2.7M-row Azure
REM  vmtable, so each seed is a different draw of 1000 VMs from the same
REM  population. Everything else -- carbon traces, capacity, margins --
REM  is held fixed. Any spread across seeds is workload sampling noise,
REM  which is exactly what the non-monotonic margin behaviour at the
REM  saturated end needs to be tested against before it is reported.
REM
REM  Roughly 75 minutes.
REM ===================================================================

setlocal enabledelayedexpansion
set PREP=python scripts\prep_traces.py
set CC=data\carboncast
set AZURE=--vm-src data\AzureVMTraces\vmtable_with_header.csv --min-duration-h 2
set BASE=--out data --hours 720 --requests 1000 --deadline-margin 48

if not exist results\robust mkdir results\robust

for %%s in (1 2 3 4 5) do (
  echo.
  echo ############ seed %%s : us full sweep ############
  %PREP% --mode real --carbon-src %CC% --region-set us %AZURE% %BASE% --seed %%s
  if errorlevel 1 goto :failed
  call mvn -q exec:java "-Dsweep.regionSets=us" "-Dsweep.caps=56,84,140,350" ^
    "-Dexec.args=data results/robust/us_full_seed%%s.csv"
  if errorlevel 1 goto :failed

  echo.
  echo ############ seed %%s : us home sweep ############
  call mvn -q exec:java "-Dsweep.regionSets=us" "-Dsweep.caps=84,350" "-Dsweep.margins=6,48" ^
    "-Dsweep.homeRegion=all" "-Dexec.args=data results/robust/us_home_seed%%s.csv"
  if errorlevel 1 goto :failed
)

REM eu at three seeds only: it is the control for finding 3 (savings scale
REM with region-set degeneracy), and that gap is ~2x, far larger than any
REM plausible sampling noise, so it needs less evidence than the margin claim.
for %%s in (1 2 3) do (
  echo.
  echo ############ seed %%s : eu full sweep ############
  %PREP% --mode real --carbon-src %CC% --region-set eu %AZURE% %BASE% --seed %%s
  if errorlevel 1 goto :failed
  call mvn -q exec:java "-Dsweep.regionSets=eu" "-Dsweep.caps=55,85,140,350" ^
    "-Dexec.args=data results/robust/eu_full_seed%%s.csv"
  if errorlevel 1 goto :failed
)

echo.
echo ================= robustness pass complete =================
dir /b results\robust\*.csv
goto :eof

:failed
echo.
echo *** FAILED at the step above. ***
exit /b 1
