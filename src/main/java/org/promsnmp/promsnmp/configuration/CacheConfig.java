package org.promsnmp.promsnmp.configuration;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.promsnmp.promsnmp.repositories.PrometheusMetricsRepository;
import org.promsnmp.promsnmp.services.cache.CachedMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class CacheConfig {

    @Value("${COLLECTION_INTERVAL:30000}")
    private long collectionInterval;

    @Value("${CACHE_EXPIRE_MILLIS:86400000}")
    private long cacheExpireMillis;

    @Value("${CACHE_ENTRY_CNT:10000}")
    private int cacheEntries;

    @Bean
    public AsyncLoadingCache<String, CachedMetrics> metricsCache(
            @Qualifier("configuredMetricsRepo") PrometheusMetricsRepository repository,
            @Qualifier("snmpMetricsExecutor") Executor executor) {

        long refreshAfterWrite = collectionInterval * 2;

        log.info("Metrics cache: refreshAfterWrite={}ms, expireAfterWrite={}ms, maxSize={}",
                refreshAfterWrite, cacheExpireMillis, cacheEntries);

        return Caffeine.newBuilder()
                .refreshAfterWrite(refreshAfterWrite, TimeUnit.MILLISECONDS)
                .expireAfterWrite(cacheExpireMillis, TimeUnit.MILLISECONDS)
                .maximumSize(cacheEntries)
                .recordStats()
                .executor(executor)
                .buildAsync((CacheLoader<String, CachedMetrics>) instance -> {
                    log.debug("Cache loader triggered for instance: {}", instance);
                    return repository.readMetrics(instance)
                            .map(CachedMetrics::fresh)
                            .orElse(null);
                });
    }
}
