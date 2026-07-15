package com.smd.checkpackagesize.diagnostics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ReportWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ReportWriter() {
    }

    public static void write(TrafficReport report, File gameDirectory) throws Exception {
        File root = new File(new File(gameDirectory, "logs"), "checkpackagesize");
        File directory = new File(root, new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)
                .format(new Date(report.startedAtMillis)) + "_" + report.sessionId);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create report directory " + directory);
        }
        report.reportDirectory = directory.getAbsolutePath();
        writeUtf8(new File(directory, "report.json"), GSON.toJson(report));
        writeUtf8(new File(directory, "report.html"), html(report));
    }

    private static String html(TrafficReport report) {
        List<ModSummary> mods = summarize(report);
        StringBuilder builder = new StringBuilder(16384);
        builder.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<title>CheckPackageSize 报告</title><style>")
                .append("body{font:14px sans-serif;margin:28px;color:#222;background:#f6f7f9}h1,h2{margin-bottom:8px}")
                .append(".card{background:white;border:1px solid #ddd;border-radius:8px;padding:16px;margin:14px 0}")
                .append("table{width:100%;border-collapse:collapse;background:white}th,td{padding:8px;border-bottom:1px solid #ddd;text-align:left}")
                .append("th{background:#eef1f4}code{font-size:12px}details{margin:10px 0}.warn{color:#a14d00}")
                .append("</style></head><body><h1>CheckPackageSize 网络诊断报告</h1><div class=\"card\">")
                .append("采集时长：").append(formatDuration(report.durationMillis)).append("<br>")
                .append(report.localTheoretical ? "环境：单机/本地连接，传输大小为远程压缩估算"
                        : hasServerData(report) ? "环境：客户端与服务端联合报告，传输大小来自实际压缩阶段"
                        : "环境：仅客户端报告，未取得服务端处理数据")
                .append("<br>丢弃或失败的测量：").append(report.droppedMeasurements).append("</div>");

        if (!mods.isEmpty()) {
            ModSummary traffic = CollectionsUtil.max(mods, Comparator.comparingLong(value -> value.trafficBytes));
            ModSummary main = CollectionsUtil.max(mods, Comparator.comparingLong(value -> value.mainNanos));
            builder.append("<div class=\"card\"><h2>直接结论</h2>")
                    .append("流量最高：<b>").append(escape(traffic.modId)).append("</b>，")
                    .append(formatBytes(traffic.trafficBytes)).append("。<br>")
                    .append("主线程处理最高：<b>").append(escape(main.modId)).append("</b>，")
                    .append(formatNanos(main.mainNanos)).append("。")
                    .append("<p class=\"warn\">若多数 Mod 的队列等待同时很高，通常表示主线程整体繁忙，不应直接归因于单个网络包。</p></div>");
        }

        builder.append("<h2>Mod 汇总</h2><table><tr><th>Mod</th><th>C2S</th><th>S2C</th><th>编解码</th><th>主线程处理</th><th>最大队列等待</th><th>主要包</th></tr>");
        mods.sort(Comparator.comparingLong((ModSummary value) -> value.trafficBytes).reversed());
        for (ModSummary mod : mods) {
            builder.append("<tr><td>").append(escape(mod.modId)).append("</td><td>").append(formatBytes(mod.c2sBytes))
                    .append("</td><td>").append(formatBytes(mod.s2cBytes)).append("</td><td>")
                    .append(formatNanos(mod.codecNanos)).append("</td><td>").append(formatNanos(mod.mainNanos))
                    .append("</td><td>").append(formatNanos(mod.maxQueueNanos)).append("</td><td><code>")
                    .append(escape(shortName(mod.topPacket))).append("</code></td></tr>");
        }
        builder.append("</table><h2>具体包</h2>");

        ArrayList<TrafficReport.PacketRow> packets = new ArrayList<>(report.packets);
        packets.sort(Comparator.comparingLong(ReportWriter::trafficBytes).reversed());
        for (TrafficReport.PacketRow row : packets) {
            long count = Math.max(row.client.sentCount + row.server.sentCount,
                    row.client.receivedCount + row.server.receivedCount);
            long codec = row.client.encodeNanos + row.client.decodeNanos + row.server.encodeNanos + row.server.decodeNanos;
            long main = row.client.mainThreadNanos + row.server.mainThreadNanos;
            long wait = Math.max(row.client.maxQueueWaitNanos, row.server.maxQueueWaitNanos);
            builder.append("<details><summary><b>").append(escape(row.modId)).append("</b> · ")
                    .append(escape(row.direction)).append(" · <code>").append(escape(row.packetClass)).append("</code> · ")
                    .append(formatBytes(trafficBytes(row))).append("</summary><div class=\"card\">")
                    .append("频道：<code>").append(escape(row.channel)).append("</code><br>")
                    .append("处理器：<code>").append(escape(row.handlerClass)).append("</code><br>")
                    .append("发送次数：").append(count).append("<br>")
                    .append("传输大小：").append(formatBytes(trafficBytes(row))).append("<br>")
                    .append("编解码耗时：").append(formatNanos(codec)).append("<br>")
                    .append("主线程处理：").append(formatNanos(main)).append("<br>")
                    .append("最大队列等待：").append(formatNanos(wait)).append("<br>")
                    .append(endpointDetail("客户端", row.client))
                    .append(endpointDetail("服务端", row.server))
                    .append("首次发送位置：<code>").append(escape(row.firstCallSite == null ? "未捕获" : row.firstCallSite)).append("</code>")
                    .append("</div></details>");
        }
        return builder.append("</body></html>").toString();
    }

    public static List<ModSummary> summarize(TrafficReport report) {
        Map<String, ModSummary> result = new LinkedHashMap<>();
        Map<String, Long> topPacketBytes = new LinkedHashMap<>();
        for (TrafficReport.PacketRow row : report.packets) {
            ModSummary summary = result.computeIfAbsent(row.modId, ModSummary::new);
            long bytes = trafficBytes(row);
            summary.trafficBytes += bytes;
            if ("C2S".equals(row.direction)) summary.c2sBytes += bytes;
            if ("S2C".equals(row.direction)) summary.s2cBytes += bytes;
            summary.codecNanos += row.client.encodeNanos + row.client.decodeNanos + row.server.encodeNanos + row.server.decodeNanos;
            summary.mainNanos += row.client.mainThreadNanos + row.server.mainThreadNanos;
            summary.maxQueueNanos = Math.max(summary.maxQueueNanos,
                    Math.max(row.client.maxQueueWaitNanos, row.server.maxQueueWaitNanos));
            String key = row.modId + '\0' + row.packetClass;
            long packetBytes = topPacketBytes.getOrDefault(key, 0L) + bytes;
            topPacketBytes.put(key, packetBytes);
            if (packetBytes >= summary.topPacketBytes) {
                summary.topPacketBytes = packetBytes;
                summary.topPacket = row.packetClass;
            }
        }
        return new ArrayList<>(result.values());
    }

    public static long trafficBytes(TrafficReport.PacketRow row) {
        if ("C2S".equals(row.direction)) {
            if (row.client.transferredBytes > 0) return row.client.transferredBytes;
            return row.server.transferredBytes;
        }
        if (row.server.transferredBytes > 0) return row.server.transferredBytes;
        return row.client.transferredBytes;
    }

    public static boolean hasServerData(TrafficReport report) {
        for (TrafficReport.PacketRow row : report.packets) {
            TrafficReport.EndpointMetrics server = row.server;
            if (server.sentCount > 0 || server.receivedCount > 0 || server.mainThreadTasks > 0
                    || server.encodeNanos > 0 || server.decodeNanos > 0) {
                return true;
            }
        }
        return false;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0));
    }

    public static String formatNanos(long nanos) {
        if (nanos < 1_000L) return nanos + " ns";
        if (nanos < 1_000_000L) return String.format(Locale.ROOT, "%.1f µs", nanos / 1_000.0);
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
    }

    private static String endpointDetail(String name, TrafficReport.EndpointMetrics metrics) {
        long averageWait = metrics.mainThreadTasks == 0 ? 0L : metrics.queueWaitNanos / metrics.mainThreadTasks;
        return "<b>" + name + "</b>：编码 " + formatNanos(metrics.encodeNanos)
                + "，解码 " + formatNanos(metrics.decodeNanos)
                + "，主线程 " + formatNanos(metrics.mainThreadNanos)
                + "，队列平均/最大 " + formatNanos(averageWait) + " / " + formatNanos(metrics.maxQueueWaitNanos)
                + "<br>";
    }

    private static String formatDuration(long millis) {
        return String.format(Locale.ROOT, "%.2f 秒", millis / 1000.0);
    }

    private static String shortName(String name) {
        if (name == null) return "-";
        int separator = name.lastIndexOf('.');
        return separator < 0 ? name : name.substring(separator + 1);
    }

    private static String escape(String value) {
        if (value == null) return "-";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void writeUtf8(File file, String content) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(content);
        }
    }

    public static final class ModSummary {
        public final String modId;
        public long c2sBytes;
        public long s2cBytes;
        public long trafficBytes;
        public long codecNanos;
        public long mainNanos;
        public long maxQueueNanos;
        public String topPacket = "-";
        long topPacketBytes;

        private ModSummary(String modId) {
            this.modId = modId;
        }
    }

    private static final class CollectionsUtil {
        static <T> T max(List<T> values, Comparator<T> comparator) {
            T result = values.get(0);
            for (int index = 1; index < values.size(); index++) {
                if (comparator.compare(values.get(index), result) > 0) result = values.get(index);
            }
            return result;
        }
    }
}
