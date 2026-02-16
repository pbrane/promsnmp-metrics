package org.promsnmp.promsnmp.services.prometheus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.promsnmp.promsnmp.services.cache.CachedMetrics;
import org.promsnmp.promsnmp.services.cache.CachedMetricsService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnmpBasedMetricsServiceTest {

    private CachedMetricsService cachedMetricsService;
    private SnmpBasedMetricsService service;

    @BeforeEach
    void setUp() {
        cachedMetricsService = mock(CachedMetricsService.class);
        service = new SnmpBasedMetricsService(cachedMetricsService);
    }

    @Test
    void freshMetricsContainsScrapeSuccessOne() {
        CachedMetrics fresh = CachedMetrics.fresh("ifHCInOctets{instance=\"device1\"} 100\n");
        when(cachedMetricsService.getRawMetrics("device1")).thenReturn(Optional.of(fresh));

        Optional<String> result = service.getMetrics("device1", false);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("snmp_scrape_success 1");
        assertThat(result.get()).contains("snmp_scrape_duration_seconds");
    }

    @Test
    void staleMetricsContainsScrapeSuccessZero() {
        CachedMetrics stale = CachedMetrics.fresh("ifHCInOctets{instance=\"device1\"} 100\n").markStale();
        when(cachedMetricsService.getRawMetrics("device1")).thenReturn(Optional.of(stale));

        Optional<String> result = service.getMetrics("device1", false);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("snmp_scrape_success 0");
        assertThat(result.get()).contains("ifHCInOctets{instance=\"device1\"} 100");
    }

    @Test
    void emptyCacheReturnsEmpty() {
        when(cachedMetricsService.getRawMetrics("unknown")).thenReturn(Optional.empty());

        Optional<String> result = service.getMetrics("unknown", false);

        assertThat(result).isEmpty();
    }
}
