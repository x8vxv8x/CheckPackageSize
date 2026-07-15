package com.smd.checkpackagesize.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TrafficReportMerger {

    private TrafficReportMerger() {
    }

    public static TrafficReport merge(TrafficReport local, TrafficReport remote) {
        if (local == null) return remote;
        if (remote == null) return local;
        TrafficReport result = new TrafficReport();
        result.sessionId = local.sessionId;
        result.startedAtMillis = Math.min(local.startedAtMillis, remote.startedAtMillis);
        result.durationMillis = Math.max(local.durationMillis, remote.durationMillis);
        result.localTheoretical = local.localTheoretical && remote.localTheoretical;
        result.droppedMeasurements = local.droppedMeasurements + remote.droppedMeasurements;
        Map<String, TrafficReport.PacketRow> rows = new LinkedHashMap<>();
        append(rows, local);
        append(rows, remote);
        result.packets.addAll(rows.values());
        return result;
    }

    private static void append(Map<String, TrafficReport.PacketRow> rows, TrafficReport source) {
        for (TrafficReport.PacketRow incoming : source.packets) {
            String key = incoming.direction + '\0' + incoming.modId + '\0' + incoming.channel + '\0'
                    + incoming.packetClass + '\0' + incoming.handlerClass;
            TrafficReport.PacketRow row = rows.get(key);
            if (row == null) {
                row = new TrafficReport.PacketRow();
                row.direction = incoming.direction;
                row.modId = incoming.modId;
                row.channel = incoming.channel;
                row.packetClass = incoming.packetClass;
                row.handlerClass = incoming.handlerClass;
                rows.put(key, row);
            }
            if (row.firstCallSite == null) row.firstCallSite = incoming.firstCallSite;
            row.client.add(incoming.client);
            row.server.add(incoming.server);
        }
    }
}
