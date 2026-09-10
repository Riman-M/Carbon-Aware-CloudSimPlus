package org.carbonaware.trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Hourly carbon-intensity time series, one series per region.
 *
 * <p>Expected CSV format (header required):
 * <pre>
 * hour,region,ci_gco2_per_kwh
 * 0,IT-NO,312.4
 * 0,FR,58.1
 * ...
 * </pre>
 * where {@code hour} is hours elapsed since the start of the simulated period.
 *
 * <p>This deliberately uses <em>average</em> carbon intensity, matching the
 * Electricity Maps data used by Zanotto et al. Average CI answers "how clean is
 * the grid right now", not "what emissions does my marginal load cause". That
 * distinction is a known limitation and is recorded in {@link #isMarginal()}
 * so downstream reporting can state it explicitly rather than silently.
 */
public final class CarbonTrace {

    private final Map<String, double[]> seriesByRegion;
    private final int horizonHours;

    private CarbonTrace(final Map<String, double[]> seriesByRegion, final int horizonHours) {
        this.seriesByRegion = seriesByRegion;
        this.horizonHours = horizonHours;
    }

    public static CarbonTrace fromCsv(final Path csv) throws IOException {
        final Map<String, Map<Integer, Double>> raw = new LinkedHashMap<>();
        int maxHour = -1;

        final List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty()) {
            throw new IOException("Empty carbon trace: " + csv);
        }

        for (int i = 1; i < lines.size(); i++) {   // skip header
            final String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            final String[] f = line.split(",");
            if (f.length < 3) {
                throw new IOException("Malformed row " + (i + 1) + " in " + csv + ": " + line);
            }
            final int hour = Integer.parseInt(f[0].trim());
            final String region = f[1].trim();
            final double ci = Double.parseDouble(f[2].trim());

            raw.computeIfAbsent(region, k -> new HashMap<>()).put(hour, ci);
            maxHour = Math.max(maxHour, hour);
        }

        final int horizon = maxHour + 1;
        final Map<String, double[]> dense = new LinkedHashMap<>();

        for (final var e : raw.entrySet()) {
            final double[] arr = new double[horizon];
            Arrays.fill(arr, Double.NaN);
            e.getValue().forEach((h, ci) -> arr[h] = ci);

            // Fail loudly on gaps. Silent interpolation would let a partially
            // loaded trace produce plausible-looking but wrong emissions totals.
            for (int h = 0; h < horizon; h++) {
                if (Double.isNaN(arr[h])) {
                    throw new IOException(
                        "Region " + e.getKey() + " missing carbon intensity at hour " + h);
                }
            }
            dense.put(e.getKey(), arr);
        }

        return new CarbonTrace(dense, horizon);
    }

    /** Carbon intensity in gCO2eq/kWh for a region at a given simulation hour. */
    public double ciAt(final String region, final int hour) {
        final double[] s = seriesByRegion.get(region);
        if (s == null) {
            throw new IllegalArgumentException("Unknown region: " + region);
        }
        // Clamp past the end of the trace rather than throwing: jobs deferred
        // near the horizon edge would otherwise crash the run.
        final int h = Math.max(0, Math.min(hour, horizonHours - 1));
        return s[h];
    }

    /** Mean CI over [fromHour, fromHour+durationHours), used as a job's placement cost. */
    public double meanCiOverWindow(final String region, final int fromHour, final int durationHours) {
        if (durationHours <= 0) {
            throw new IllegalArgumentException("durationHours must be positive");
        }
        double sum = 0;
        for (int h = fromHour; h < fromHour + durationHours; h++) {
            sum += ciAt(region, h);
        }
        return sum / durationHours;
    }

    public Set<String> regions() {
        return Collections.unmodifiableSet(seriesByRegion.keySet());
    }

    public int horizonHours() {
        return horizonHours;
    }

    /**
     * Always false for now. Kept explicit so the limitation appears in the
     * results metadata instead of being buried in a paper's discussion section.
     */
    public boolean isMarginal() {
        return false;
    }
}
