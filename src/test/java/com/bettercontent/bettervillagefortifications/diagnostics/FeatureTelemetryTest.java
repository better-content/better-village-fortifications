package com.bettercontent.bettervillagefortifications.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class FeatureTelemetryTest {
    @Test public void nativeOutcomeIsNotMisreportedOrExpandedIntoPrivatePayload() {
        List<String> records = new ArrayList<>();
        PlaytestLog log = new PlaytestLog("better_village_fortifications", new PlaytestLog.Sink() {
            public boolean enabled(String category, String level) { return true; }
            public void write(String category, String level, String message) { records.add(message); }
        }, () -> 0L, new Properties());
        var context = new PlaytestLog.Context("server", "minecraft:overworld", 42, "", "10,64,-3");
        FeatureTelemetry.outcome(log, context, "queued", "chunk_load");
        FeatureTelemetry.generation(log, context, false);
        if (records.size() != 2 || !records.get(0).contains("result=queued reason=chunk_load")
                || !records.get(1).contains("result=deferred reason=no_valid_footprint"))
            throw new AssertionError(records.toString());
        for (String record : records) {
            if (!record.contains("dimension=minecraft:overworld") || !record.contains("tick=42")
                    || !record.contains("pos=10,64,-3") || record.contains("player=")
                    || record.contains("payload=") || record.contains("seed=")) throw new AssertionError(record);
        }
    }
    public static void main(String[] args) { new FeatureTelemetryTest().nativeOutcomeIsNotMisreportedOrExpandedIntoPrivatePayload(); }
}
