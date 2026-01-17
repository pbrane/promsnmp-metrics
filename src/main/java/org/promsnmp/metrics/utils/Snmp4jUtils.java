package org.promsnmp.metrics.snmp;

public class Snmp4jUtils {

    /**
     * Converts a user-friendly version string to SNMP4J version constant.
     * @param version "v2c" or "v3"
     * @return SNMP4J version number (1 or 3)
     */
    public static int resolveSnmpVersion(String version) {
        return switch (version.toLowerCase()) {
            case "v2c" -> 1; // SnmpConstants.version2c
            case "v3"  -> 3; // SnmpConstants.version3
            default -> throw new IllegalArgumentException("Unsupported SNMP version: " + version);
        };
    }
}
