package com.smd.checkpackagesize.diagnostics;

import com.smd.checkpackagesize.CheckPackageSize;
import io.netty.channel.Channel;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class DiagnosticsManager {

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final AtomicReference<TrafficReport> COMPLETED = new AtomicReference<>();
    private static final AtomicInteger FINALIZING = new AtomicInteger();
    private static final ExecutorService REPORT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static volatile Session active;
    private static volatile TrafficReport lastReport;
    private static File gameDirectory = new File(".");

    private DiagnosticsManager() {
    }

    public static void initialize(File directory) {
        if (directory != null) gameDirectory = directory;
    }

    public static synchronized boolean startClient(int durationSeconds, boolean singleplayer) {
        return start(singleplayer ? CaptureMode.SINGLEPLAYER_COMBINED : CaptureMode.CLIENT_ONLY, durationSeconds);
    }

    public static synchronized boolean startServer(int durationSeconds) {
        return start(CaptureMode.SERVER_ONLY, durationSeconds);
    }

    private static boolean start(CaptureMode mode, int durationSeconds) {
        if ((active != null && !active.isFinished()) || FINALIZING.get() > 0) return false;
        int seconds = Math.max(1, Math.min(300, durationSeconds));
        try {
            active = new Session(NEXT_ID.incrementAndGet(), mode, seconds, gameDirectory);
            COMPLETED.set(null);
            return true;
        } catch (Exception exception) {
            CheckPackageSize.LOGGER.error("Unable to start diagnostics session", exception);
            active = null;
            return false;
        }
    }

    public static synchronized TrafficReport stop(Endpoint endpoint) {
        Session session = active;
        if (session == null || !session.mode.includes(endpoint)) return lastReport;
        session.requestStop();
        return finishIfReady(session, false);
    }

    public static void tick(Endpoint endpoint) {
        Session session = active;
        if (session == null || !session.mode.includes(endpoint)) return;
        session.tick();
        finishIfReady(session, false);
    }

    private static synchronized TrafficReport finishIfReady(Session session, boolean force) {
        if (active != session || (!force && !session.readyToFinish())) return null;
        session.seal();
        TrafficReport report = session.snapshot();
        active = null;
        FINALIZING.incrementAndGet();
        REPORT_EXECUTOR.submit(() -> {
            try {
                ReportWriter.writeHtml(report, session.reportDirectory);
            } catch (Exception exception) {
                CheckPackageSize.LOGGER.error("Unable to write diagnostics HTML report", exception);
            }
            lastReport = report;
            COMPLETED.set(report);
            FINALIZING.decrementAndGet();
        });
        return report;
    }

    public static boolean isCapturing() {
        Session session = active;
        return session != null && session.accepting;
    }

    public static boolean isFinalizing() {
        return FINALIZING.get() > 0;
    }

    public static boolean isCapturing(Endpoint endpoint) {
        Session session = active;
        return session != null && session.accepting && session.mode.includes(endpoint);
    }

    public static long getRemainingMillis() {
        Session session = active;
        return session == null ? 0L : session.remainingMillis();
    }

    public static long getDurationMillis() {
        Session session = active;
        return session == null ? 0L : session.durationNanos / 1_000_000L;
    }

    public static TrafficReport getLastReport() {
        return lastReport;
    }

    public static TrafficReport pollCompleted() {
        return COMPLETED.getAndSet(null);
    }

    static Session session(Endpoint endpoint) {
        Session session = active;
        return session != null && session.mode.includes(endpoint) && session.canRecord() ? session : null;
    }

    static final class Session {
        final long id;
        final CaptureMode mode;
        final long startMillis;
        final long startNanos;
        final long durationNanos;
        final File reportDirectory;
        final ConcurrentMap<PacketIdentity, MutablePacket> packets = new ConcurrentHashMap<>();
        final ConcurrentMap<Channel, PendingPacketQueue> outboundCompression = new ConcurrentHashMap<>();
        final ConcurrentMap<Channel, LongQueue> inboundCompression = new ConcurrentHashMap<>();
        final AtomicInteger pendingAsync = new AtomicInteger();
        final LongAdder dropped = new LongAdder();
        volatile boolean accepting = true;
        volatile boolean sealed;
        volatile long stopRequestedNanos;

        Session(long id, CaptureMode mode, int durationSeconds, File gameDirectory) throws Exception {
            this.id = id;
            this.mode = mode;
            this.startMillis = System.currentTimeMillis();
            this.startNanos = System.nanoTime();
            this.durationNanos = durationSeconds * 1_000_000_000L;
            this.reportDirectory = ReportWriter.createSessionDirectory(gameDirectory, startMillis, id);
        }

        void tick() {
            if (accepting && System.nanoTime() - startNanos >= durationNanos) requestStop();
        }

        void requestStop() {
            if (accepting) {
                accepting = false;
                stopRequestedNanos = System.nanoTime();
            }
        }

        boolean canRecord() {
            return !sealed && (accepting || (stopRequestedNanos != 0L && System.nanoTime() - stopRequestedNanos < 2_000_000_000L));
        }

        boolean readyToFinish() {
            return !accepting && (pendingAsync.get() == 0 || System.nanoTime() - stopRequestedNanos >= 2_000_000_000L);
        }

        boolean isFinished() {
            return !accepting && readyToFinish();
        }

        void seal() {
            accepting = false;
            sealed = true;
        }

        long remainingMillis() {
            if (!accepting) return 0L;
            return Math.max(0L, (durationNanos - (System.nanoTime() - startNanos)) / 1_000_000L);
        }

        MutablePacket packet(PacketIdentity identity) {
            return packets.computeIfAbsent(identity, MutablePacket::new);
        }

        void recordSentWire(Endpoint endpoint, PacketIdentity identity, long encodedBytes, long transferredBytes) {
            MutableEndpoint metrics = packet(identity).endpoint(endpoint);
            metrics.encodedBytes.add(encodedBytes);
            metrics.transferredBytes.add(transferredBytes);
        }

        void recordReceivedWire(Endpoint endpoint, PacketIdentity identity, long encodedBytes, long transferredBytes) {
            MutableEndpoint metrics = packet(identity).endpoint(endpoint);
            metrics.receivedBytes.add(encodedBytes);
            metrics.transferredBytes.add(transferredBytes);
        }

        void recordLocalWire(Endpoint sender, PacketIdentity identity, long encodedBytes, long transferredBytes) {
            recordSentWire(sender, identity, encodedBytes, transferredBytes);
            Endpoint receiver = sender == Endpoint.CLIENT ? Endpoint.SERVER : Endpoint.CLIENT;
            MutableEndpoint received = packet(identity).endpoint(receiver);
            received.receivedCount.increment();
            received.receivedBytes.add(encodedBytes);
            received.transferredBytes.add(transferredBytes);
        }

        TrafficReport snapshot() {
            TrafficReport report = new TrafficReport();
            report.sessionId = id;
            report.mode = mode.name();
            report.startedAtMillis = startMillis;
            report.durationMillis = Math.min(durationNanos, Math.max(0L, System.nanoTime() - startNanos)) / 1_000_000L;
            report.localTheoretical = mode.localTheoretical();
            report.reportDirectory = reportDirectory.getAbsolutePath();
            report.droppedMeasurements = dropped.sum();
            ArrayList<MutablePacket> sorted = new ArrayList<>(packets.values());
            sorted.sort(Comparator.comparing(value -> value.identity.modId() + value.identity.packetClass() + value.identity.direction()));
            for (MutablePacket packet : sorted) {
                if (!"checkpackagesize".equals(packet.identity.modId())) report.packets.add(packet.snapshot());
            }
            return report;
        }

    }

    static final class MutablePacket {
        final PacketIdentity identity;
        final MutableEndpoint client = new MutableEndpoint();
        final MutableEndpoint server = new MutableEndpoint();
        MutablePacket(PacketIdentity identity) { this.identity = identity; }

        MutableEndpoint endpoint(Endpoint endpoint) { return endpoint == Endpoint.SERVER ? server : client; }

        TrafficReport.PacketRow snapshot() {
            TrafficReport.PacketRow row = new TrafficReport.PacketRow();
            row.direction = identity.direction();
            row.modId = identity.modId();
            row.channel = identity.channel();
            row.packetClass = identity.packetClass();
            row.handlerClass = identity.handlerClass();
            row.client = client.snapshot();
            row.server = server.snapshot();
            return row;
        }
    }

    static final class MutableEndpoint {
        final LongAdder encodedBytes = new LongAdder();
        final LongAdder receivedBytes = new LongAdder();
        final LongAdder transferredBytes = new LongAdder();
        final LongAdder sentCount = new LongAdder();
        final LongAdder receivedCount = new LongAdder();

        TrafficReport.EndpointMetrics snapshot() {
            TrafficReport.EndpointMetrics metrics = new TrafficReport.EndpointMetrics();
            metrics.sentCount = sentCount.sum();
            metrics.receivedCount = receivedCount.sum();
            metrics.encodedBytes = encodedBytes.sum();
            metrics.receivedBytes = receivedBytes.sum();
            metrics.transferredBytes = transferredBytes.sum();
            return metrics;
        }
    }

    static final class PendingPacketQueue {
        private PacketIdentity[] identities = new PacketIdentity[16];
        private Endpoint[] endpoints = new Endpoint[16];
        private long[] encodedBytes = new long[16];
        private int head;
        private int size;

        void add(PacketIdentity identity, Endpoint endpoint, long bytes) {
            ensureCapacity();
            int tail = (head + size) % identities.length;
            identities[tail] = identity;
            endpoints[tail] = endpoint;
            encodedBytes[tail] = bytes;
            size++;
        }

        boolean isEmpty() { return size == 0; }
        PacketIdentity identity() { return identities[head]; }
        Endpoint endpoint() { return endpoints[head]; }
        long encodedBytes() { return encodedBytes[head]; }

        void remove() {
            identities[head] = null;
            endpoints[head] = null;
            head = (head + 1) % identities.length;
            size--;
        }

        private void ensureCapacity() {
            if (size < identities.length) return;
            int nextCapacity = identities.length << 1;
            PacketIdentity[] nextIdentities = new PacketIdentity[nextCapacity];
            Endpoint[] nextEndpoints = new Endpoint[nextCapacity];
            long[] nextBytes = new long[nextCapacity];
            for (int index = 0; index < size; index++) {
                int source = (head + index) % identities.length;
                nextIdentities[index] = identities[source];
                nextEndpoints[index] = endpoints[source];
                nextBytes[index] = encodedBytes[source];
            }
            identities = nextIdentities;
            endpoints = nextEndpoints;
            encodedBytes = nextBytes;
            head = 0;
        }
    }

    static final class LongQueue {
        private long[] values = new long[16];
        private int head;
        private int size;

        void add(long value) {
            if (size == values.length) grow();
            values[(head + size) % values.length] = value;
            size++;
        }

        long poll(long fallback) {
            if (size == 0) return fallback;
            long value = values[head];
            head = (head + 1) % values.length;
            size--;
            return value;
        }

        private void grow() {
            long[] next = new long[values.length << 1];
            for (int index = 0; index < size; index++) next[index] = values[(head + index) % values.length];
            values = next;
            head = 0;
        }
    }
}
