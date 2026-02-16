package org.promsnmp.promsnmp.repositories.prometheus;

import lombok.extern.slf4j.Slf4j;
import org.promsnmp.promsnmp.mappers.AgentToTargetMapper;
import org.promsnmp.promsnmp.model.Agent;
import org.promsnmp.promsnmp.model.InterfaceInfo;
import org.promsnmp.promsnmp.model.MetricInfo;
import org.promsnmp.promsnmp.model.UserAgent;
import org.promsnmp.promsnmp.repositories.PrometheusMetricsRepository;
import org.promsnmp.promsnmp.repositories.jpa.NetworkDeviceRepository;
import org.promsnmp.promsnmp.services.prometheus.PrometheusHistogramService;
import org.promsnmp.promsnmp.snmp.AuthProtocolMapper;
import org.promsnmp.promsnmp.snmp.PrivProtocolMapper;
import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TreeEvent;
import org.snmp4j.util.TreeUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static java.util.Map.entry;

@Slf4j
@Repository("SnmpMetricsRepo")
public class SnmpMetricsRepository implements PrometheusMetricsRepository {

    public static final String              SYS_UPTIME = "1.3.6.1.2.1.1.3.0";
    public static final String                 IF_NAME = "1.3.6.1.2.1.31.1.1.1.1";
    public static final String         IF_HC_IN_OCTETS = "1.3.6.1.2.1.31.1.1.1.6";
    public static final String      IF_HC_IN_UCAST_PKTS = "1.3.6.1.2.1.31.1.1.1.7";
    public static final String  IF_HC_IN_MULTICAST_PKTS = "1.3.6.1.2.1.31.1.1.1.8";
    public static final String  IF_HC_IN_BROADCAST_PKTS = "1.3.6.1.2.1.31.1.1.1.9";
    public static final String         IF_HC_OUT_OCTETS = "1.3.6.1.2.1.31.1.1.1.10";
    public static final String     IF_HC_OUT_UCAST_PKTS = "1.3.6.1.2.1.31.1.1.1.11";
    public static final String IF_HC_OUT_MULTICAST_PKTS = "1.3.6.1.2.1.31.1.1.1.12";
    public static final String IF_HC_OUT_BROADCAST_PKTS = "1.3.6.1.2.1.31.1.1.1.13";
    public static final String            IF_HIGH_SPEED = "1.3.6.1.2.1.31.1.1.1.15";
    public static final String                 IF_SPEED = "1.3.6.1.2.1.2.2.1.5";
    public static final String                 IF_DESCR = "1.3.6.1.2.1.2.2.1.2";
    public static final String                 IF_ALIAS = "1.3.6.1.2.1.31.1.1.1.18";

    private static final LinkedHashMap<String, MetricInfo> METRICS;

    static {
        METRICS = new LinkedHashMap<>();
        METRICS.put("sysUpTime", new MetricInfo("sysUpTime", SYS_UPTIME, "System uptime in hundredths of a second", "gauge", true));
        METRICS.put("ifHCInOctets", new MetricInfo("ifHCInOctets", IF_HC_IN_OCTETS, "The total number of octets received on the interface, including framing characters", "counter", true));
        METRICS.put("ifHCInUcastPkts", new MetricInfo("ifHCInUcastPkts", IF_HC_IN_UCAST_PKTS, "Packets received not addressed to multicast/broadcast", "counter", true));
        METRICS.put("ifHCInMulticastPkts", new MetricInfo("ifHCInMulticastPkts", IF_HC_IN_MULTICAST_PKTS, "Multicast packets received", "counter", true));
        METRICS.put("ifHCInBroadcastPkts", new MetricInfo("ifHCInBroadcastPkts", IF_HC_IN_BROADCAST_PKTS, "Broadcast packets received", "counter", true));
        METRICS.put("ifHCOutOctets", new MetricInfo("ifHCOutOctets", IF_HC_OUT_OCTETS, "The total number of octets transmitted including framing characters", "counter", true));
        METRICS.put("ifHCOutUcastPkts", new MetricInfo("ifHCOutUcastPkts", IF_HC_OUT_UCAST_PKTS, "Packets sent not addressed to multicast/broadcast", "counter", true));
        METRICS.put("ifHCOutMulticastPkts", new MetricInfo("ifHCOutMulticastPkts", IF_HC_OUT_MULTICAST_PKTS, "Multicast packets sent", "counter", true));
        METRICS.put("ifHCOutBroadcastPkts", new MetricInfo("ifHCOutBroadcastPkts", IF_HC_OUT_BROADCAST_PKTS, "Broadcast packets sent", "counter", true));
        METRICS.put("ifHighSpeed", new MetricInfo("ifHighSpeed", IF_HIGH_SPEED, "Interface speed (Mbps)", "gauge", true));
        METRICS.put("ifSpeed", new MetricInfo("ifSpeed", IF_SPEED, "Interface speed (bps)", "gauge", true));
        METRICS.put("interface_utilization", new MetricInfo("interface_utilization", "custom.histogram", "Interface bandwidth utilization as histogram", "histogram", false));
    }

    private final NetworkDeviceRepository deviceRepository;
    private final AgentToTargetMapper agentToTargetMapper;
    private final PrometheusHistogramService histogramService;

    public SnmpMetricsRepository(NetworkDeviceRepository deviceRepository, AgentToTargetMapper agentToTargetMapper, PrometheusHistogramService histogramService) {
        this.deviceRepository = deviceRepository;
        this.agentToTargetMapper = agentToTargetMapper;
        this.histogramService = histogramService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> readMetrics(String instance) {
        return deviceRepository.findBySysNameWithAgents(instance)
                .flatMap(device -> {
                    Agent agent = device.resolvePrimaryAgent();
                    if (agent == null) {
                        // Consider logging the situation for observability
                        return Optional.empty();
                    }

                    try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
                        snmp.listen();

                        Target<UdpAddress> target = agentToTargetMapper.mapToTarget(agent);

                        // For SNMPv3, ensure USM user is registered
                        if (target instanceof UserTarget<?> && agent instanceof UserAgent userAgent) {
                            UsmUser user = new UsmUser(
                                    new OctetString(userAgent.getSecurityName()),
                                    AuthProtocolMapper.map(userAgent.getAuthProtocol()),
                                    userAgent.getAuthPassphrase() != null ? new OctetString(userAgent.getAuthPassphrase()) : null,
                                    PrivProtocolMapper.map(userAgent.getPrivProtocol()),
                                    userAgent.getPrivPassphrase() != null ? new OctetString(userAgent.getPrivPassphrase()) : null
                            );

                            snmp.getUSM().addUser(user);
                        }

                        return Optional.of(walkMetrics(snmp, target, instance));
                    } catch (IOException e) {
                        log.error("Error walking SNMP metrics for instance: {}", instance, e);
                        return Optional.empty();
                    }
                });
    }

    private String walkMetrics(Snmp snmp, Target<UdpAddress> target, String instance) throws IOException {
        Instant start = Instant.now();
        StringBuilder sb = new StringBuilder();

        PDU sysPdu = new PDU();
        sysPdu.add(new VariableBinding(new OID(SYS_UPTIME)));
        sysPdu.setType(PDU.GET);
        ResponseEvent<UdpAddress> sysResp = snmp.get(sysPdu, target);

        if (sysResp.getResponse() != null && !sysResp.getResponse().getVariableBindings().isEmpty()) {
            Variable uptimeVar = sysResp.getResponse().get(0).getVariable();
            if (uptimeVar instanceof TimeTicks ticks) {
                long hundredths = ticks.toMilliseconds() / 10; // Convert from ms to hundredths of a second
                String humanReadable = getHumanReadable(hundredths);

                sb.append("# HELP sysUpTime system uptime in hundredths of a second - 1.3.6.1.2.1.1.3.0\n");
                sb.append("# TYPE sysUpTime gauge\n");
                sb.append(String.format("sysUpTime{instance=\"%s\"} %d\n", instance, hundredths));
            }
        }

        // --- Walk interface identity columns ---
        Map<Integer, String> ifDescr = walkStringColumn(snmp, target, IF_DESCR);
        Map<Integer, String> ifName  = walkStringColumn(snmp, target, IF_NAME);
        Map<Integer, String> ifAlias = walkStringColumn(snmp, target, IF_ALIAS);

        // --- Build InterfaceInfo map ---
        Map<Integer, InterfaceInfo> interfaces = new HashMap<>();
        for (Integer idx : ifDescr.keySet()) {
            interfaces.put(idx, new InterfaceInfo(
                    idx,
                    ifDescr.getOrDefault(idx, ""),
                    ifName.getOrDefault(idx, ""),
                    ifAlias.getOrDefault(idx, "")
            ));
        }

        // --- Collect all walkable metrics ---
        Map<String, Map<Integer, Long>> collectedData = new HashMap<>();
        for (var entry : METRICS.entrySet()) {
            MetricInfo metric = entry.getValue();
            if (!metric.walkable()) continue;
            collectedData.put(metric.name(), walkLongColumn(snmp, target, metric.oid()));
        }

        // --- Emit HELP/TYPE and metric values ---
        for (var entry : METRICS.entrySet()) {
            MetricInfo metric = entry.getValue();
            if (!metric.walkable()) continue;

            Map<Integer, Long> values = collectedData.getOrDefault(metric.name(), Map.of());
            if (values.isEmpty()) continue;

            sb.append(String.format("# HELP %s %s - %s\n", metric.name(), metric.help(), metric.oid()));
            sb.append(String.format("# TYPE %s %s\n", metric.name(), metric.type()));

            for (var iface : interfaces.values()) {
                long value = values.getOrDefault(iface.ifIndex(), 0L);
                sb.append(String.format(
                        "%s{instance=\"%s\",ifDescr=\"%s\",ifName=\"%s\",ifIndex=\"%d\",ifAlias=\"%s\"} %d\n",
                        metric.name(),
                        instance,
                        iface.ifDescr(),
                        iface.ifName(),
                        iface.ifIndex(),
                        iface.ifAlias(),
                        value
                ));
            }
        }

        // --- Emit histogram metrics ---
        sb.append("# HELP interface_utilization Interface bandwidth utilization as histogram - custom.histogram\n");
        sb.append("# TYPE interface_utilization histogram\n");

        for (InterfaceInfo iface : interfaces.values()) {
            long inOctets  = collectedData.getOrDefault("ifHCInOctets", Map.of()).getOrDefault(iface.ifIndex(), 0L);
            long outOctets = collectedData.getOrDefault("ifHCOutOctets", Map.of()).getOrDefault(iface.ifIndex(), 0L);
            long total = inOctets + outOctets;

            long speedBps = collectedData.getOrDefault("ifHighSpeed", Map.of()).getOrDefault(iface.ifIndex(), 0L) * 1_000_000L;
            if (speedBps == 0) {
                speedBps = collectedData.getOrDefault("ifSpeed", Map.of()).getOrDefault(iface.ifIndex(), 0L);
            }

            histogramService.renderUtilizationHistogram(instance, iface, total, speedBps)
                    .ifPresent(sb::append);
        }

        return sb.toString();
    }

    private static String getHumanReadable(long hundredths) {
        long totalSeconds = hundredths / 100;

        Duration duration = Duration.ofSeconds(totalSeconds);
        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
        long seconds = duration.minusDays(days).minusHours(hours).minusMinutes(minutes).getSeconds();

        String humanReadable = String.format("%d days, %02d:%02d:%02d", days, hours, minutes, seconds);
        return humanReadable;
    }

    private Map<Integer, String> walkStringColumn(Snmp snmp, Target<UdpAddress> target, String baseOid) throws IOException {
        Map<Integer, String> results = new HashMap<>();
        TreeUtils tree = new TreeUtils(snmp, new DefaultPDUFactory());
        List<TreeEvent> events = tree.getSubtree(target, new OID(baseOid));
        for (TreeEvent e : events) {
            if (e.getVariableBindings() != null) {
                for (VariableBinding vb : e.getVariableBindings()) {
                    results.put(vb.getOid().last(), vb.getVariable().toString());
                }
            }
        }
        return results;
    }

    private Map<Integer, Long> walkLongColumn(Snmp snmp, Target<UdpAddress> target, String baseOid) throws IOException {
        Map<Integer, Long> results = new HashMap<>();
        TreeUtils tree = new TreeUtils(snmp, new DefaultPDUFactory());
        List<TreeEvent> events = tree.getSubtree(target, new OID(baseOid));
        for (TreeEvent e : events) {
            if (e.getVariableBindings() != null) {
                for (VariableBinding vb : e.getVariableBindings()) {
                    try {
                        results.put(vb.getOid().last(), vb.getVariable().toLong());
                    } catch (NumberFormatException ignored) {} //fixme: need to do something much better here
                }
            }
        }
        return results;
    }

    private String render(String metric, InterfaceInfo iface, String instance, long value) {
        return String.format(
                "%s{instance=\"%s\",ifDescr=\"%s\",ifName=\"%s\",ifIndex=\"%d\",ifAlias=\"%s\"} %d\n",
                metric,
                instance,
                iface.ifDescr(),
                iface.ifName(),
                iface.ifIndex(),
                iface.ifAlias(),
                value
        );
    }

    private void emitMetricHeader(StringBuilder sb, String metric, Map<?, ?> values) {
        if (!values.isEmpty()) {
            MetricInfo info = METRICS.get(metric);
            if (info != null) {
                sb.append(String.format("# HELP %s %s - %s\n", info.name(), info.help(), info.oid()));
                sb.append(String.format("# TYPE %s %s\n", info.name(), info.type()));
            }
        }
    }

    private String getOidForMetric(String metric) {
        return METRICS.getOrDefault(metric, new MetricInfo(metric, "unknown", "", "", false)).oid();
    }

}
