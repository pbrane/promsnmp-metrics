package org.promsnmp.promsnmp.controllers;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.promsnmp.promsnmp.services.cache.CachedMetrics;
import org.promsnmp.promsnmp.utils.ProtocolOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This Controller is for management of the PromSnmp instance
 */

@Tag(name = "PromSNMP", description = "Endpoint for Managing PromSNMP itself")
@RestController
@RequestMapping("/promsnmp")
public class PromSnmpController {

    private final AsyncLoadingCache<String, CachedMetrics> metricsCache;

    private final ThreadPoolTaskExecutor snmpDiscoveryExecutor;
    private final ThreadPoolTaskExecutor snmpMetricsExecutor;

    public PromSnmpController(AsyncLoadingCache<String, CachedMetrics> metricsCache,
            @Qualifier("snmpDiscoveryExecutor") ThreadPoolTaskExecutor discoveryExecutor,
            @Qualifier("snmpMetricsExecutor") ThreadPoolTaskExecutor metricsExecutor) {
        this.metricsCache = metricsCache;
        this.snmpDiscoveryExecutor = discoveryExecutor;
        this.snmpMetricsExecutor = metricsExecutor;
    }

    @Operation(summary = "Hello World", description = "Simple \"is alive\" test")
    @GetMapping("/hello")
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok("Hello World");
    }

    @Operation(summary = "Evict Metric Cache", description = "Clears the cache of Metrics in memory")
    @GetMapping("/evictCache")
    public String evictAll() {
        metricsCache.synchronous().invalidateAll();
        return "Cache cleared.";
    }

    @Operation(summary = "Authorization Protocols", description = "List the supported SNMP Authorization Protocols")
    @GetMapping("/authProtocols")
    public List<String> getAuthProtocols() {
        return ProtocolOptions.getSupportedAuthProtocols();
    }

    @Operation(summary = "Privacy Protocols", description = "List the supported SNMP Privacy Protocols")
    @GetMapping("/privProtocols")
    public List<String> getPrivProtocols() {
        return ProtocolOptions.getSupportedPrivProtocols();
    }

    @Operation(summary = "Thread Pools", description = "List the current status of the PromSNMP Threadpools")
    @GetMapping("/threadPools")
    public Map<String, Object> threadPoolStats() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("snmpDiscoveryExecutor", Map.of(
                "active", snmpDiscoveryExecutor.getActiveCount(),
                "poolSize", snmpDiscoveryExecutor.getPoolSize(),
                "maxPoolSize", snmpDiscoveryExecutor.getMaxPoolSize(),
                "queueSize", snmpDiscoveryExecutor.getThreadPoolExecutor().getQueue().size()
        ));

        stats.put("snmpMetricsExecutor", Map.of(
                "active", snmpMetricsExecutor.getActiveCount(),
                "poolSize", snmpMetricsExecutor.getPoolSize(),
                "maxPoolSize", snmpMetricsExecutor.getMaxPoolSize(),
                "queueSize", snmpMetricsExecutor.getThreadPoolExecutor().getQueue().size()
        ));

        return stats;
    }

}
