package org.carbonaware.trace;

import org.carbonaware.core.Model.VmRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads VM allocation requests.
 *
 * <p>Expected CSV format (header required):
 * <pre>
 * id,pes,ram_mb,duration_h,arrival_h,deadline_h
 * 0,4,16384,8,0,20
 * </pre>
 *
 * <p>The generator in {@code scripts/prep_traces.py} produces this from Azure
 * Resource Central traces, and can also emit a synthetic stand-in so the harness
 * runs before the real data is downloaded.
 */
public final class RequestTrace {

    private RequestTrace() {}

    public static List<VmRequest> fromCsv(final Path csv) throws IOException {
        final List<String> lines = Files.readAllLines(csv);
        final List<VmRequest> out = new ArrayList<>(Math.max(0, lines.size() - 1));

        for (int i = 1; i < lines.size(); i++) {
            final String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            final String[] f = line.split(",");
            if (f.length < 6) {
                throw new IOException("Malformed row " + (i + 1) + " in " + csv + ": " + line);
            }
            out.add(new VmRequest(
                Long.parseLong(f[0].trim()),
                Integer.parseInt(f[1].trim()),
                Long.parseLong(f[2].trim()),
                Integer.parseInt(f[3].trim()),
                Integer.parseInt(f[4].trim()),
                Integer.parseInt(f[5].trim())
            ));
        }
        return out;
    }
}
