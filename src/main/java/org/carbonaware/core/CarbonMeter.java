package org.carbonaware.core;

import org.carbonaware.trace.CarbonTrace;
import org.cloudsimplus.core.Simulation;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.hosts.Host;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Integrates host power against regional carbon intensity over simulated time.
 *
 * <p>Emissions follow {@code E = sum_t Power(t) * CI(t)}, sampled on each clock
 * tick and multiplied by the elapsed interval.
 *
 * <p><b>Three accounting bases</b>, because the choice materially changes the
 * headline number and must be made explicitly rather than by default.
 *
 * <ul>
 *   <li><b>total</b> -- every provisioned host draws power for the whole window,
 *       idle or not. This is what the provider's meter reads. It is also
 *       <em>policy-invariant by construction</em>: the same fleet exists in the
 *       same regions regardless of where VMs land, so idle emissions are a
 *       constant and two policies can report identical totals while scheduling
 *       completely differently. Reporting only this hides the effect entirely.</li>
 *   <li><b>dynamic</b> -- power above idle only. Policy-sensitive, but flatters
 *       scheduling by discarding the idle floor that scheduling cannot move.</li>
 *   <li><b>attributed</b> -- full power (idle + dynamic) of hosts that are
 *       actually running at least one VM; empty hosts contribute nothing. This is
 *       the consumer-side basis: a customer pays for, and is responsible for, the
 *       machines their workload occupies, not the provider's idle capacity. It is
 *       both policy-sensitive and inclusive of the idle floor of the hardware
 *       actually in use, and it matches the consumer-side premise of the paper.</li>
 * </ul>
 *
 * <p>Units are converted in exactly one place: watts x seconds gives joules,
 * joules / 3.6e6 gives kWh, kWh x gCO2eq/kWh gives grams.
 */
public final class CarbonMeter {

    private static final double JOULES_PER_KWH = 3.6e6;

    private final CarbonTrace trace;
    private final Map<Datacenter, String> regionOf;

    private final Map<String, Double> gramsByRegion = new LinkedHashMap<>();
    private final Map<String, Double> dynamicGramsByRegion = new LinkedHashMap<>();
    private final Map<String, Double> attributedGramsByRegion = new LinkedHashMap<>();
    private double totalKwh = 0.0;
    private double dynamicKwh = 0.0;
    private double attributedKwh = 0.0;
    private double lastTick = 0.0;
    private long peakActiveHosts = 0;

    public CarbonMeter(final CarbonTrace trace, final Map<Datacenter, String> regionOf) {
        this.trace = trace;
        this.regionOf = regionOf;
        regionOf.values().forEach(r -> {
            gramsByRegion.put(r, 0.0);
            dynamicGramsByRegion.put(r, 0.0);
            attributedGramsByRegion.put(r, 0.0);
        });
    }

    /** Registers the sampling hook. Call once, before {@code simulation.start()}. */
    public void attach(final Simulation simulation) {
        simulation.addOnClockTickListener(info -> sample(info.getTime()));
    }

    private void sample(final double now) {
        final double deltaSeconds = now - lastTick;
        if (deltaSeconds <= 0) return;
        lastTick = now;

        // The whole interval is attributed to the CI of the hour it began in. With a
        // scheduling interval well under an hour this is negligible; with a coarse
        // one it is not, so the interval must stay fine.
        final int hour = (int) (now / 3600.0);
        long activeNow = 0;

        for (final var entry : regionOf.entrySet()) {
            final String region = entry.getValue();
            final double ci = trace.ciAt(region, hour);

            double joules = 0.0;
            double dynJoules = 0.0;
            double attrJoules = 0.0;
            for (final Host host : entry.getKey().getHostList()) {
                // Measured SPECpower curve rather than the host's linear model.
                final double util = host.getCpuPercentUtilization();
                final double watts = SpecPower.watts(util);
                final double idleWatts = SpecPower.idleWatts();
                final boolean active = !host.getVmList().isEmpty();
                if (active) activeNow++;

                joules     += watts * deltaSeconds;
                dynJoules  += Math.max(0.0, watts - idleWatts) * deltaSeconds;
                attrJoules += active ? watts * deltaSeconds : 0.0;
            }

            final double kwh     = joules / JOULES_PER_KWH;
            final double dynKwh  = dynJoules / JOULES_PER_KWH;
            final double attrKwh = attrJoules / JOULES_PER_KWH;
            totalKwh      += kwh;
            dynamicKwh    += dynKwh;
            attributedKwh += attrKwh;
            gramsByRegion.merge(region, kwh * ci, Double::sum);
            dynamicGramsByRegion.merge(region, dynKwh * ci, Double::sum);
            attributedGramsByRegion.merge(region, attrKwh * ci, Double::sum);
        }
        peakActiveHosts = Math.max(peakActiveHosts, activeNow);
    }

    public double totalGrams() {
        return gramsByRegion.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public double dynamicGrams() {
        return dynamicGramsByRegion.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public double attributedGrams() {
        return attributedGramsByRegion.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public double totalKwh()      { return totalKwh; }
    public double dynamicKwh()    { return dynamicKwh; }
    public double attributedKwh() { return attributedKwh; }

    /** Peak hosts simultaneously running at least one VM. Zero means nothing ran. */
    public long peakActiveHosts() { return peakActiveHosts; }

    public Map<String, Double> gramsByRegion() { return Map.copyOf(gramsByRegion); }
}
