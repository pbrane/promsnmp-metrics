package org.promsnmp.promsnmp.inventory;

import org.promsnmp.promsnmp.model.Agent;
import java.util.List;

/**
 * Interface for notifying external systems about discovered agents.
 *
 * <p>Implementations handle notification/publishing to external destinations
 * such as logs, message queues, or webhooks.</p>
 *
 * <p><strong>NOTE:</strong> This interface is NOT responsible for persistence.
 * Agent and device persistence is handled by JPA repositories during the
 * discovery flow in {@link org.promsnmp.promsnmp.inventory.discovery.SnmpAgentDiscovery}.</p>
 *
 * @see LoggingInventoryPublisher
 */
public interface InventoryPublisher {

    /**
     * Notifies about newly discovered agents.
     *
     * @param agents the discovered agents to publish/notify about
     */
    void publish(List<? extends Agent> agents);
}