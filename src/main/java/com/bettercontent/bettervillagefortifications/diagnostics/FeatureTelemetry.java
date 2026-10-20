package com.bettercontent.bettervillagefortifications.diagnostics;

/** Pure observation of the owning feature's existing outcome, with no native reads. */
public final class FeatureTelemetry {
    private FeatureTelemetry() {}
    public static void generation(PlaytestLog log, boolean built) {
        log.event("walls", "village_wall.build", "server", built ? "built" : "deferred", built ? "native_generation" : "no_valid_footprint", null);
    }
}
