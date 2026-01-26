# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
make                    # Compile and assemble JAR (skips tests)
make tests              # Build with full test suite
make oci                # Build Docker container image
make clean              # Clean build artifacts

# Maven direct
mvn test -Dtest=MyTestClass                  # Run single test class
mvn test -Dtest=MyTestClass#testMethodName   # Run single test method
```

## Run Locally

```bash
java -jar target/promsnmp-*.jar
```

## Development Stack

```bash
cd deployment && docker compose up -d
```
- Grafana: http://localhost:3000 (admin/admin)
- Prometheus: http://localhost:9090
- PromSNMP: http://localhost:8080

## Release Process

```bash
make release RELEASE_VERSION=x.y.z   # Creates release in local repo
git push                              # Push snapshot version
git push origin vx.y.z               # Push tag to trigger artifact build
```

## Architecture

### Strategy Pattern for Service Backends

`ServiceApiConfig` switches implementations via environment variables:

| Variable | Options | Default | Purpose |
|----------|---------|---------|---------|
| `PROM_METRICS_API` | snmp/demo/direct | snmp | Metrics service backend |
| `PROM_DISCOVERY_API` | snmp/demo | snmp | Discovery service backend |
| `METRICS_REPO_API` | snmp/demo/direct | snmp | Repository backend |

### Layer Structure

**Controllers** (`controllers/`)
- `MetricsController`: Prometheus scrape endpoints (`/snmp?target=...`, `/targets`)
- `DiscoveryController`: REST API for SNMP discovery operations
- `InventoryController`: Import/export inventory
- `PromSnmpController`: Utility endpoints (`/promsnmp/hello`, `/promsnmp/evictCache`)

**Services** (`services/`)
- `SnmpBasedMetricsService`: Production implementation using SNMP
- `CachedMetricsService`: Caffeine cache wrapper around repository
- `JpaPrometheusDiscoveryService`: Target discovery from JPA-persisted devices

**Repositories** (`repositories/`)
- `SnmpMetricsRepository`: Live SNMP OID walks, computes interface utilization histograms
- JPA repositories (`jpa/`): NetworkDevice, Agent, DiscoverySeed persistence

**SNMP Integration** (`snmp/`)
- Uses SNMP4J library
- `AuthProtocolMapper`/`PrivProtocolMapper`: SNMPv3 protocol translation
- `SnmpAgentConfig`: Connection configuration

### Data Flow: Metrics Scrape

1. Prometheus calls `/snmp?target=device.example.com`
2. `MetricsController` → `SnmpBasedMetricsService` → `CachedMetricsService`
3. Cache miss: `SnmpMetricsRepository.readMetrics()` performs SNMP OID walks
4. Collects: sysUpTime, ifName, ifDescr, ifHCInOctets, ifHCOutOctets, packet counts
5. `PrometheusHistogramService` computes interface utilization histograms
6. Returns OpenMetrics text format

### Discovery Flow

1. `POST /promsnmp/discovery` with SNMP credentials and target IPs
2. `SnmpAgentDiscovery` probes targets via SNMP
3. Discovered devices persisted to JPA (H2 in-memory) and encrypted JSON file
4. `SnmpDiscoveryScheduler` runs periodic scans based on `DISCOVERY_CRON`

## Key Environment Variables

```bash
# Cache configuration
CACHE_EXP_MILLIS=300000          # Cache TTL
CACHE_ENTRY_CNT=10000            # Max cache entries
CACHE_STATS_RATE_MILLIS=15000    # Stats logging interval

# Discovery
DISCOVERY_CRON="0 0 2 * * *"     # Nightly at 2 AM
DISCOVERY_ON_START=false         # Scan on startup

# Inventory persistence
PROM_INV_FILE=/app/data/promsnmp-inventory.json
PROM_ENCRYPT_KEY=<16-char-key>   # Encryption key for inventory file

# Site metadata
PROM_TENANT_ID, PROM_SITE_ID, PROM_SITE_LABEL, PROM_SITE_DESCR
PROM_SITE_ADDR, PROM_SITE_LAT, PROM_SITE_LONG
```

## Tech Stack

- Java 21, Spring Boot 3.5, Spring Data JPA
- SNMP4J for SNMP operations
- Caffeine for caching, Micrometer/Prometheus for metrics
- H2 in-memory database, Lombok, SpringDoc OpenAPI
- Spring Shell 3.4 for CLI commands (disabled by default)
