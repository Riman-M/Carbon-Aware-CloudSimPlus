package org.carbonaware.core;

/** Value types shared across policies and the simulator. */
public final class Model {

    private Model() {}

    /**
     * A VM allocation request, following the tuple in Zanotto et al. section 4:
     * VM := (MinCPU, MinRAM, D, DL, ML). Latency (ML) is not carried here because
     * eligible regions are filtered by policy before scheduling; the filtered set
     * is passed to the planner separately.
     */
    public record VmRequest(
        long id, int pes, long ramMb, int durationH, int arrivalH, int deadlineH
    ) {
        public VmRequest {
            if (durationH <= 0) throw new IllegalArgumentException("durationH must be > 0");
            if (deadlineH < arrivalH + durationH) {
                throw new IllegalArgumentException(
                    "Infeasible request " + id + ": deadline " + deadlineH +
                    " < arrival " + arrivalH + " + duration " + durationH);
            }
        }

        /** Latest hour the VM can start and still finish on time. */
        public int latestStartH() { return deadlineH - durationH; }

        /** Hours of scheduling freedom. Zero means no time-shifting is possible. */
        public int slackH() { return latestStartH() - arrivalH; }
    }

    /**
     * A scheduling decision.
     *
     * <p>Every policy must return a placement for every request. Rejecting is not
     * allowed, because comparing total emissions across policies that admit
     * different workloads is meaningless -- a policy can "win" simply by running
     * less work. When no slot satisfies the capacity cap within the deadline, the
     * planner falls back to the earliest feasible slot beyond it and records the
     * overrun in {@code deadlineOverrunH}, which is then reported as a separate
     * QoS metric rather than being hidden inside the carbon number.
     *
     * @param region           target region key
     * @param startHour        absolute simulation hour at which the VM starts
     * @param deadlineOverrunH hours by which completion misses the deadline (0 if met)
     */
    public record Placement(String region, int startHour, int deadlineOverrunH) {
        public Placement(String region, int startHour) { this(region, startHour, 0); }
        public boolean meetsDeadline() { return deadlineOverrunH == 0; }
    }
}
