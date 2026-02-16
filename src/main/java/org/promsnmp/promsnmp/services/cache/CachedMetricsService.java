package org.promsnmp.promsnmp.services.cache;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.promsnmp.promsnmp.repositories.PrometheusMetricsRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class CachedMetricsService {

    private final AsyncLoadingCache<String, CachedMetrics> cache;
    private final PrometheusMetricsRepository repository;

    public CachedMetricsService(
            AsyncLoadingCache<String, CachedMetrics> metricsCache,
            @Qualifier("configuredMetricsRepo") PrometheusMetricsRepository repository) {
        this.cache = metricsCache;
        this.repository = repository;
    }

    public Optional<CachedMetrics> getRawMetrics(String instance) {
        try {
            CompletableFuture<CachedMetrics> future = cache.get(instance);
            CachedMetrics result = future.join();
            return Optional.ofNullable(result);
        } catch (Exception e) {
            log.error("Error retrieving cached metrics for instance: {}", instance, e);
            return Optional.empty();
        }
    }

    public Optional<CachedMetrics> refreshMetrics(String instance) {
        try {
            Optional<String> fresh = repository.readMetrics(instance);

            if (fresh.isPresent()) {
                CachedMetrics cachedMetrics = CachedMetrics.fresh(fresh.get());
                cache.put(instance, CompletableFuture.completedFuture(cachedMetrics));
                return Optional.of(cachedMetrics);
            }

            // SNMP walk failed — preserve last known good data, mark stale
            CompletableFuture<CachedMetrics> existing = cache.getIfPresent(instance);
            if (existing != null) {
                CachedMetrics previous = existing.getNow(null);
                if (previous != null) {
                    CachedMetrics stale = previous.markStale();
                    cache.put(instance, CompletableFuture.completedFuture(stale));
                    return Optional.of(stale);
                }
            }

            return Optional.empty();
        } catch (Exception e) {
            log.error("Error refreshing metrics for instance: {}", instance, e);
            return Optional.empty();
        }
    }

    public AsyncLoadingCache<String, CachedMetrics> getCache() {
        return cache;
    }
}
