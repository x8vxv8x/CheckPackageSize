package com.smd.checkpackagesize.diagnostics;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class PacketResolver {

    private static final ClassValue<PacketIdentity[]> VANILLA_IDENTITIES = new ClassValue<>() {
        @Override
        protected PacketIdentity[] computeValue(Class<?> type) {
            String modId = ModResolver.resolve(type);
            String className = type.getName();
            return new PacketIdentity[] {
                    new PacketIdentity("C2S", modId, "-", className, "-"),
                    new PacketIdentity("S2C", modId, "-", className, "-")
            };
        }
    };
    private static final ConcurrentMap<String, AtomicReferenceArray<PacketIdentity>> UNKNOWN_FORGE = new ConcurrentHashMap<>();

    private PacketResolver() {
    }

    public static PacketIdentity resolve(Packet<?> packet, EnumPacketDirection direction) {
        String directionName = direction == EnumPacketDirection.SERVERBOUND ? "C2S" : "S2C";
        if (packet instanceof FMLProxyPacket) {
            FMLProxyPacket proxy = (FMLProxyPacket) packet;
            String channel = proxy.channel();
            int discriminator = readDiscriminator(proxy.payload());
            MessageRegistry.Entry entry = MessageRegistry.find(channel, discriminator);
            if (entry != null) {
                return entry.identity(directionName);
            }
            String channelName = channel == null ? "" : channel;
            AtomicReferenceArray<PacketIdentity> identities = UNKNOWN_FORGE.computeIfAbsent(channelName,
                    ignored -> new AtomicReferenceArray<>(512));
            int slot = (discriminator & 0xFF) * 2 + (direction == EnumPacketDirection.SERVERBOUND ? 0 : 1);
            PacketIdentity identity = identities.get(slot);
            if (identity != null) return identity;
            String modId = channelName.isEmpty() ? "forge" : channelName;
            PacketIdentity created = new PacketIdentity(directionName, modId, channelName, packet.getClass().getName(), "-");
            if (identities.compareAndSet(slot, null, created)) return created;
            return identities.get(slot);
        }
        return VANILLA_IDENTITIES.get(packet.getClass())[direction == EnumPacketDirection.SERVERBOUND ? 0 : 1];
    }

    private static int readDiscriminator(ByteBuf payload) {
        if (payload == null || payload.readableBytes() < 1) {
            return -1;
        }
        return payload.getUnsignedByte(payload.readerIndex());
    }

    public static String endpointForInbound(EnumPacketDirection direction) {
        return direction == EnumPacketDirection.CLIENTBOUND ? "CLIENT" : "SERVER";
    }

    public static String endpointForOutbound(EnumPacketDirection direction) {
        return direction == EnumPacketDirection.SERVERBOUND ? "CLIENT" : "SERVER";
    }

    public static int framedSize(int payloadBytes) {
        return payloadBytes + varIntSize(payloadBytes);
    }

    private static int varIntSize(int value) {
        if ((value & 0xFFFFFF80) == 0) return 1;
        if ((value & 0xFFFFC000) == 0) return 2;
        if ((value & 0xFFE00000) == 0) return 3;
        if ((value & 0xF0000000) == 0) return 4;
        return 5;
    }
}
