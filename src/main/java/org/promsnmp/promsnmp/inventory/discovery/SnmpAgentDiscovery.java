package org.promsnmp.promsnmp.inventory.discovery;

import org.promsnmp.promsnmp.model.AgentEndpoint;
import org.promsnmp.promsnmp.model.CommunityAgent;
import org.promsnmp.promsnmp.model.NetworkDevice;
import org.promsnmp.promsnmp.model.UserAgent;
import org.promsnmp.promsnmp.repositories.jpa.CommunityAgentRepository;
import org.promsnmp.promsnmp.repositories.jpa.NetworkDeviceRepository;
import org.promsnmp.promsnmp.repositories.jpa.UserAgentRepository;
import org.promsnmp.promsnmp.snmp.AuthProtocolType;
import org.promsnmp.promsnmp.snmp.PrivProtocolType;
import org.promsnmp.promsnmp.snmp.ProtocolValidator;
import org.promsnmp.promsnmp.utils.Snmpv3Utils;
import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class SnmpAgentDiscovery {

    private static final OID SYS_OBJECT_ID_OID = new OID("1.3.6.1.2.1.1.2.0");
    private static final OID SYS_NAME_OID = new OID("1.3.6.1.2.1.1.5.0");
    private static final OID SYS_DESCR_OID = new OID("1.3.6.1.2.1.1.1.0");
    private static final OID SYS_CONTACT_OID = new OID("1.3.6.1.2.1.1.4.0");
    private static final OID SYS_LOCATION_OID = new OID("1.3.6.1.2.1.1.6.0");

    private final CommunityAgentRepository communityRepo;
    private final UserAgentRepository userRepo;
    private final NetworkDeviceRepository deviceRepo;

    public SnmpAgentDiscovery(CommunityAgentRepository communityRepo, UserAgentRepository userRepo,
                              NetworkDeviceRepository deviceRepo) {
        this.communityRepo = communityRepo;
        this.userRepo = userRepo;
        this.deviceRepo = deviceRepo;
    }

    @Async("snmpDiscoveryExecutor")
    public CompletableFuture<Optional<CommunityAgent>> discoverCommunityAgent(
            InetAddress address, int port, String community) {

        try {
            CommunityTarget<UdpAddress> target = new CommunityTarget<>();
            target.setCommunity(new OctetString(community));
            target.setAddress(new UdpAddress(address, port));
            target.setRetries(1);
            target.setTimeout(1000);
            target.setVersion(SnmpConstants.version2c);

            try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
                snmp.listen();

                PDU pdu = new PDU();
                pdu.add(new VariableBinding(SYS_OBJECT_ID_OID));
                pdu.setType(PDU.GET);

                ResponseEvent<UdpAddress> event = snmp.get(pdu, target);
                if (event.getResponse() != null && !event.getResponse().getVariableBindings().isEmpty()) {
                    VariableBinding vb = event.getResponse().get(0);
                    if (!vb.getVariable().isException()) {
                        AgentEndpoint endpoint = new AgentEndpoint(address, port);
                        Optional<CommunityAgent> communityAgent = communityRepo.findByEndpoint(endpoint);
                        if (communityAgent.isEmpty()) {
                            CommunityAgent agent = new CommunityAgent();
                            agent.setEndpoint(endpoint);
                            agent.setRetries(1);
                            agent.setTimeout(1000);
                            agent.setVersion(SnmpConstants.version2c);
                            agent.setReadCommunity(community);
                            agent.setDiscoveredAt(Instant.now());

                            Optional<NetworkDevice> deviceOpt = createDeviceFromMib2(snmp, target);
                            if (deviceOpt.isPresent()) {
                                NetworkDevice device = deviceOpt.get();
                                device.addAgent(agent);  // Establishes bidirectional relationship
                                deviceRepo.save(device); // Cascades to save agent
                            } else {
                                communityRepo.save(agent); // Save agent without device
                            }

                            return CompletableFuture.completedFuture(Optional.of(agent));
                        }
                    }
                }
            }
        } catch (Exception e) { //fixme
            // log as needed
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Async("snmpDiscoveryExecutor")
    public CompletableFuture<Optional<UserAgent>> discoverUserAgent(
            InetAddress address,
            int port,
            String username,
            String authProtocol,
            String authPass,
            String privProtocol,
            String privPass) {

        AuthProtocolType authEnum = ProtocolValidator.validateAuthProtocol(authProtocol);
        PrivProtocolType privEnum = ProtocolValidator.validatePrivProtocol(privProtocol);

        try {
            OctetString user = new OctetString(username);
            UserTarget<UdpAddress> target = new UserTarget<>();
            target.setAddress(new UdpAddress(address, port));
            target.setRetries(1);
            target.setTimeout(1000);
            target.setVersion(SnmpConstants.version3);
            target.setSecurityName(user);
            target.setSecurityLevel(SecurityLevel.AUTH_PRIV);

            TransportMapping<UdpAddress> transport = new DefaultUdpTransportMapping();
            Snmp snmp = new Snmp(transport);
            snmp.listen();

            // Use helper to register SNMPv3 user and discover engine ID
            UserAgent agent = new UserAgent();
            agent.setEndpoint(new AgentEndpoint(address, port));
            agent.setRetries(1);
            agent.setTimeout(1000);
            agent.setVersion(SnmpConstants.version3);
            agent.setSecurityName(username);
            agent.setSecurityLevel(SecurityLevel.AUTH_PRIV);
            agent.setAuthProtocol(authEnum.name()); // or configurable
            agent.setAuthPassphrase(authPass);
            agent.setPrivProtocol(privEnum.name()); // or configurable
            agent.setPrivPassphrase(privPass);
            agent.setDiscoveredAt(Instant.now());

            // Discover and register the engineId
            Snmpv3Utils.registerUser(snmp, agent);

            ScopedPDU pdu = new ScopedPDU();
            pdu.setType(PDU.GET);
            pdu.add(new VariableBinding(SYS_OBJECT_ID_OID));

            ResponseEvent<UdpAddress> event = snmp.get(pdu, target);
            if (event.getResponse() != null && !event.getResponse().getVariableBindings().isEmpty()) {
                VariableBinding vb = event.getResponse().get(0);
                if (!vb.getVariable().isException()) {
                    if (userRepo.findByEndpoint(agent.getEndpoint()).isEmpty()) {
                        Optional<NetworkDevice> deviceOpt = createDeviceFromMib2(snmp, target);
                        if (deviceOpt.isPresent()) {
                            NetworkDevice device = deviceOpt.get();
                            device.addAgent(agent);  // Establishes bidirectional relationship
                            deviceRepo.save(device); // Cascades to save agent
                        } else {
                            userRepo.save(agent); // Save agent without device
                        }
                        return CompletableFuture.completedFuture(Optional.of(agent));
                    }
                }
            }

        } catch (Exception e) {
            // log error appropriately
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    public CompletableFuture<List<CommunityAgent>> discoverMultiple(List<InetAddress> addresses, int port, String community) {
        List<CompletableFuture<Optional<CommunityAgent>>> futures = addresses.stream()
                .map(addr -> discoverCommunityAgent(addr, port, community))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(Optional::stream)
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<List<UserAgent>> discoverMultipleV3(List<InetAddress> addresses,
                                                                 int port,
                                                                 String username,
                                                                 String authProtocol,
                                                                 String authPass,
                                                                 String privProtocol,
                                                                 String privPass) {
        List<CompletableFuture<Optional<UserAgent>>> futures = addresses.stream()
                .map(addr -> discoverUserAgent(addr, port, username, authProtocol, authPass, privProtocol, privPass))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(Optional::stream)
                        .collect(Collectors.toList()));
    }

    private Optional<NetworkDevice> createDeviceFromMib2(Snmp snmp, Target<UdpAddress> target) throws Exception {
        PDU pdu = new PDU();
        pdu.setType(PDU.GET);
        pdu.add(new VariableBinding(SYS_NAME_OID));
        pdu.add(new VariableBinding(SYS_DESCR_OID));
        pdu.add(new VariableBinding(SYS_CONTACT_OID));
        pdu.add(new VariableBinding(SYS_LOCATION_OID));

        ResponseEvent<UdpAddress> event = snmp.get(pdu, target);
        if (event.getResponse() != null && !event.getResponse().getVariableBindings().isEmpty()) {
            String sysName = null;
            NetworkDevice device = new NetworkDevice();

            for (VariableBinding vb : event.getResponse().getVariableBindings()) {
                if (SYS_NAME_OID.equals(vb.getOid())) {
                    sysName = vb.getVariable().toString();
                    device.setSysName(sysName);
                }
                if (SYS_DESCR_OID.equals(vb.getOid())) device.setSysDescr(vb.getVariable().toString());
                if (SYS_CONTACT_OID.equals(vb.getOid())) device.setSysContact(vb.getVariable().toString());
                if (SYS_LOCATION_OID.equals(vb.getOid())) device.setSysLocation(vb.getVariable().toString());
            }

            if (sysName != null) {
                return deviceRepo.findBySysNameWithAgents(sysName).or(() -> {
                    device.setDiscoveredAt(Instant.now());
                    return Optional.of(deviceRepo.save(device));
                });
            }
        }
        return Optional.empty();
    }

}
