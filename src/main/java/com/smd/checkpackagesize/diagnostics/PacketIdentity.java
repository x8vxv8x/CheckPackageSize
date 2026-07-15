package com.smd.checkpackagesize.diagnostics;

public record PacketIdentity(String direction, String modId, String channel, String packetClass, String handlerClass) {
    public PacketIdentity {
        direction = value(direction, "UNKNOWN");
        modId = value(modId, "unknown");
        channel = value(channel, "-");
        packetClass = value(packetClass, "unknown");
        handlerClass = value(handlerClass, "-");
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
