package org.promsnmp.promsnmp.services.cache;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CachedMetricsTest {

    @Test
    void freshSetsLastRefreshSucceededTrue() {
        Instant before = Instant.now();
        CachedMetrics cm = CachedMetrics.fresh("payload data");

        assertThat(cm.metricsPayload()).isEqualTo("payload data");
        assertThat(cm.lastRefreshSucceeded()).isTrue();
        assertThat(cm.collectedAt()).isBetween(before, Instant.now());
    }

    @Test
    void markStalePreservesPayloadAndTimestamp() {
        CachedMetrics fresh = CachedMetrics.fresh("payload data");
        CachedMetrics stale = fresh.markStale();

        assertThat(stale.metricsPayload()).isEqualTo("payload data");
        assertThat(stale.collectedAt()).isEqualTo(fresh.collectedAt());
        assertThat(stale.lastRefreshSucceeded()).isFalse();
    }
}
