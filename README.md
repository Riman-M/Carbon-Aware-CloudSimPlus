# carbon-aware-cloudsimplus

Simulation harness and analysis code for a study of what determines reported
savings in carbon-aware virtual machine scheduling.

The study isolates three parameters that evaluations of carbon-aware scheduling
seldom report — capacity utilisation, the origin region assigned to a
time-shifting policy, and the composition of the candidate region set — and
measures the effect of each on reported emissions reductions, holding the
scheduler, workload and hardware fixed.

## What is here

```
src/main/java/org/carbonaware/   CloudSim Plus harness
  Simulator.java                 builds and runs one experimental cell
  ExperimentRunner.java          sweeps the factor grid, writes results CSV
  core/CarbonMeter.java          power x carbon intensity integration
  core/RegionLedger.java         per-region concurrency ledger
  core/SpecPower.java            measured SPECpower host power curve
  core/Metrics.java              per-cell result record
  policy/Planners.java           round-robin, space, WaitAwhile, space+time
  trace/                         carbon and request trace loaders
scripts/
  prep_traces.py                 builds traces from CarbonCast and Azure inputs
  analyze_robust.py              summarises the seed-robustness results
  make_figures.py                generates the paper figures
results/                         result CSVs behind every figure and table
```

## Requirements

- Java 17 and Maven
- Python 3 with pandas and matplotlib
- CloudSim Plus 8.5.7 (resolved by Maven)

## Input data

Neither dataset is redistributed here; both are published and freely available.

Fetch both with:

```
python scripts/fetch_data.py --all
```

The script clones CarbonCast shallowly and copies out the region files, then
downloads and decompresses the Azure trace. It skips anything already present,
so it is safe to re-run.

**Carbon intensity.** CarbonCast, https://github.com/carbonfirst/CarbonCast.
Per-region `<REGION>_lifecycle_emissions.csv` files go in `data/carboncast/`.
The study uses the seven US regions BPAT, CISO, ERCO, ISNE, NYISO, PJM and FPL,
and the five European regions SE, ES, DE, NL and PL.

**Virtual machine requests.** Azure Public Dataset,
https://github.com/Azure/AzurePublicDataset. `vmtable.csv` goes in
`data/AzureVMTraces/`. The file is roughly 0.76 GB and is deliberately excluded
from version control.

The study uses release V2 (2019), whose core and memory fields are *buckets*
rather than exact values, with the highest recorded as `>24` cores and `>64` GB.
`prep_traces.py` maps these to 30 and 70, following the dataset's own published
analysis, so virtual machines in the top buckets are modelled at a lower bound
on their true size. Rows whose core or memory fields cannot be read are counted
and reported rather than skipped silently. Pass `--azure-version v1` to
`fetch_data.py` for the 2017 trace, which records exact values.

## Reproducing the results

Generate a trace, then run the sweep. Both steps write into `data/`, so they
must be run in order.

```
python scripts/prep_traces.py --mode real --carbon-src data/carboncast \
    --region-set us --vm-src data/AzureVMTraces/vmtable.csv \
    --out data --hours 720 --requests 1000 --deadline-margin 48 \
    --min-duration-h 2

mvn compile
mvn exec:java "-Dsweep.regionSets=us" "-Dsweep.caps=56,84,140,350"
```

The sweep axes are overridable with `-Dsweep.caps`, `-Dsweep.margins`,
`-Dsweep.regionSets` and `-Dsweep.homeRegion=all`, the last of which runs the
time-shifting policy once per candidate origin region.

`run_all.bat` and `run_robust.bat` run the full experiment matrix and the
five-seed robustness pass respectively. The robustness pass takes roughly 75
minutes.

```
python scripts/analyze_robust.py results/robust
python scripts/make_figures.py results/robust figures
```

## Notes on the harness

Every experimental cell verifies, after execution, that each virtual machine ran
in the region its planner selected and that its workload completed inside the
measurement window. Cells that fail this check are reported as invalid and
should be excluded from analysis; the `placement_valid` column in the results
CSV records the outcome.

Emissions are reported on three bases — total, dynamic and attributed — because
the choice determines what a comparison is able to detect. In a fixed fleet the
total basis is invariant to placement and cannot distinguish policies. See the
paper for the definitions.

Host power uses the published SPECpower_ssj2008 curve for the Dell PowerEdge
XR8620T with Intel Xeon Gold 6433N, the platform modelled by Zanotto et al.
Because the simulated host matches the benchmarked one, the curve is used
without rescaling.

## Citation

CITATION TO BE ADDED ON PUBLICATION.

## License

Apache License 2.0, matching CloudSim Plus. See `LICENSE`.