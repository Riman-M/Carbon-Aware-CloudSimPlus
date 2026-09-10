package org.carbonaware.core;

import java.util.*;

/**
 * Tracks concurrent VM occupancy per region and enforces a per-region cap.
 *
 * <p>This is the {@code alloc[j][t]} matrix and constraint (3) from Zanotto et al.
 *
 * <p><b>Changed:</b> the cap is now per region rather than a single global constant.
 * The previous design confounded two axes: at cap {@code C}, a space-shifting policy
 * over {@code |J|} regions had {@code C x |J|} concurrent slots while a time-shifting
 * policy pinned to one region had {@code C}. Any emissions difference between them
 * was therefore partly just "more machines". The experiment now fixes a *total*
 * system capacity and divides it across the eligible regions, so every policy is
 * compared under the same amount of hardware.
 *
 * <p>The occupancy arrays extend past the trace horizon by {@link #OVERRUN_SLACK_H}
 * so that deadline-overrun fallbacks always have somewhere to land.
 */
public final class RegionLedger {

    /** Extra hours beyond the trace horizon reserved for deadline overruns. */
    public static final int OVERRUN_SLACK_H = 336; // two weeks

    private final Map<String, int[]> occupancy = new LinkedHashMap<>();
    private final Map<String, Integer> placedCount = new LinkedHashMap<>();
    private final Map<String, Integer> capByRegion;
    private final int limit;

    public RegionLedger(final Map<String, Integer> capByRegion, final int horizonHours) {
        this.capByRegion = Map.copyOf(capByRegion);
        this.limit = horizonHours + OVERRUN_SLACK_H;
        for (final String r : capByRegion.keySet()) {
            occupancy.put(r, new int[limit + 1]);
            placedCount.put(r, 0);
        }
    }

    public int capOf(final String region) {
        return capByRegion.getOrDefault(region, 0);
    }

    /** Sum of per-region caps: the total concurrent slots available to any policy. */
    public int totalCapacity() {
        return capByRegion.values().stream().mapToInt(Integer::intValue).sum();
    }

    public boolean canPlace(final String region, final int startHour, final int durationH) {
        final int[] occ = occupancy.get(region);
        final int cap = capOf(region);
        if (occ == null || cap <= 0 || startHour < 0 || startHour + durationH > limit) return false;
        for (int h = startHour; h < startHour + durationH; h++) {
            if (occ[h] >= cap) return false;
        }
        return true;
    }

    public void place(final String region, final int startHour, final int durationH) {
        final int[] occ = occupancy.get(region);
        for (int h = startHour; h < startHour + durationH; h++) occ[h]++;
        placedCount.merge(region, 1, Integer::sum);
    }

    /** Peak simultaneous VMs across all regions, over the whole ledger. */
    public int peakConcurrency() {
        int peak = 0;
        for (int h = 0; h <= limit; h++) {
            int now = 0;
            for (final int[] occ : occupancy.values()) now += occ[h];
            peak = Math.max(peak, now);
        }
        return peak;
    }

    public int placedIn(final String region) { return placedCount.get(region); }

    /**
     * Share of all placements landing in the single busiest region, in [0,1].
     *
     * <p>This quantifies the "black hole" effect directly: a value near 1 means
     * the policy has concentrated the entire workload into one grid, which is the
     * regime where reported savings look largest and real-world feasibility is
     * lowest. Reporting it alongside emissions keeps that trade-off visible.
     */
    public double maxRegionShare() {
        final int total = placedCount.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return 0;
        return placedCount.values().stream().mapToInt(Integer::intValue).max().orElse(0) / (double) total;
    }

    public Map<String, Integer> placementsByRegion() { return Map.copyOf(placedCount); }
}
