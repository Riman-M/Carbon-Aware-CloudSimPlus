#!/usr/bin/env python3
"""
Summarise the seed-robustness pass.

Reads results/robust/*.csv and reports, for each of the three findings, the
across-seed mean and standard deviation. The question in every case is whether
the effect is large relative to workload sampling noise -- a finding that moves
less than its own seed spread is not a finding.

Usage:
    python scripts/analyze_robust.py [results/robust]
"""
import sys
import glob
import os
from pathlib import Path

import pandas as pd

# Measured over the 720h window starting 2020-01-01, from the trace summary.
HOME_CI = {"BPAT": 63, "NYISO": 209, "ISNE": 297, "CISO": 299,
           "PJM": 341, "ERCO": 354, "FPL": 359}
HOME_DIURNAL = {"BPAT": 14.3, "NYISO": 25.6, "ISNE": 14.5, "CISO": 52.7,
                "PJM": 7.8, "ERCO": 24.0, "FPL": 21.1}


def load(pattern):
    frames = []
    for path in sorted(glob.glob(pattern)):
        seed = os.path.basename(path).split("seed")[1].split(".")[0]
        df = pd.read_csv(path)
        df["seed"] = int(seed)
        frames.append(df)
    return pd.concat(frames, ignore_index=True) if frames else None


def pct_reduction(df, policy, baseline="round-robin"):
    """Reduction in attributed emissions vs the carbon-agnostic baseline."""
    keys = ["seed", "total_capacity", "deadline_margin_h"]
    p = df[df.policy == policy].set_index(keys).attributed_gco2
    b = df[df.policy == baseline].set_index(keys).attributed_gco2
    return (100 * (1 - p / b)).rename("pct")


def finding_1_capacity_vs_margin(df):
    print("\n" + "=" * 70)
    print("FINDING 1  capacity dominates deadline margin")
    print("=" * 70)
    st = df[df.policy == "space+time"]
    g = st.groupby(["total_capacity", "deadline_margin_h"]).attributed_gco2
    tab = (g.mean() / 1000).unstack()
    sd = (g.std() / 1000).unstack()
    print("\nattributed kgCO2, mean across seeds (sd in brackets):")
    for cap in tab.index:
        row = "  cap=%-4d " % cap
        for m in tab.columns:
            row += f"{tab.loc[cap, m]:7.0f} ({sd.loc[cap, m]:4.0f})  "
        print(row)
    print("  margins:  " + "  ".join(f"{m:>13d}" for m in tab.columns))

    # The effect sizes that matter, each measured per seed so the spread is real.
    caps = sorted(df.total_capacity.unique())
    margins = sorted(df.deadline_margin_h.unique())
    cap_eff, marg_eff = [], []
    for s, gs in st.groupby("seed"):
        piv = gs.pivot_table(index="total_capacity", columns="deadline_margin_h",
                             values="attributed_gco2")
        cap_eff.append(100 * (1 - piv.loc[caps[-1], margins[-1]] / piv.loc[caps[0], margins[-1]]))
        marg_eff.append(100 * (1 - piv.loc[caps[0], margins[-1]] / piv.loc[caps[0], margins[0]]))
    ce, me = pd.Series(cap_eff), pd.Series(marg_eff)
    print(f"\n  capacity effect (cap {caps[0]}->{caps[-1]} at margin {margins[-1]}): "
          f"{ce.mean():.1f}% +/- {ce.std():.1f}")
    print(f"  margin effect   (margin {margins[0]}->{margins[-1]} at cap {caps[0]}): "
          f"{me.mean():.1f}% +/- {me.std():.1f}")
    print("  -> margin at the saturated end is only a finding if it clears its own spread.")


def finding_2_home_region(df):
    print("\n" + "=" * 70)
    print("FINDING 2  time-shifting is dominated by home-region selection")
    print("=" * 70)
    t = df[df.policy.str.startswith("time@")].copy()
    t["home"] = t.policy.str.split("@").str[1]
    t["ci"] = t.home.map(HOME_CI)
    t["diurnal"] = t.home.map(HOME_DIURNAL)

    rows = []
    for (cap, margin, seed), g in t.groupby(["total_capacity", "deadline_margin_h", "seed"]):
        rows.append({
            "cap": cap, "margin": margin, "seed": seed,
            "corr_ci": g.ci.corr(g.attributed_gco2),
            "corr_diurnal": g.diurnal.corr(g.attributed_gco2),
        })
    r = pd.DataFrame(rows)
    print("\n  corr(home carbon intensity, emissions) and corr(home diurnal, emissions):")
    for (cap, margin), g in r.groupby(["cap", "margin"]):
        print(f"  cap={cap:<4d} m={margin:<3d}  "
              f"CI {g.corr_ci.mean():+.3f} +/- {g.corr_ci.std():.3f}   "
              f"diurnal {g.corr_diurnal.mean():+.3f} +/- {g.corr_diurnal.std():.3f}")

    # A single WaitAwhile number is only reproducible if the home spread is small.
    print("\n  WaitAwhile spread across homes (attributed kgCO2), per cell:")
    for (cap, margin), g in t.groupby(["total_capacity", "deadline_margin_h"]):
        by_seed = g.groupby("seed").attributed_gco2
        print(f"  cap={cap:<4d} m={margin:<3d}  "
              f"best {by_seed.min().mean()/1000:6.0f}   "
              f"median {g.groupby('seed').attributed_gco2.median().mean()/1000:6.0f}   "
              f"worst {by_seed.max().mean()/1000:6.0f}")

    space = df[df.policy == "space"].groupby(["total_capacity", "deadline_margin_h"]).attributed_gco2.mean()
    print("\n  for comparison, 'space' in the same cells:")
    for k, v in space.items():
        print(f"  cap={k[0]:<4d} m={k[1]:<3d}  {v/1000:6.0f}")


def finding_3_region_set(us, eu):
    print("\n" + "=" * 70)
    print("FINDING 3  reported savings scale with region-set degeneracy")
    print("=" * 70)
    print("\n  space-shifting reduction vs round-robin, by capacity utilisation:")
    print(f"  {'util':>6}  {'eu':>16}  {'us':>16}")
    # Capacity totals differ between sets (5 vs 7 regions), so pair by rank.
    us_caps = sorted(us.total_capacity.unique())
    eu_caps = sorted(eu.total_capacity.unique())
    for i, (ec, uc) in enumerate(zip(eu_caps, us_caps)):
        e = pct_reduction(eu[eu.total_capacity == ec], "space")
        u = pct_reduction(us[us.total_capacity == uc], "space")
        print(f"  {['86%','56%','34%','14%'][i]:>6}  "
              f"{e.mean():6.1f}% +/- {e.std():4.1f}  "
              f"{u.mean():6.1f}% +/- {u.std():4.1f}")
    print("\n  -> if the eu column exceeds the us column by more than the two spreads")
    print("     combined, region-set composition is doing the work, not the scheduler.")


def main():
    base = Path(sys.argv[1] if len(sys.argv) > 1 else "results/robust")
    us_full = load(str(base / "us_full_seed*.csv"))
    us_home = load(str(base / "us_home_seed*.csv"))
    eu_full = load(str(base / "eu_full_seed*.csv"))

    if us_full is None:
        raise SystemExit(f"no us_full_seed*.csv under {base}")

    invalid = us_full[~us_full.placement_valid]
    print(f"loaded: us_full {us_full.seed.nunique()} seeds, "
          f"us_home {us_home.seed.nunique() if us_home is not None else 0} seeds, "
          f"eu_full {eu_full.seed.nunique() if eu_full is not None else 0} seeds")
    if len(invalid):
        print(f"WARNING: {len(invalid)} invalid rows excluded; "
              "those cells did not run the schedule under test.")
        us_full = us_full[us_full.placement_valid]

    finding_1_capacity_vs_margin(us_full)
    if us_home is not None:
        finding_2_home_region(us_home[us_home.placement_valid])
    if eu_full is not None:
        finding_3_region_set(us_full, eu_full[eu_full.placement_valid])
    print()


if __name__ == "__main__":
    main()
