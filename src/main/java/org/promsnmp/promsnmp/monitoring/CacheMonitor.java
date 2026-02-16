package org.promsnmp.promsnmp.monitoring;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.promsnmp.promsnmp.services.cache.CachedMetrics;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CacheMonitor {

    private final AsyncLoadingCache<String, CachedMetrics> metricsCache;

    public CacheMonitor(AsyncLoadingCache<String, CachedMetrics> metricsCache) {
        this.metricsCache = metricsCache;
    }

    @Scheduled(fixedRateString = "${CACHE_STATS_RATE_MILLIS:60000}")
    public void logCacheStats() {
        CacheStats stats = metricsCache.synchronous().stats();
        log.info("Metrics Cache Stats: {}", stats);
    }
}
