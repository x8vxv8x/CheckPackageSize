package com.smd.checkpackagesize.diagnostics;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

public final class DiagnosticHooks {

    private static final int THEORETICAL_COMPRESSION_THRESHOLD = 256;
    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(Deflater::new);
    private static final ThreadLocal<byte[]> COMPRESSION_BUFFER = ThreadLocal.withInitial(() -> new byte[8192]);
    private static final ThreadPoolExecutor LOCAL_WORKER = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512), runnable -> {
        Thread thread = new Thread(runnable, "CheckPackageSize-LocalTraffic");
        thread.setDaemon(true);
        return thread;
    });

    private DiagnosticHooks() {
    }

    public static boolean isCapturing() {
        return DiagnosticsManager.isCapturing();
    }

    public static void onLocalPacket(Packet<?> packet, EnumPacketDirection inboundDirection, EnumConnectionState state) {
        if (packet == null || state == null) return;
        EnumPacketDirection outbound = opposite(inboundDirection);
        Endpoint sender = endpointForOutbound(outbound);
        DiagnosticsManager.Session session = DiagnosticsManager.session(sender);
        if (session == null) return;
        if (LOCAL_WORKER.getQueue().remainingCapacity() == 0) {
            session.dropped.increment();
            return;
        }

        PacketIdentity identity = PacketResolver.resolve(packet, outbound);
        byte[] data = encodeLocal(packet, state, outbound);
        if (data == null) {
            session.dropped.increment();
            return;
        }

        session.packet(identity).endpoint(sender).sentCount.increment();
        session.pendingAsync.incrementAndGet();
        try {
            LOCAL_WORKER.execute(() -> finishLocalTraffic(session, identity, sender, data));
        } catch (RejectedExecutionException exception) {
            session.pendingAsync.decrementAndGet();
            session.dropped.increment();
        }
    }

    public static void onEncoded(Channel channel, EnumPacketDirection direction, Packet<?> packet, int encodedBytes,
                                 boolean compressionEnabled) {
        if (packet == null || encodedBytes < 0) return;
        Endpoint endpoint = endpointForOutbound(direction);
        DiagnosticsManager.Session session = DiagnosticsManager.session(endpoint);
        if (session == null) return;

        PacketIdentity identity = PacketResolver.resolve(packet, direction);
        int framedBytes = PacketResolver.framedSize(encodedBytes);
        session.packet(identity).endpoint(endpoint).sentCount.increment();
        if (compressionEnabled) {
            session.outboundCompression.computeIfAbsent(channel, ignored -> new DiagnosticsManager.PendingPacketQueue())
                    .add(identity, endpoint, framedBytes);
        } else {
            session.recordSentWire(endpoint, identity, framedBytes, framedBytes);
        }
    }

    public static void onCompressedOutbound(Channel channel, int bytes) {
        for (Endpoint endpoint : Endpoint.values()) {
            DiagnosticsManager.Session session = DiagnosticsManager.session(endpoint);
            if (session == null) continue;
            DiagnosticsManager.PendingPacketQueue queue = session.outboundCompression.get(channel);
            if (queue != null && !queue.isEmpty()) {
                session.recordSentWire(queue.endpoint(), queue.identity(), queue.encodedBytes(), PacketResolver.framedSize(bytes));
                queue.remove();
                return;
            }
        }
    }

    public static void onCompressedInbound(Channel channel, int bytes) {
        for (Endpoint endpoint : Endpoint.values()) {
            DiagnosticsManager.Session session = DiagnosticsManager.session(endpoint);
            if (session != null) {
                session.inboundCompression.computeIfAbsent(channel, ignored -> new DiagnosticsManager.LongQueue())
                        .add((long) PacketResolver.framedSize(bytes));
                return;
            }
        }
    }

    public static void onDecoded(Channel channel, EnumPacketDirection direction, Packet<?> packet, int encodedBytes,
                                 boolean compressionEnabled) {
        if (packet == null || encodedBytes < 0) return;
        Endpoint endpoint = endpointForInbound(direction);
        DiagnosticsManager.Session session = DiagnosticsManager.session(endpoint);
        if (session == null) return;

        PacketIdentity identity = PacketResolver.resolve(packet, direction);
        int framedBytes = PacketResolver.framedSize(encodedBytes);
        session.packet(identity).endpoint(endpoint).receivedCount.increment();
        if (compressionEnabled) {
            DiagnosticsManager.LongQueue queue = session.inboundCompression.get(channel);
            long transferred = queue == null ? framedBytes : queue.poll(framedBytes);
            session.recordReceivedWire(endpoint, identity, framedBytes, transferred);
        } else {
            session.recordReceivedWire(endpoint, identity, framedBytes, framedBytes);
        }
    }

    private static byte[] encodeLocal(Packet<?> packet, EnumConnectionState state, EnumPacketDirection direction) {
        try {
            Integer packetId = state.getPacketId(direction, packet);
            if (packetId == null) return null;
            if (packet instanceof FMLProxyPacket proxy && proxy.payload() != null) {
                ByteBuf payload = proxy.payload();
                int idBytes = varIntSize(packetId);
                byte[] data = new byte[idBytes + payload.readableBytes()];
                writeVarInt(data, 0, packetId);
                payload.getBytes(payload.readerIndex(), data, idBytes, payload.readableBytes());
                return data;
            }

            ByteBuf buffer = Unpooled.buffer();
            try {
                PacketBuffer packetBuffer = new PacketBuffer(buffer);
                packetBuffer.writeVarInt(packetId);
                packet.writePacketData(packetBuffer);
                byte[] data = new byte[buffer.readableBytes()];
                buffer.getBytes(buffer.readerIndex(), data);
                return data;
            } finally {
                buffer.release();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void finishLocalTraffic(DiagnosticsManager.Session session, PacketIdentity identity, Endpoint sender,
                                           byte[] data) {
        try {
            int encodedBytes = PacketResolver.framedSize(data.length);
            int transferredBytes = compressedFrameSize(data, THEORETICAL_COMPRESSION_THRESHOLD);
            session.recordLocalWire(sender, identity, encodedBytes, transferredBytes);
        } catch (Throwable ignored) {
            session.dropped.increment();
        } finally {
            session.pendingAsync.decrementAndGet();
        }
    }

    private static int compressedFrameSize(byte[] data, int threshold) {
        if (data.length < threshold) return PacketResolver.framedSize(1 + data.length);
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();
        byte[] output = COMPRESSION_BUFFER.get();
        int compressed = 0;
        while (!deflater.finished()) {
            compressed += deflater.deflate(output);
        }
        return PacketResolver.framedSize(varIntSize(data.length) + compressed);
    }

    private static void writeVarInt(byte[] target, int offset, int value) {
        int index = offset;
        while ((value & -128) != 0) {
            target[index++] = (byte) (value & 127 | 128);
            value >>>= 7;
        }
        target[index] = (byte) value;
    }

    private static int varIntSize(int value) {
        if ((value & 0xFFFFFF80) == 0) return 1;
        if ((value & 0xFFFFC000) == 0) return 2;
        if ((value & 0xFFE00000) == 0) return 3;
        if ((value & 0xF0000000) == 0) return 4;
        return 5;
    }

    private static EnumPacketDirection opposite(EnumPacketDirection direction) {
        return direction == EnumPacketDirection.CLIENTBOUND ? EnumPacketDirection.SERVERBOUND : EnumPacketDirection.CLIENTBOUND;
    }

    private static Endpoint endpointForInbound(EnumPacketDirection direction) {
        return Endpoint.valueOf(PacketResolver.endpointForInbound(direction));
    }

    private static Endpoint endpointForOutbound(EnumPacketDirection direction) {
        return Endpoint.valueOf(PacketResolver.endpointForOutbound(direction));
    }
}
