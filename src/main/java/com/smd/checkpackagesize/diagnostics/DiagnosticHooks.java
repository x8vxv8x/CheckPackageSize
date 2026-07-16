package com.smd.checkpackagesize.diagnostics;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

public final class DiagnosticHooks {

    private static final int THEORETICAL_COMPRESSION_THRESHOLD = 256;
    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(Deflater::new);
    private static final ThreadLocal<byte[]> COMPRESSION_BUFFER = ThreadLocal.withInitial(() -> new byte[8192]);
    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Map<Packet<?>, ArrayDeque<CallerTrace>> OUTBOUND_TRACES = new IdentityHashMap<>();
    private static final ThreadLocal<ForgeTraceContext> FORGE_TRACE = new ThreadLocal<>();
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

    public static void resetTransientState() {
        synchronized (OUTBOUND_TRACES) {
            OUTBOUND_TRACES.clear();
        }
        FORGE_TRACE.remove();
    }

    public static void onRemotePacketQueued(Packet<?> packet, EnumPacketDirection inboundDirection) {
        if (packet == null) return;
        EnumPacketDirection outbound = opposite(inboundDirection);
        DiagnosticsManager.Session session = DiagnosticsManager.session(endpointForOutbound(outbound));
        if (session == null || session.traceDepth == 0) return;
        CallerTrace trace = captureCallerTrace(session.traceDepth);
        if (trace == null) return;
        synchronized (OUTBOUND_TRACES) {
            OUTBOUND_TRACES.computeIfAbsent(packet, ignored -> new ArrayDeque<>()).addLast(trace);
        }
    }

    public static CallerTrace takeOutboundTrace(Channel channel, Packet<?> packet) {
        CallerTrace direct = takeRegisteredTrace(packet);
        if (direct != null) return direct;
        ForgeTraceContext context = FORGE_TRACE.get();
        return context != null && context.channel == channel ? context.trace : null;
    }

    public static void beginForgeProxyWrite(Channel channel, Object message) {
        if (message instanceof FMLProxyPacket proxy) {
            CallerTrace trace = takeRegisteredTrace(proxy);
            if (trace == null) FORGE_TRACE.remove();
            else FORGE_TRACE.set(new ForgeTraceContext(channel, trace));
        } else {
            FORGE_TRACE.remove();
        }
    }

    public static void endForgeProxyWrite(Object message) {
        if (message instanceof FMLProxyPacket) FORGE_TRACE.remove();
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
        CallerTrace trace = session.traceDepth == 0 ? null : captureCallerTrace(session.traceDepth);
        long timelineSecond = session.currentSecond();
        byte[][] frames = encodeLocalFrames(packet, state, outbound);
        if (frames == null) {
            session.dropped.increment();
            return;
        }

        session.packet(identity).endpoint(sender).sentCount.increment();
        session.recordTraceSent(trace, identity);
        session.pendingAsync.incrementAndGet();
        try {
            LOCAL_WORKER.execute(() -> finishLocalTraffic(session, identity, trace, sender, timelineSecond, frames));
        } catch (RejectedExecutionException exception) {
            session.pendingAsync.decrementAndGet();
            session.dropped.increment();
        }
    }

    public static void onEncoded(Channel channel, EnumPacketDirection direction, PacketIdentity identity,
                                 CallerTrace trace, int encodedBytes, boolean compressionEnabled) {
        if (identity == null || encodedBytes < 0) return;
        Endpoint endpoint = endpointForOutbound(direction);
        DiagnosticsManager.Session session = DiagnosticsManager.session(endpoint);
        if (session == null) return;

        int framedBytes = PacketResolver.framedSize(encodedBytes);
        session.packet(identity).endpoint(endpoint).sentCount.increment();
        session.recordTraceSent(trace, identity);
        if (compressionEnabled) {
            session.outboundCompression.computeIfAbsent(channel, ignored -> new DiagnosticsManager.PendingPacketQueue())
                    .add(identity, endpoint, trace, framedBytes);
        } else {
            session.recordSentWire(endpoint, identity, trace, framedBytes, framedBytes);
        }
    }

    public static void onCompressedOutbound(Channel channel, int bytes) {
        for (Endpoint endpoint : Endpoint.values()) {
            DiagnosticsManager.Session session = DiagnosticsManager.session(endpoint);
            if (session == null) continue;
            DiagnosticsManager.PendingPacketQueue queue = session.outboundCompression.get(channel);
            if (queue != null && !queue.isEmpty()) {
                session.recordSentWire(queue.endpoint(), queue.identity(), queue.trace(), queue.encodedBytes(),
                        PacketResolver.framedSize(bytes));
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
                        .add(PacketResolver.framedSize(bytes));
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

    private static byte[][] encodeLocalFrames(Packet<?> packet, EnumConnectionState state, EnumPacketDirection direction) {
        try {
            if (packet instanceof FMLProxyPacket proxy && proxy.payload() != null) {
                if (direction == EnumPacketDirection.SERVERBOUND) {
                    PacketBuffer payload = new PacketBuffer(proxy.payload().duplicate());
                    byte[] encoded = encodeVanillaPacket(new CPacketCustomPayload(proxy.channel(), payload), state, direction);
                    return encoded == null ? null : new byte[][] { encoded };
                }
                List<? extends Packet<?>> packets = proxy.copy().toS3FPackets();
                byte[][] frames = new byte[packets.size()][];
                for (int index = 0; index < packets.size(); index++) {
                    frames[index] = encodeVanillaPacket(packets.get(index), state, direction);
                    if (frames[index] == null) return null;
                }
                return frames;
            }
            byte[] encoded = encodeVanillaPacket(packet, state, direction);
            return encoded == null ? null : new byte[][] { encoded };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] encodeVanillaPacket(Packet<?> packet, EnumConnectionState state,
                                              EnumPacketDirection direction) throws Exception {
        Integer packetId = state.getPacketId(direction, packet);
        if (packetId == null) return null;
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
    }

    private static void finishLocalTraffic(DiagnosticsManager.Session session, PacketIdentity identity, CallerTrace trace,
                                           Endpoint sender, long timelineSecond, byte[][] frames) {
        try {
            long encodedBytes = 0L;
            long transferredBytes = 0L;
            for (byte[] frame : frames) {
                encodedBytes += PacketResolver.framedSize(frame.length);
                transferredBytes += compressedFrameSize(frame, THEORETICAL_COMPRESSION_THRESHOLD);
            }
            session.recordLocalWire(sender, identity, trace, encodedBytes, transferredBytes, timelineSecond);
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

    private static CallerTrace takeRegisteredTrace(Packet<?> packet) {
        if (packet == null) return null;
        synchronized (OUTBOUND_TRACES) {
            ArrayDeque<CallerTrace> traces = OUTBOUND_TRACES.get(packet);
            if (traces == null) return null;
            CallerTrace trace = traces.pollFirst();
            if (traces.isEmpty()) OUTBOUND_TRACES.remove(packet);
            return trace;
        }
    }

    private static CallerTrace captureCallerTrace(int depth) {
        return STACK_WALKER.walk(stream -> {
            List<StackWalker.StackFrame> frames = stream
                    .filter(frame -> !isNoiseFrame(frame.getClassName()))
                    .limit(depth)
                    .toList();
            if (frames.isEmpty()) return null;

            StackWalker.StackFrame selected = null;
            ArrayList<String> rendered = new ArrayList<>(frames.size());
            StringBuilder pathKey = new StringBuilder(frames.size() * 64);
            for (StackWalker.StackFrame frame : frames) {
                rendered.add(renderFrame(frame));
                pathKey.append(frame.getClassName()).append('#').append(frame.getMethodName())
                        .append(':').append(frame.getLineNumber()).append('\n');
                if (selected == null && !isNetworkInfrastructure(frame.getClassName())) selected = frame;
            }
            if (selected == null) selected = frames.getFirst();
            return new CallerTrace(pathKey.toString(), selected.getClassName(), selected.getMethodName(),
                    selected.getLineNumber(), rendered);
        });
    }

    private static boolean isNetworkInfrastructure(String className) {
        return className.startsWith("net.minecraft.network.")
                || className.startsWith("net.minecraft.client.network.")
                || className.startsWith("net.minecraft.server.network.")
                || className.startsWith("net.minecraftforge.fml.common.network.")
                || className.startsWith("io.netty.");
    }

    private static boolean isNoiseFrame(String className) {
        return className.startsWith("com.smd.checkpackagesize.") || className.startsWith("io.netty.");
    }

    private static String renderFrame(StackWalker.StackFrame frame) {
        String location = frame.getFileName() == null ? "Unknown Source" : frame.getFileName()
                + (frame.getLineNumber() < 0 ? "" : ":" + frame.getLineNumber());
        return frame.getClassName() + '.' + frame.getMethodName() + '(' + location + ')';
    }

    private record ForgeTraceContext(Channel channel, CallerTrace trace) { }
}
