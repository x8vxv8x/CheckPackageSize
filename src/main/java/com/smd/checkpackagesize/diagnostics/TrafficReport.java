package com.smd.checkpackagesize.diagnostics;

import java.util.ArrayList;
import java.util.List;

public class TrafficReport {
    public long sessionId;
    public long startedAtMillis;
    public long durationMillis;
    public boolean localTheoretical;
    public long droppedMeasurements;
    public String reportDirectory;
    public List<PacketRow> packets = new ArrayList<>();

    public static class PacketRow {
        public String direction;
        public String modId;
        public String channel;
        public String packetClass;
        public String handlerClass;
        public String firstCallSite;
        public EndpointMetrics client = new EndpointMetrics();
        public EndpointMetrics server = new EndpointMetrics();
    }

    public static class EndpointMetrics {
        public long sentCount;
        public long receivedCount;
        public long encodedBytes;
        public long receivedBytes;
        public long transferredBytes;
        public long encodeNanos;
        public long decodeNanos;
        public long mainThreadTasks;
        public long mainThreadNanos;
        public long queueWaitNanos;
        public long maxQueueWaitNanos;

        public void add(EndpointMetrics other) {
            sentCount += other.sentCount;
            receivedCount += other.receivedCount;
            encodedBytes += other.encodedBytes;
            receivedBytes += other.receivedBytes;
            transferredBytes += other.transferredBytes;
            encodeNanos += other.encodeNanos;
            decodeNanos += other.decodeNanos;
            mainThreadTasks += other.mainThreadTasks;
            mainThreadNanos += other.mainThreadNanos;
            queueWaitNanos += other.queueWaitNanos;
            maxQueueWaitNanos = Math.max(maxQueueWaitNanos, other.maxQueueWaitNanos);
        }
    }
}
