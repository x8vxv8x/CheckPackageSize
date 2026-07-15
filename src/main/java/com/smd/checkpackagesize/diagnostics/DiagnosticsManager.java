package com.smd.checkpackagesize.diagnostics;

import com.smd.checkpackagesize.CheckPackageSize;
import io.netty.channel.Channel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Queue;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class DiagnosticsManager {

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final AtomicReference<TrafficReport> COMPLETED = new AtomicReference<>();
    private static volatile Session active;
    private static volatile TrafficReport lastReport;
    private static volatile TrafficReport pendingRemoteReport;
    private static File gameDirectory = new File(".");

    private DiagnosticsManager() {
    }

    public static void initialize(File directory) {
        if (directory != null) {
            gameDirectory = directory;
        }
    }

    public static synchronized boolean start(int durationSeconds, boolean localTheoretical) {
        if (active != null && !active.isFinished()) {
            return false;
        }
        int seconds = Math.max(1, Math.min(300, durationSeconds));
        active = new Session(NEXT_ID.incrementAndGet(), seconds, localTheoretical);
        pendingRemoteReport = null;
        COMPLETED.set(null);
        return true;
    }

    public static synchronized TrafficReport stop() {
        Session session = active;
        if (session == null) {
            return lastReport;
        }
        session.requestStop();
        return finishIfReady(session, true);
    }

    public static void tick() {
        Session session = active;
        if (session == null) {
            return;
        }
        session.tick();
        finishIfReady(session, false);
    }

    private static synchronized TrafficReport finishIfReady(Session session, boolean force) {
        if (active != session) {
            return lastReport;
        }
        if (!force && !session.readyToFinish()) {
            return null;
        }
        TrafficReport report = session.snapshot();
        TrafficReport remote = pendingRemoteReport;
        if (remote != null) {
            report = TrafficReportMerger.merge(report, remote);
            pendingRemoteReport = null;
        }
        active = null;
        try {
            ReportWriter.write(report, gameDirectory);
        } catch (Exception exception) {
            CheckPackageSize.LOGGER.error("Unable to write diagnostics report", exception);
        }
        lastReport = report;
        COMPLETED.set(report);
        return report;
    }

    public static TrafficReport pollCompleted() {
        return COMPLETED.getAndSet(null);
    }

    public static TrafficReport getLastReport() {
        return lastReport;
    }

    public static synchronized TrafficReport mergeRemoteReport(TrafficReport remote) {
        if (active != null) {
            pendingRemoteReport = remote;
            return remote;
        }
        TrafficReport merged = TrafficReportMerger.merge(lastReport, remote);
        if (merged == null) {
            return null;
        }
        try {
            ReportWriter.write(merged, gameDirectory);
        } catch (Exception exception) {
            CheckPackageSize.LOGGER.error("Unable to write merged diagnostics report", exception);
        }
        lastReport = merged;
        COMPLETED.set(merged);
        return merged;
    }

    public static boolean isCapturing() {
        Session session = active;
        return session != null && session.accepting;
    }

    public static long getRemainingMillis() {
        Session session = active;
        return session == null ? 0L : session.remainingMillis();
    }

    public static long getDurationMillis() {
        Session session = active;
        return session == null ? 0L : session.durationNanos / 1_000_000L;
    }

    static Session session() {
        Session session = active;
        return session != null && session.accepting ? session : null;
    }

    static final class Session {
        final long id;
        final long startMillis;
        final long startNanos;
        final long durationNanos;
        final boolean localTheoretical;
        final ConcurrentMap<PacketIdentity, MutablePacket> packets = new ConcurrentHashMap<>();
        final ConcurrentMap<Channel, Queue<PendingPacket>> outboundCompression = new ConcurrentHashMap<>();
        final ConcurrentMap<Channel, Queue<Long>> inboundCompression = new ConcurrentHashMap<>();
        final Map<Object, CallSite> callSites = Collections.synchronizedMap(new IdentityHashMap<>());
        final ConcurrentMap<String, Boolean> sampledCallSites = new ConcurrentHashMap<>();
        final AtomicInteger pendingAsync = new AtomicInteger();
        final LongAdder dropped = new LongAdder();
        volatile boolean accepting = true;
        volatile long stopRequestedNanos;

        Session(long id, int durationSeconds, boolean localTheoretical) {
            this.id = id;
            this.startMillis = System.currentTimeMillis();
            this.startNanos = System.nanoTime();
            this.durationNanos = durationSeconds * 1_000_000_000L;
            this.localTheoretical = localTheoretical;
        }

        void tick() {
            if (accepting && System.nanoTime() - startNanos >= durationNanos) {
                requestStop();
            }
        }

        void requestStop() {
            if (accepting) {
                accepting = false;
                stopRequestedNanos = System.nanoTime();
            }
        }

        boolean readyToFinish() {
            if (accepting) {
                return false;
            }
            return pendingAsync.get() == 0 || System.nanoTime() - stopRequestedNanos >= 2_000_000_000L;
        }

        boolean isFinished() {
            return !accepting && readyToFinish();
        }

        long remainingMillis() {
            if (!accepting) {
                return 0L;
            }
            return Math.max(0L, (durationNanos - (System.nanoTime() - startNanos)) / 1_000_000L);
        }

        MutablePacket packet(PacketIdentity identity) {
            return packets.computeIfAbsent(identity, MutablePacket::new);
        }

        void rememberCallSite(Object packet, CallSite callSite) {
            if (packet != null && callSite != null) {
                callSites.put(packet, callSite);
            }
        }

        CallSite takeCallSite(Object packet) {
            return packet == null ? null : callSites.remove(packet);
        }

        TrafficReport snapshot() {
            TrafficReport report = new TrafficReport();
            report.sessionId = id;
            report.startedAtMillis = startMillis;
            report.durationMillis = Math.min(durationNanos, Math.max(0L, System.nanoTime() - startNanos)) / 1_000_000L;
            report.localTheoretical = localTheoretical;
            report.droppedMeasurements = dropped.sum();
            ArrayList<MutablePacket> sorted = new ArrayList<>(packets.values());
            sorted.sort(Comparator.comparing(value -> value.identity.modId + value.identity.packetClass + value.identity.direction));
            for (MutablePacket packet : sorted) {
                if (!"checkpackagesize".equals(packet.identity.modId)) {
                    report.packets.add(packet.snapshot());
                }
            }
            return report;
        }
    }

    static final class MutablePacket {
        final PacketIdentity identity;
        final MutableEndpoint client = new MutableEndpoint();
        final MutableEndpoint server = new MutableEndpoint();
        final AtomicReference<String> firstCallSite = new AtomicReference<>();

        MutablePacket(PacketIdentity identity) {
            this.identity = identity;
        }

        MutableEndpoint endpoint(String name) {
            return "SERVER".equals(name) ? server : client;
        }

        TrafficReport.PacketRow snapshot() {
            TrafficReport.PacketRow row = new TrafficReport.PacketRow();
            row.direction = identity.direction;
            row.modId = identity.modId;
            row.channel = identity.channel;
            row.packetClass = identity.packetClass;
            row.handlerClass = identity.handlerClass;
            row.firstCallSite = firstCallSite.get();
            row.client = client.snapshot();
            row.server = server.snapshot();
            return row;
        }
    }

    static final class MutableEndpoint {
        final LongAdder sentCount = new LongAdder();
        final LongAdder receivedCount = new LongAdder();
        final LongAdder encodedBytes = new LongAdder();
        final LongAdder receivedBytes = new LongAdder();
        final LongAdder transferredBytes = new LongAdder();
        final LongAdder encodeNanos = new LongAdder();
        final LongAdder decodeNanos = new LongAdder();
        final LongAdder mainThreadTasks = new LongAdder();
        final LongAdder mainThreadNanos = new LongAdder();
        final LongAdder queueWaitNanos = new LongAdder();
        final AtomicLong maxQueueWaitNanos = new AtomicLong();

        TrafficReport.EndpointMetrics snapshot() {
            TrafficReport.EndpointMetrics metrics = new TrafficReport.EndpointMetrics();
            metrics.sentCount = sentCount.sum();
            metrics.receivedCount = receivedCount.sum();
            metrics.encodedBytes = encodedBytes.sum();
            metrics.receivedBytes = receivedBytes.sum();
            metrics.transferredBytes = transferredBytes.sum();
            metrics.encodeNanos = encodeNanos.sum();
            metrics.decodeNanos = decodeNanos.sum();
            metrics.mainThreadTasks = mainThreadTasks.sum();
            metrics.mainThreadNanos = mainThreadNanos.sum();
            metrics.queueWaitNanos = queueWaitNanos.sum();
            metrics.maxQueueWaitNanos = maxQueueWaitNanos.get();
            return metrics;
        }
    }

    static final class PendingPacket {
        final PacketIdentity identity;
        final String endpoint;

        PendingPacket(PacketIdentity identity, String endpoint) {
            this.identity = identity;
            this.endpoint = endpoint;
        }
    }

    static final class CallSite {
        final String text;
        final String modId;

        CallSite(String text, String modId) {
            this.text = text;
            this.modId = modId;
        }
    }
}
