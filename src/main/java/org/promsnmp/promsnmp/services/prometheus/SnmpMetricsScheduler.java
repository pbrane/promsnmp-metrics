package org.promsnmp.promsnmp.services.prometheus;

import lombok.extern.slf4j.Slf4j;
import org.promsnmp.promsnmp.model.NetworkDevice;
import org.promsnmp.promsnmp.repositories.jpa.NetworkDeviceRepository;
import org.promsnmp.promsnmp.services.cache.CachedMetricsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class SnmpMetricsScheduler {

    private final NetworkDeviceRepository deviceRepository;

    private final ConcurrentHashMap<String, AtomicBoolean> collectionLocks = new ConcurrentHashMap<>();
    private final Executor snmpExecutor;

    private final CachedMetricsService cachedMetricsService;

    public SnmpMetricsScheduler(NetworkDeviceRepository deviceRepository,
                                @Qualifier("snmpDiscoveryExecutor") Executor snmpExecutor,
                                CachedMetricsService cachedMetricsService) {

        this.deviceRepository = deviceRepository;
        this.snmpExecutor = snmpExecutor;
        this.cachedMetricsService = cachedMetricsService;
    }

    @Scheduled(fixedRateString = "${collection.interval:30000}")
    public void scheduledMetricCollection() {
        log.info("Starting scheduled SNMP metric collection...");

        List<NetworkDevice> devices = deviceRepository.findAllWithAgents();

        devices.stream()
                .filter(device -> device.resolvePrimaryAgent() != null)
                .map(NetworkDevice::getSysName)
                .map(this::collectMetricsAsync)
                .forEach(future -> future.exceptionally(ex -> {
                    log.error("Exception during async metric collection", ex);
                    return null;
                }));
    }

    @Async("snmpMetricsExecutor")
    public CompletableFuture<Void> collectMetricsAsync(String instance) {
        AtomicBoolean lock = collectionLocks.computeIfAbsent(instance, k -> new AtomicBoolean(false));

        if (!lock.compareAndSet(false, true)) {
            log.warn("Metric collection for instance '{}' is already in progress. Skipping this run.", instance);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                log.debug("Collecting metrics for instance: {}", instance);
                cachedMetricsService.refreshMetrics(instance)
                        .ifPresent(cm -> {
                            if (!cm.lastRefreshSucceeded()) {
                                log.warn("SNMP collection failed for '{}'; serving stale data from {}", instance, cm.collectedAt());
                            }
                        });
            } catch (Exception ex) {
                log.error("Error during SNMP metric collection for instance: {}", instance, ex);
            } finally {
                lock.set(false);
            }
        }, snmpExecutor);
    }
}
