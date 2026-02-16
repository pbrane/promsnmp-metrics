package org.promsnmp.promsnmp.services.prometheus;

import org.promsnmp.promsnmp.services.PrometheusMetricsService;
import org.promsnmp.promsnmp.services.cache.CachedMetrics;
import org.promsnmp.promsnmp.services.cache.CachedMetricsService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service("SnmpSvc")
public class SnmpBasedMetricsService implements PrometheusMetricsService {

    private final CachedMetricsService cachedMetrics;

    public SnmpBasedMetricsService(CachedMetricsService cachedMetrics) {
        this.cachedMetrics = cachedMetrics;
    }

    @Override
    public Optional<String> getMetrics(String instance, boolean regex) {
        Instant start = Instant.now();

        Optional<CachedMetrics> cached = cachedMetrics.getRawMetrics(instance);

        Instant end = Instant.now();
        double durationSeconds = Duration.between(start, end).toNanos() / 1_000_000_000.0;

        return cached.map(cm -> filterByInstance(cm.metricsPayload(), instance, regex)
                + scrapeSuccessMetric(cm.lastRefreshSucceeded())
                + "# HELP snmp_scrape_duration_seconds Total SNMP time scrape took (cached read).\n"
                + "# TYPE snmp_scrape_duration_seconds gauge\n"
                + "snmp_scrape_duration_seconds{source=\"cached\"} " + durationSeconds + "\n");
    }

    @Override
    public Optional<String> forceRefreshMetrics(String instance, boolean regex) {
        Instant start = Instant.now();

        Optional<CachedMetrics> refreshed = cachedMetrics.refreshMetrics(instance);

        Instant end = Instant.now();
        double durationSeconds = Duration.between(start, end).toNanos() / 1_000_000_000.0;

        return refreshed.map(cm -> filterByInstance(cm.metricsPayload(), instance, regex)
                + scrapeSuccessMetric(cm.lastRefreshSucceeded())
                + "# HELP snmp_scrape_duration_seconds Total SNMP time scrape took (forced, real-time read).\n"
                + "# TYPE snmp_scrape_duration_seconds gauge\n"
                + "snmp_scrape_duration_seconds{source=\"uncached\"} " + durationSeconds + "\n");
    }

    private String scrapeSuccessMetric(boolean succeeded) {
        int value = succeeded ? 1 : 0;
        return "# HELP snmp_scrape_success Whether the last SNMP scrape was successful (1=fresh, 0=stale).\n"
                + "# TYPE snmp_scrape_success gauge\n"
                + "snmp_scrape_success " + value + "\n";
    }

    private String formatMetrics(String rawMetrics) {
        return Stream.of(rawMetrics.split("\n"))
                .map(line -> line.matches("# (HELP|TYPE).*") ? "\n" + line : line)
                .collect(Collectors.joining("\n"));
    }

    private String filterByInstance(String metrics, String instance, boolean regex) {
        if (instance == null || instance.isBlank()) {
            return metrics;
        }

        StringBuilder filtered = new StringBuilder();
        String[] lines = metrics.split("\n");

        Pattern pattern = null;
        if (regex) {
            try {
                pattern = Pattern.compile(instance);
            } catch (PatternSyntaxException e) {
                return "Invalid regex pattern: " + instance;
            }
        }

        for (String line : lines) {
            if (line.isBlank() || line.startsWith("# HELP") || line.startsWith("# TYPE")) {
                filtered.append(line).append("\n");
            } else {
                boolean match = regex
                        ? pattern.matcher(line).find()
                        : line.contains("instance=\"" + instance + "\"");

                if (match) {
                    filtered.append(line).append("\n");
                }
            }
        }

        return filtered.toString();
    }
}
