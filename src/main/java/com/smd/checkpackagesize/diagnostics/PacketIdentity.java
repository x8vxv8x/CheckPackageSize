package com.smd.checkpackagesize.diagnostics;

public record PacketIdentity(String direction, String modId, String channel, String packetClass, String handlerClass,
                             int discriminator) {
    public PacketIdentity(String direction, String modId, String channel, String packetClass, String handlerClass) {
        this(direction, modId, channel, packetClass, handlerClass, -1);
    }

    public PacketIdentity {
        direction = value(direction, "UNKNOWN");
        modId = value(modId, "unknown");
        channel = value(channel, "-");
        packetClass = value(packetClass, "unknown");
        handlerClass = value(handlerClass, "-");
        discriminator = discriminator < 0 ? -1 : discriminator & 0xFF;
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
