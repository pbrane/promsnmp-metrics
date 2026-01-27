package org.promsnmp.promsnmp.services;

import lombok.extern.slf4j.Slf4j;
import org.promsnmp.promsnmp.dto.DiscoveryRequestDTO;
import org.promsnmp.promsnmp.inventory.InventoryBackupManager;
import org.promsnmp.promsnmp.inventory.InventoryPublisher;
import org.promsnmp.promsnmp.inventory.discovery.SnmpAgentDiscovery;
import org.promsnmp.promsnmp.model.NetworkDevice;
import org.promsnmp.promsnmp.repositories.jpa.NetworkDeviceRepository;
import org.promsnmp.promsnmp.utils.IpUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

import static org.promsnmp.promsnmp.utils.Snmp4jUtils.resolveSnmpVersion;

@Slf4j
@Service
public class DiscoveryManagementService {

    private final SnmpAgentDiscovery discoveryService;
    private final DiscoverySeedService seedService;
    private final InventoryPublisher inventoryPublisher;
    private final InventoryBackupManager inventoryBackupManager;
    private final NetworkDeviceRepository deviceRepository;

    public DiscoveryManagementService(SnmpAgentDiscovery discoveryService,
                                      DiscoverySeedService seedService,
                                      InventoryPublisher inventoryPublisher,
                                      InventoryBackupManager inventoryBackupManager,
                                      NetworkDeviceRepository deviceRepository) {
        this.discoveryService = discoveryService;
        this.seedService = seedService;
        this.inventoryPublisher = inventoryPublisher;
        this.inventoryBackupManager = inventoryBackupManager;
        this.deviceRepository = deviceRepository;
    }

    public void handleDiscoveryRequest(DiscoveryRequestDTO request, boolean scheduleNow, boolean saveSeed) {
        int snmpVersion = resolveSnmpVersion(request.getVersion());
        UUID contextId = UUID.randomUUID();
        List<InetAddress> targets = IpUtils.toInetAddressList(request.getPotentialTargets(), contextId);

        if (targets.isEmpty()) throw new IllegalArgumentException("No valid targets provided.");

        boolean shouldPersist = !scheduleNow || saveSeed;

        if ("snmp-community".equals(request.getAgentType())) {
            if (request.getReadCommunity() == null)
                throw new IllegalArgumentException("readCommunity is required for snmp-community");

            if (scheduleNow) {
                var future = discoveryService.discoverMultiple(
                        targets, request.getPort(), request.getReadCommunity());

                future.thenAccept(agents -> {
                    if (shouldPersist) {
                        seedService.saveDiscoverySeed(request);
                    }
                    agents.forEach(a -> {
                        a.setVersion(snmpVersion);
                        NetworkDevice device = a.getDevice();
                        if (device != null) {
                            device.setPrimaryAgent(a);
                            deviceRepository.save(device);  // Persist primaryAgent and version changes
                        }
                    });
                    inventoryPublisher.publish(agents);
                });
            } else {
                seedService.saveDiscoverySeed(request);
            }

        } else if ("snmp-user".equals(request.getAgentType())) {
            if (request.getSecurityName() == null || request.getAuthProtocol() == null || request.getAuthPassphrase() == null)
                throw new IllegalArgumentException("Incomplete SNMPv3 configuration");

            if (scheduleNow) {
                var future = discoveryService.discoverMultipleV3(
                        targets, request.getPort(),
                        request.getSecurityName(),
                        request.getAuthProtocol(), request.getAuthPassphrase(),
                        request.getPrivProtocol(), request.getPrivPassphrase());

                future.thenAccept(agents -> {
                    if (shouldPersist) {
                        seedService.saveDiscoverySeed(request);
                    }
                    agents.forEach(a -> {
                        a.setVersion(snmpVersion);
                        NetworkDevice device = a.getDevice();
                        if (device != null) {
                            device.setPrimaryAgent(a);
                            deviceRepository.save(device);  // Persist primaryAgent and version changes
                        }
                    });
                    inventoryPublisher.publish(agents);
                });
            } else {
                seedService.saveDiscoverySeed(request);
            }

        } else {
            throw new IllegalArgumentException("Unsupported agent type: " + request.getAgentType());
        }

        inventoryBackupManager.backup();
    }

}
