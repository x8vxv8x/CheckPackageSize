package com.smd.checkpackagesize.diagnostics;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

public final class PacketResolver {

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
                return new PacketIdentity(directionName, entry.modId, channel, entry.messageClass, entry.handlerClass);
            }
            String modId = channel == null || channel.isEmpty() ? "forge" : channel;
            return new PacketIdentity(directionName, modId, channel, packet.getClass().getName(), "-");
        }
        return new PacketIdentity(directionName, ModResolver.resolve(packet.getClass()), "-",
                packet.getClass().getName(), "-");
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
