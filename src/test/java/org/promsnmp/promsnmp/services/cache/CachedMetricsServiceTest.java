package org.promsnmp.promsnmp.services.cache;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.promsnmp.promsnmp.repositories.PrometheusMetricsRepository;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CachedMetricsServiceTest {

    private PrometheusMetricsRepository repository;
    private CachedMetricsService service;
    private AsyncLoadingCache<String, CachedMetrics> cache;

    @BeforeEach
    void setUp() {
        repository = mock(PrometheusMetricsRepository.class);
        cache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .maximumSize(100)
                .recordStats()
                .buildAsync((CacheLoader<String, CachedMetrics>) key ->
                        repository.readMetrics(key)
                                .map(CachedMetrics::fresh)
                                .orElse(null));
        service = new CachedMetricsService(cache, repository);
    }

    @Test
    void firstLoadSuccessReturnsFreshMetrics() {
        when(repository.readMetrics("device1")).thenReturn(Optional.of("ifHCInOctets 100\n"));

        Optional<CachedMetrics> result = service.getRawMetrics("device1");

        assertThat(result).isPresent();
        assertThat(result.get().metricsPayload()).isEqualTo("ifHCInOctets 100\n");
        assertThat(result.get().lastRefreshSucceeded()).isTrue();
    }

    @Test
    void firstLoadFailureReturnsEmpty() {
        when(repository.readMetrics("unknown")).thenReturn(Optional.empty());

        Optional<CachedMetrics> result = service.getRawMetrics("unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void staleOnFailurePreservesPayload() {
        when(repository.readMetrics("device1")).thenReturn(Optional.of("ifHCInOctets 100\n"));
        service.refreshMetrics("device1");

        // Now simulate SNMP failure
        when(repository.readMetrics("device1")).thenReturn(Optional.empty());
        Optional<CachedMetrics> result = service.refreshMetrics("device1");

        assertThat(result).isPresent();
        assertThat(result.get().metricsPayload()).isEqualTo("ifHCInOctets 100\n");
        assertThat(result.get().lastRefreshSucceeded()).isFalse();
    }

    @Test
    void refreshRecoveryReturnsFreshData() {
        // Initial success
        when(repository.readMetrics("device1")).thenReturn(Optional.of("ifHCInOctets 100\n"));
        service.refreshMetrics("device1");

        // Failure — goes stale
        when(repository.readMetrics("device1")).thenReturn(Optional.empty());
        service.refreshMetrics("device1");

        // Recovery — fresh data again
        when(repository.readMetrics("device1")).thenReturn(Optional.of("ifHCInOctets 200\n"));
        Optional<CachedMetrics> result = service.refreshMetrics("device1");

        assertThat(result).isPresent();
        assertThat(result.get().metricsPayload()).isEqualTo("ifHCInOctets 200\n");
        assertThat(result.get().lastRefreshSucceeded()).isTrue();
    }
}
