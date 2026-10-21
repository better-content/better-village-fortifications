package com.bettercontent.bettervillagefortifications.diagnostics;

/** Pure observation of the owning feature's existing outcome, with no native reads. */
public final class FeatureTelemetry {
    private FeatureTelemetry() {}
    public static void outcome(PlaytestLog log, PlaytestLog.Context context, String result, String reason) {
        log.debug("walls", "village_wall.build", context, result, reason, null);
    }
    public static void generation(PlaytestLog log, PlaytestLog.Context context, boolean built) {
        outcome(log, context, built ? "built" : "deferred", built ? "native_generation" : "no_valid_footprint");
    }
}
