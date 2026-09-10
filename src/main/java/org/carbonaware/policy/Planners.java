package org.carbonaware.policy;

import org.carbonaware.core.Model.Placement;
import org.carbonaware.core.Model.VmRequest;
import org.carbonaware.core.RegionLedger;
import org.carbonaware.trace.CarbonTrace;

import java.util.List;

/**
 * Scheduling policies under comparison.
 *
 * <p>All policies see the same {@link CarbonTrace} and the same {@link RegionLedger},
 * so differences in outcome come from the decision rule alone.
 */
public interface Planners {

    interface Planner {
        String name();
        Placement plan(VmRequest req, List<String> eligibleRegions, RegionLedger ledger);

        /**
         * The region this policy refuses to leave while the deadline is still
         * reachable, or {@code null} if it is free to place anywhere. Reported so
         * the results can state which policies were spatially constrained.
         */
        default String pinnedRegion() { return null; }
    }

    /**
     * Shared fallback: the earliest feasible slot anywhere, ignoring the deadline.
     *
     * <p><b>Changed:</b> this is now always searched over the full eligible region
     * set, including for policies that pin themselves to a home region while the
     * deadline is reachable. Restricting the fallback to the home region made
     * {@code WaitAwhile} structurally infeasible -- with one region's share of
     * capacity it could absorb only a fraction of the workload, so the run threw
     * rather than producing a result. The correct reading of "run to completion
     * once the allowed delay is over" (Wiesner et al.) is run <em>now, wherever</em>;
     * staying home past the deadline is not a defensible interpretation. The cost
     * of leaving still shows up, as deadline overrun and as regional concentration.
     */
    private static Placement fallback(final VmRequest req, final List<String> regions,
                                      final RegionLedger ledger, final CarbonTrace trace) {
        for (int h = req.arrivalH(); h < trace.horizonHours() + RegionLedger.OVERRUN_SLACK_H; h++) {
            String best = null;
            double bestCi = Double.MAX_VALUE;
            for (final String r : regions) {
                if (!ledger.canPlace(r, h, req.durationH())) continue;
                final double ci = trace.meanCiOverWindow(r, h, req.durationH());
                if (ci < bestCi) { bestCi = ci; best = r; }
            }
            if (best != null) {
                final int overrun = Math.max(0, (h + req.durationH()) - req.deadlineH());
                return new Placement(best, h, overrun);
            }
        }
        throw new IllegalStateException(
            "No feasible slot for request " + req.id() + " anywhere, even beyond the deadline. "
            + "Total capacity " + ledger.totalCapacity() + " concurrent VMs is too small for this "
            + "workload; raise the total-capacity sweep or reduce --requests.");
    }

    /**
     * Carbon-agnostic round robin: the baseline used by Zanotto et al. It is a
     * weak comparator by construction, and is included so that the share of any
     * headline percentage attributable to the baseline's weakness is visible.
     */
    final class RoundRobin implements Planner {
        private final CarbonTrace trace;
        private int cursor = 0;

        public RoundRobin(final CarbonTrace trace) { this.trace = trace; }

        @Override public String name() { return "round-robin"; }

        @Override
        public Placement plan(final VmRequest req, final List<String> regions, final RegionLedger ledger) {
            for (int i = 0; i < regions.size(); i++) {
                final String region = regions.get((cursor + i) % regions.size());
                if (ledger.canPlace(region, req.arrivalH(), req.durationH())) {
                    cursor = (cursor + i + 1) % regions.size();
                    return new Placement(region, req.arrivalH());
                }
            }
            return fallback(req, regions, ledger, trace);
        }
    }

    /** Space-shift only: cleanest eligible region at the arrival hour, no deferral. */
    final class GreedySpace implements Planner {
        private final CarbonTrace trace;

        public GreedySpace(final CarbonTrace trace) { this.trace = trace; }

        @Override public String name() { return "space"; }

        @Override
        public Placement plan(final VmRequest req, final List<String> regions, final RegionLedger ledger) {
            String best = null;
            double bestCi = Double.MAX_VALUE;
            for (final String region : regions) {
                if (!ledger.canPlace(region, req.arrivalH(), req.durationH())) continue;
                final double ci = trace.meanCiOverWindow(region, req.arrivalH(), req.durationH());
                if (ci < bestCi) { bestCi = ci; best = region; }
            }
            return best == null ? fallback(req, regions, ledger, trace)
                                : new Placement(best, req.arrivalH());
        }
    }

    /**
     * Time-shift only, after Wiesner et al.'s "Let's Wait Awhile": stay in the
     * origin region, defer to the cleanest feasible start hour within the deadline.
     * Once the deadline is unreachable it releases the region constraint via the
     * shared fallback.
     */
    final class WaitAwhile implements Planner {
        private final CarbonTrace trace;
        private final String homeRegion;

        public WaitAwhile(final CarbonTrace trace, final String homeRegion) {
            this.trace = trace;
            this.homeRegion = homeRegion;
        }

        // The home region is part of the identity of this policy, not a detail:
        // "time" pinned to the cleanest grid and "time" pinned to the dirtiest
        // are different experiments and must not share a row label.
        @Override public String name() { return "time@" + homeRegion; }

        @Override public String pinnedRegion() { return homeRegion; }

        @Override
        public Placement plan(final VmRequest req, final List<String> regions, final RegionLedger ledger) {
            final String region = regions.contains(homeRegion) ? homeRegion : regions.get(0);
            int bestHour = -1;
            double bestCi = Double.MAX_VALUE;
            for (int h = req.arrivalH(); h <= req.latestStartH(); h++) {
                if (!ledger.canPlace(region, h, req.durationH())) continue;
                final double ci = trace.meanCiOverWindow(region, h, req.durationH());
                if (ci < bestCi) { bestCi = ci; bestHour = h; }
            }
            // Full region set on fallback -- see the note on fallback() above.
            return bestHour < 0 ? fallback(req, regions, ledger, trace)
                                : new Placement(region, bestHour);
        }
    }

    /**
     * Combined space and time shift by exhaustive search over (region, start hour).
     *
     * <p>Zanotto et al. solve this as a binary integer program with PuLP/CBC, one
     * VM at a time against a greedily updated occupancy matrix. For a single
     * request the feasible set has |J|x|T| members, so enumeration returns the
     * identical optimum without a solver dependency and in microseconds.
     */
    final class SpaceTime implements Planner {
        private final CarbonTrace trace;

        public SpaceTime(final CarbonTrace trace) { this.trace = trace; }

        @Override public String name() { return "space+time"; }

        @Override
        public Placement plan(final VmRequest req, final List<String> regions, final RegionLedger ledger) {
            String bestRegion = null;
            int bestHour = -1;
            double bestCi = Double.MAX_VALUE;
            for (final String region : regions) {
                for (int h = req.arrivalH(); h <= req.latestStartH(); h++) {
                    if (!ledger.canPlace(region, h, req.durationH())) continue;
                    final double ci = trace.meanCiOverWindow(region, h, req.durationH());
                    if (ci < bestCi) { bestCi = ci; bestRegion = region; bestHour = h; }
                }
            }
            return bestRegion == null ? fallback(req, regions, ledger, trace)
                                      : new Placement(bestRegion, bestHour);
        }
    }
}
