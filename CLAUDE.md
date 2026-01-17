# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build (compile and assemble JAR, skips tests)
make

# Build with tests
mvn install

# Run a single test class
mvn test -Dtest=MyTestClass

# Run a single test method
mvn test -Dtest=MyTestClass#testMethodName

# Clean build artifacts
make clean

# Build Docker container image
make oci

# Run locally
java -jar target/metrics-*.jar
```

## Project Overview

PromSnmp Metrics is a Spring Boot 3.5 application that bridges SNMP network device monitoring with Prometheus metrics collection. It discovers SNMP agents on network devices, collects metrics via SNMP OID walks, and exposes them through Prometheus-compatible HTTP endpoints (`/snmp`, `/metrics`, `/targets`).

## Architecture

### Layered Structure (src/main/java/org/promsnmp/metrics/)

**Controllers** → REST endpoints for metrics scraping and discovery management
- `MetricsController`: Primary Prometheus scrape endpoint (`/snmp?target=...`)
- `DiscoveryController`: REST API for SNMP discovery operations

**Services** → Business logic with strategy-pattern implementations
- `PrometheusMetricsService` interface: Multiple implementations (SNMP-based, resource-based, direct)
- `PrometheusDiscoveryService` interface: Target discovery (JPA-based or static resources)
- `CachedMetricsService`: Caffeine cache wrapper (10-min TTL)

**Repositories** → Data access abstraction
- `PrometheusMetricsRepository` interface: SNMP OID walks vs static resources
- `SnmpMetricsRepository`: Live SNMP queries for interface counters, computes utilization histograms
- JPA repositories in `/jpa`: NetworkDevice, Agent (SNMPv1/v2c/v3 credentials), DiscoverySeed

**SNMP Integration** (`/snmp` and `/utils`)
- Uses SNMP4J 3.7.1 library
- `SnmpAgentConfig`: Connection configuration (address, port, timeout)
- Protocol mappers for SNMPv3 auth (MD5, SHA) and privacy (DES, 3DES, AES)

### Configuration Strategy

`ServiceApiConfig` uses environment variables to switch implementations:
- `PROM_METRICS_API`: Metrics service backend (snmp/demo/direct)
- `PROM_DISCOVERY_API`: Discovery service backend
- `METRICS_REPO_API`: Metrics repository backend

### Key Dependencies

- `promsnmp-common` (0.0.1): Shared models (Agent, UserAgent, CommunityAgent)
- Prometheus metrics libraries (1.4.0-SNAPSHOT): Native histogram support
- H2: In-memory database for device/agent persistence
- Spring Shell 3.4: CLI commands via `DiscoveryCommand`

### Data Flow: Metrics Scrape

1. Prometheus calls `/snmp?target=device.example.com`
2. `MetricsController` → `SnmpBasedMetricsService` → `CachedMetricsService`
3. Cache miss triggers `SnmpMetricsRepository` SNMP OID walk
4. Collects: sysUpTime, ifName, ifDescr, ifHCInOctets, ifHCOutOctets, packet counts
5. Computes interface utilization histograms
6. Returns OpenMetrics text format (RFC 9154)

## Tech Stack

- Java 21, Spring Boot 3.5, Spring Data JPA
- SNMP4J 3.7.1 for SNMP operations
- Caffeine for caching, Micrometer/Prometheus for metrics
- H2 database, Lombok, SpringDoc OpenAPI
