# Carbon-Aware Scheduling on CloudSim Plus

A CloudSim Plus harness for comparing carbon-aware VM scheduling policies across
regions, under explicit capacity and deadline constraints.

Deliberately unbranded. `CarbonSim` collides with EDF's emissions-trading game
(carbonsim.org), Bancor's `carbon-simulator`, and Hans, Zhao & Lee's *CarbonSim*
(IGSC 2026, `github.com/pittcps/carbonsim`) in this exact area. `CarbonBench` is
Benson et al.'s published atmospheric-transport benchmark (JAMES 2025). A
descriptive name cannot collide confusingly and is more discoverable to anyone
searching for CloudSim Plus carbon extensions. A branded name is worth having
once there is a *method* to name — see the follow-on work.

## Paper A scope

**Claim.** Reported savings for carbon-aware scheduling are largely determined by
two parameters that papers frequently leave unstated: the per-region concurrency
cap and the deadline margin. We quantify that dependence for non-preemptible VM
workloads and show how much of the headline number survives once the baseline,
the admitted workload, and the measurement window are held fixed.

**Deliberately out of scope** (these are Paper B):
- forecasting and forecast error — everything here uses perfect foresight, so the
  capacity and deadline effects are not confounded with prediction quality
- risk-aware or chance-constrained objectives
- marginal carbon intensity

**Positioning.** CarbonFlex (Hanafy et al., 2025) sweeps cluster capacity and
delay for *elastic parallel batch jobs* on AWS ParallelCluster. This study covers
*non-preemptible, fixed-size VMs* across *multiple regions* in simulation — a
different system model, and the one used by Zanotto et al. (FGCS 2026), whose
results motivate the question.

## Three design decisions that make the comparison valid

1. **Every policy places every request.** Policies may not reject. When the cap
   makes all in-deadline slots infeasible, the planner falls back past the
   deadline and the overrun is reported separately. Comparing total emissions
   across policies that admit different workloads is meaningless — a policy can
   "win" by running less work. This bug was present in the first version and
   inflated time-shift savings to 63% while it silently dropped 74% of requests.

2. **Identical measurement window.** `sim.terminateAt()` is set from the trace
   horizon, so a policy that defers work does not accrue extra idle energy simply
   by running longer. Without this, deferral looks expensive for the wrong reason.

3. **Total *and* dynamic emissions.** Idle power is real and stays in the total,
   but it is also reported separately so it cannot swamp the differences that
   scheduling actually controls. A fleet sized far above the cap makes every
   policy look identical; `Simulator.hostsFor()` sizes hosts to the cap instead.

## Layout

```
core/CarbonMeter.java     power x CI integration; the only place units convert
core/RegionLedger.java    alloc[j][t] and the M_j cap; reports concentration
core/Metrics.java         everything reported per experiment cell
core/Model.java           VmRequest / Placement
policy/Planners.java      round-robin, space, time, space+time, shared fallback
trace/CarbonTrace.java    hourly CI per region
trace/RequestTrace.java   VM requests
Simulator.java            runs one cell
ExperimentRunner.java     sweeps policy x region-set x cap x margin -> CSV
scripts/analyze.py        CSV -> figures and LaTeX table
```

CloudSim Plus hooks used: `broker.setDatacenterMapper(...)` for space-shifting,
`vm.setSubmissionDelay(...)` for time-shifting. Both are stock API — the
framework is a dependency, not a fork.

## Running

```bash
python3 scripts/prep_traces.py --out data --hours 720 --requests 1000
mvn compile
mvn exec:java
python3 scripts/analyze.py
```

## Before this is a paper

- [ ] **Real carbon traces.** The synthetic generator exists so the pipeline runs;
      synthetic CI is not evidence about real grids. Electricity Maps free tier
      now gives 5 historical datasets per account — enough for one year across a
      handful of zones. Note their coverage/pricing has tightened recently.
- [ ] **Real VM traces.** Azure Resource Central, `github.com/Azure/AzurePublicDataset`.
- [ ] **Real power figures.** `HOST_MAX_WATTS` / `HOST_STATIC_WATTS` are
      placeholders. Substitute SPECpower for the Dell XR8620T; the idle/peak
      ratio drives the consolidation-vs-distribution trade-off.
- [ ] **Latency region set.** Zanotto's third policy is implemented as a
      region-set filter but needs real cloudping latency tables.
- [ ] **Repeat runs.** Round-robin has an arbitrary starting cursor; report
      variance across seeds rather than single runs.
- [ ] **Average vs marginal CI.** `CarbonTrace.isMarginal()` returns false and
      this is printed in the run header. It must appear as a stated limitation.

## Known limitations to state in the paper

Non-preemptible, fixed-size VMs with uniform power draw over their lifetime —
inherited from Zanotto et al.'s model. No data-transfer emissions for
space-shifting. No embodied carbon. Simulation only, no deployment.
