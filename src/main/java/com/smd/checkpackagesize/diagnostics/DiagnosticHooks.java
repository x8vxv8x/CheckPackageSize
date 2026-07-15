package com.smd.checkpackagesize.diagnostics;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;

public final class DiagnosticHooks {

    private static final int THEORETICAL_COMPRESSION_THRESHOLD = 256;
    private static final ThreadLocal<Trace> CURRENT_TRACE = new ThreadLocal<>();
    private static final ThreadPoolExecutor LOCAL_WORKER = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512), runnable -> {
        Thread thread = new Thread(runnable, "CheckPackageSize-LocalSimulation");
        thread.setDaemon(true);
        return thread;
    });

    private DiagnosticHooks() {
    }

    public static boolean isCapturing() {
        return DiagnosticsManager.isCapturing();
    }

    public static void onPacketQueued(Packet<?> packet, EnumPacketDirection inboundDirection, boolean localChannel,
                                      EnumConnectionState state) {
        DiagnosticsManager.Session session = DiagnosticsManager.session();
        if (session == null || packet == null) {
            return;
        }
        EnumPacketDirection outbound = opposite(inboundDirection);
        PacketIdentity identity = PacketResolver.resolve(packet, outbound);
        String callSiteKey = identity.modId + '\0' + identity.packetClass;
        DiagnosticsManager.CallSite callSite = session.sampledCallSites.putIfAbsent(callSiteKey, Boolean.TRUE) == null
                ? findCallSite() : null;
        session.rememberCallSite(packet, callSite);
        if (localChannel) {
            simulateLocal(session, packet, identity, PacketResolver.endpointForOutbound(outbound), state, outbound, callSite);
            session.takeCallSite(packet);
        }
    }

    public static void onEncoded(Channel channel, EnumPacketDirection direction, Packet<?> packet, int encodedBytes,
                                 long encodeNanos, boolean compressionEnabled) {
        DiagnosticsManager.Session session = DiagnosticsManager.session();
        if (session == null || packet == null || encodedBytes < 0) {
            return;
        }
        PacketIdentity identity = PacketResolver.resolve(packet, direction);
        String endpoint = PacketResolver.endpointForOutbound(direction);
        DiagnosticsManager.MutablePacket mutable = session.packet(identity);
        DiagnosticsManager.MutableEndpoint metrics = mutable.endpoint(endpoint);
        metrics.sentCount.increment();
        metrics.encodedBytes.add(PacketResolver.framedSize(encodedBytes));
        metrics.encodeNanos.add(Math.max(0L, encodeNanos));
        DiagnosticsManager.CallSite callSite = session.takeCallSite(packet);
        if (callSite != null) {
            mutable.firstCallSite.compareAndSet(null, callSite.text);
        }
        if (compressionEnabled) {
            session.outboundCompression.computeIfAbsent(channel, ignored -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                    .add(new DiagnosticsManager.PendingPacket(identity, endpoint));
        } else {
            metrics.transferredBytes.add(PacketResolver.framedSize(encodedBytes));
        }
    }

    public static void onCompressedOutbound(Channel channel, int bytes) {
        DiagnosticsManager.Session session = DiagnosticsManager.session();
        if (session == null) {
            return;
        }
        Queue<DiagnosticsManager.PendingPacket> queue = session.outboundCompression.get(channel);
        DiagnosticsManager.PendingPacket pending = queue == null ? null : queue.poll();
        if (pending != null) {
            session.packet(pending.identity).endpoint(pending.endpoint).transferredBytes.add(PacketResolver.framedSize(bytes));
        }
    }

    public static void onCompressedInbound(Channel channel, int bytes) {
        DiagnosticsManager.Session session = DiagnosticsManager.session();
        if (session != null) {
            session.inboundCompression.computeIfAbsent(channel, ignored -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                    .add((long) PacketResolver.framedSize(bytes));
        }
    }

    public static void onDecoded(Channel channel, EnumPacketDirection direction, Packet<?> packet, int encodedBytes,
                                 long decodeNanos, boolean compressionEnabled) {
        DiagnosticsManager.Session session = DiagnosticsManager.session();
        if (session == null || packet == null || encodedBytes < 0) {
            return;
        }
        PacketIdentity identity = PacketResolver.resolve(packet, direction);
        String endpoint = PacketResolver.endpointForInbound(direction);
        DiagnosticsManager.MutableEndpoint metrics = session.packet(identity).endpoint(endpoint);
        metrics.receivedCount.increment();
        metrics.receivedBytes.add(PacketResolver.framedSize(encodedBytes));
        metrics.decodeNanos.add(Math.max(0L, decodeNanos));
        if (compressionEnabled) {
            Queue<Long> queue = session.inboundCompression.get(channel);
            Long transferred = queue == null ? null : queue.poll();
            metrics.transferredBytes.add(transferred == null ? PacketResolver.framedSize(encodedBytes) : transferred);
        } else {
            metrics.transferredBytes.add(PacketResolver.framedSize(encodedBytes));
        }
    }

    public static void beginInbound(Packet<?> packet, EnumPacketDirection direction) {
        if (!isCapturing() || packet == null) {
            CURRENT_TRACE.remove();
            return;
        }
        CURRENT_TRACE.set(new Trace(PacketResolver.resolve(packet, direction), PacketResolver.endpointForInbound(direction)));
    }

    public static void endInbound() {
        CURRENT_TRACE.remove();
    }

    public static <V> Callable<V> wrapScheduled(Callable<V> original) {
        Trace trace = CURRENT_TRACE.get();
        if (trace == null || original == null || original instanceof TrackedCallable) {
            return original;
        }
        return new TrackedCallable<>(original, trace, System.nanoTime());
    }

    private static void recordMainThread(Trace trace, long waitNanos, long workNanos) {
        DiagnosticsManager.Session session = DiagnosticsManager.session();
        if (session == null) {
            return;
        }
        DiagnosticsManager.MutableEndpoint metrics = session.packet(trace.identity).endpoint(trace.endpoint);
        metrics.mainThreadTasks.increment();
        metrics.queueWaitNanos.add(Math.max(0L, waitNanos));
        metrics.mainThreadNanos.add(Math.max(0L, workNanos));
        updateMax(metrics.maxQueueWaitNanos, waitNanos);
    }

    private static void simulateLocal(DiagnosticsManager.Session session, Packet<?> packet, PacketIdentity identity,
                                      String senderEndpoint, EnumConnectionState state, EnumPacketDirection direction,
                                      DiagnosticsManager.CallSite callSite) {
        if (state == null) {
            session.dropped.increment();
            return;
        }
        ByteBuf buffer = Unpooled.buffer();
        int packetId;
        long start = System.nanoTime();
        try {
            Integer id = state.getPacketId(direction, packet);
            if (id == null) {
                session.dropped.increment();
                return;
            }
            packetId = id;
            PacketBuffer packetBuffer = new PacketBuffer(buffer);
            packetBuffer.writeVarInt(packetId);
            packet.writePacketData(packetBuffer);
            long encodeNanos = System.nanoTime() - start;
            int encodedBytes = buffer.readableBytes();
            byte[] data = new byte[encodedBytes];
            buffer.getBytes(buffer.readerIndex(), data);
            DiagnosticsManager.MutablePacket mutable = session.packet(identity);
            DiagnosticsManager.MutableEndpoint sender = mutable.endpoint(senderEndpoint);
            sender.sentCount.increment();
            sender.encodedBytes.add(PacketResolver.framedSize(encodedBytes));
            sender.encodeNanos.add(encodeNanos);
            if (callSite != null) {
                mutable.firstCallSite.compareAndSet(null, callSite.text);
            }
            session.pendingAsync.incrementAndGet();
            try {
                LOCAL_WORKER.execute(() -> finishLocalSimulation(session, identity, senderEndpoint, state, direction, data));
            } catch (RejectedExecutionException exception) {
                session.pendingAsync.decrementAndGet();
                session.dropped.increment();
            }
        } catch (Throwable ignored) {
            session.dropped.increment();
        } finally {
            buffer.release();
        }
    }

    private static void finishLocalSimulation(DiagnosticsManager.Session session, PacketIdentity identity, String senderEndpoint,
                                              EnumConnectionState state, EnumPacketDirection direction, byte[] data) {
        try {
            int compressed = compressedFrameSize(data, THEORETICAL_COMPRESSION_THRESHOLD);
            session.packet(identity).endpoint(senderEndpoint).transferredBytes.add(compressed);

            PacketBuffer input = new PacketBuffer(Unpooled.wrappedBuffer(data));
            long start = System.nanoTime();
            int packetId = input.readVarInt();
            Packet<?> decoded = state.getPacket(direction, packetId);
            if (decoded != null) {
                decoded.readPacketData(input);
                long decodeNanos = System.nanoTime() - start;
                String receiver = "CLIENT".equals(senderEndpoint) ? "SERVER" : "CLIENT";
                DiagnosticsManager.MutableEndpoint metrics = session.packet(identity).endpoint(receiver);
                metrics.receivedCount.increment();
                metrics.receivedBytes.add(PacketResolver.framedSize(data.length));
                metrics.transferredBytes.add(compressed);
                metrics.decodeNanos.add(decodeNanos);
            }
            input.release();
        } catch (Throwable ignored) {
            session.dropped.increment();
        } finally {
            session.pendingAsync.decrementAndGet();
        }
    }

    private static int compressedFrameSize(byte[] data, int threshold) {
        if (data.length < threshold) {
            int payload = 1 + data.length;
            return PacketResolver.framedSize(payload);
        }
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(data);
            deflater.finish();
            byte[] output = new byte[Math.max(64, data.length + 64)];
            int compressed = deflater.deflate(output);
            int payload = varIntSize(data.length) + compressed;
            return PacketResolver.framedSize(payload);
        } finally {
            deflater.end();
        }
    }

    private static int varIntSize(int value) {
        if ((value & 0xFFFFFF80) == 0) return 1;
        if ((value & 0xFFFFC000) == 0) return 2;
        if ((value & 0xFFE00000) == 0) return 3;
        if ((value & 0xF0000000) == 0) return 4;
        return 5;
    }

    private static DiagnosticsManager.CallSite findCallSite() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.startsWith("java.") || className.startsWith("sun.") || className.startsWith("io.netty.")
                    || className.startsWith("net.minecraft.") || className.startsWith("net.minecraftforge.")
                    || className.startsWith("com.smd.checkpackagesize.")) {
                continue;
            }
            String modId = ModResolver.resolveClassName(className);
            if (!"unknown".equals(modId) && !"minecraft".equals(modId) && !"forge".equals(modId)
                    && !"checkpackagesize".equals(modId)) {
                return new DiagnosticsManager.CallSite(frame.toString(), modId);
            }
        }
        return null;
    }

    private static EnumPacketDirection opposite(EnumPacketDirection direction) {
        return direction == EnumPacketDirection.CLIENTBOUND ? EnumPacketDirection.SERVERBOUND : EnumPacketDirection.CLIENTBOUND;
    }

    private static void updateMax(AtomicLong target, long value) {
        long current;
        do {
            current = target.get();
            if (value <= current) {
                return;
            }
        } while (!target.compareAndSet(current, value));
    }

    private static final class Trace {
        final PacketIdentity identity;
        final String endpoint;

        private Trace(PacketIdentity identity, String endpoint) {
            this.identity = identity;
            this.endpoint = endpoint;
        }
    }

    private static final class TrackedCallable<V> implements Callable<V> {
        private final Callable<V> delegate;
        private final Trace trace;
        private final long queuedAt;

        private TrackedCallable(Callable<V> delegate, Trace trace, long queuedAt) {
            this.delegate = delegate;
            this.trace = trace;
            this.queuedAt = queuedAt;
        }

        @Override
        public V call() throws Exception {
            long started = System.nanoTime();
            Trace previous = CURRENT_TRACE.get();
            CURRENT_TRACE.set(trace);
            try {
                return delegate.call();
            } finally {
                long ended = System.nanoTime();
                recordMainThread(trace, started - queuedAt, ended - started);
                if (previous == null) {
                    CURRENT_TRACE.remove();
                } else {
                    CURRENT_TRACE.set(previous);
                }
            }
        }
    }
}
