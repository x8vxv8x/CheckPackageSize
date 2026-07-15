package com.smd.checkpackagesize.diagnostics;

import java.util.ArrayList;
import java.util.List;

public class TrafficReport {
    public long sessionId;
    public String mode;
    public long startedAtMillis;
    public long durationMillis;
    public boolean localTheoretical;
    public boolean capturing;
    public long droppedMeasurements;
    public int pendingLocalMeasurements;
    public String reportDirectory;
    public List<PacketRow> packets = new ArrayList<>();
    public List<TimeBucket> timeline = new ArrayList<>();

    public static class TimeBucket {
        public int second;
        public long c2sBytes;
        public long s2cBytes;
        public long c2sPackets;
        public long s2cPackets;
    }

    public static class PacketRow {
        public String direction;
        public String modId;
        public String channel;
        public String packetClass;
        public String handlerClass;
        public int discriminator = -1;
        public EndpointMetrics client = new EndpointMetrics();
        public EndpointMetrics server = new EndpointMetrics();
    }

    public static class EndpointMetrics {
        public long sentCount;
        public long receivedCount;
        public long encodedBytes;
        public long receivedBytes;
        public long transferredBytes;
        public void add(EndpointMetrics other) {
            sentCount += other.sentCount;
            receivedCount += other.receivedCount;
            encodedBytes += other.encodedBytes;
            receivedBytes += other.receivedBytes;
            transferredBytes += other.transferredBytes;
        }
    }
}
