package org.promsnmp.promsnmp.services.cache;

import java.time.Instant;

public record CachedMetrics(
        String metricsPayload,
        Instant collectedAt,
        boolean lastRefreshSucceeded
) {

    public static CachedMetrics fresh(String payload) {
        return new CachedMetrics(payload, Instant.now(), true);
    }

    public CachedMetrics markStale() {
        return new CachedMetrics(metricsPayload, collectedAt, false);
    }
}
