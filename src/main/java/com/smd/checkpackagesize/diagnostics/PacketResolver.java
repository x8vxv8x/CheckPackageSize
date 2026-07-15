package com.smd.checkpackagesize.diagnostics;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.network.play.server.SPacketCustomPayload;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

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
    private PacketResolver() {
    }

    public static PacketIdentity resolve(Packet<?> packet, EnumPacketDirection direction) {
        String directionName = direction == EnumPacketDirection.SERVERBOUND ? "C2S" : "S2C";
        if (packet instanceof FMLProxyPacket proxy)
            return resolveForgePayload(proxy.channel(), proxy.payload(), directionName);
        if (packet instanceof CPacketCustomPayload payload)
            return resolveForgePayload(payload.getChannelName(), payload.getBufferData(), directionName);
        if (packet instanceof SPacketCustomPayload payload)
            return resolveForgePayload(payload.getChannelName(), payload.getBufferData(), directionName);
        return VANILLA_IDENTITIES.get(packet.getClass())[direction == EnumPacketDirection.SERVERBOUND ? 0 : 1];
    }

    private static PacketIdentity resolveForgePayload(String channel, ByteBuf payload, String direction) {
        int discriminator = MessageRegistry.hasChannel(channel) ? readDiscriminator(payload) : -1;
        return ForgeIdentityResolver.resolve(channel, discriminator, direction);
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
