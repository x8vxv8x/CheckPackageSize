package com.smd.checkpackagesize.diagnostics;

import java.util.Objects;

public final class PacketIdentity {

    public final String direction;
    public final String modId;
    public final String channel;
    public final String packetClass;
    public final String handlerClass;

    public PacketIdentity(String direction, String modId, String channel, String packetClass, String handlerClass) {
        this.direction = value(direction, "UNKNOWN");
        this.modId = value(modId, "unknown");
        this.channel = value(channel, "-");
        this.packetClass = value(packetClass, "unknown");
        this.handlerClass = value(handlerClass, "-");
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PacketIdentity)) {
            return false;
        }
        PacketIdentity other = (PacketIdentity) object;
        return direction.equals(other.direction)
                && modId.equals(other.modId)
                && channel.equals(other.channel)
                && packetClass.equals(other.packetClass)
                && handlerClass.equals(other.handlerClass);
    }

    @Override
    public int hashCode() {
        return Objects.hash(direction, modId, channel, packetClass, handlerClass);
    }
}
