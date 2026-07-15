package com.smd.checkpackagesize.diagnostics;

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

    private ReportWriter() {
    }

    public static File createSessionDirectory(File gameDirectory, long startedAtMillis, long sessionId) throws Exception {
        File root = new File(new File(gameDirectory, "logs"), "checkpackagesize");
        File directory = new File(root, new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)
                .format(new Date(startedAtMillis)) + "_" + sessionId);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create report directory " + directory);
        }
        return directory;
    }

    public static void writeHtml(TrafficReport report, File directory) throws Exception {
        report.reportDirectory = directory.getAbsolutePath();
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
                .append("模式：").append(escape(report.mode)).append("<br>")
                .append("采集时长：").append(formatDuration(report.durationMillis)).append("<br>")
                .append(report.localTheoretical ? "环境：单机联合采集，传输大小为远程压缩估算"
                        : "环境：单端采集，传输大小来自当前端实际压缩阶段")
                .append("<br>")
                .append("丢弃或失败的测量：").append(report.droppedMeasurements).append("</div>");

        if (!mods.isEmpty()) {
            ModSummary traffic = max(mods, Comparator.comparingLong(value -> value.trafficBytes));
            ModSummary frequent = max(mods, Comparator.comparingLong(value -> value.packetCount));
            builder.append("<div class=\"card\"><h2>直接结论</h2>")
                    .append("流量最高：<b>").append(escape(traffic.modId)).append("</b>，")
                    .append(formatBytes(traffic.trafficBytes)).append("。<br>")
                    .append("包数量最高：<b>").append(escape(frequent.modId)).append("</b>，")
                    .append(frequent.packetCount).append(" 个。")
                    .append("<p>本报告只统计 Minecraft 应用层流量，不测量编解码、发送队列或主线程处理时间。</p></div>");
        }

        builder.append("<h2>Mod 汇总</h2><table><tr><th>Mod</th><th>C2S</th><th>S2C</th><th>包数</th><th>编码大小</th><th>压缩率</th><th>主要包</th></tr>");
        mods.sort(Comparator.comparingLong((ModSummary value) -> value.trafficBytes).reversed());
        for (ModSummary mod : mods) {
            builder.append("<tr><td>").append(escape(mod.modId)).append("</td><td>").append(formatBytes(mod.c2sBytes))
                    .append("</td><td>").append(formatBytes(mod.s2cBytes)).append("</td><td>")
                    .append(mod.packetCount).append("</td><td>").append(formatBytes(mod.encodedBytes))
                    .append("</td><td>").append(formatRatio(mod.encodedBytes, mod.trafficBytes)).append("</td><td><code>")
                    .append(escape(shortName(mod.topPacket))).append("</code></td></tr>");
        }
        builder.append("</table><h2>具体包</h2>");

        ArrayList<TrafficReport.PacketRow> packets = new ArrayList<>(report.packets);
        packets.sort(Comparator.comparingLong(ReportWriter::trafficBytes).reversed());
        for (TrafficReport.PacketRow row : packets) {
            long count = packetCount(row);
            long encoded = encodedBytes(row);
            long transferred = trafficBytes(row);
            builder.append("<details><summary><b>").append(escape(row.modId)).append("</b> · ")
                    .append(escape(row.direction)).append(" · <code>").append(escape(row.packetClass)).append("</code> · ")
                    .append(formatBytes(trafficBytes(row))).append("</summary><div class=\"card\">")
                    .append("频道：<code>").append(escape(row.channel)).append("</code><br>")
                    .append("处理器：<code>").append(escape(row.handlerClass)).append("</code><br>")
                    .append("发送/接收次数：").append(count).append("<br>")
                    .append("编码大小：").append(formatBytes(encoded)).append("<br>")
                    .append("传输大小：").append(formatBytes(transferred)).append("<br>")
                    .append("平均传输大小：").append(formatBytes(count == 0 ? 0 : transferred / count)).append("<br>")
                    .append("压缩率：").append(formatRatio(encoded, transferred)).append("<br>")
                    .append(endpointDetail("客户端", row.client))
                    .append(endpointDetail("服务端", row.server))
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
            long count = packetCount(row);
            summary.trafficBytes += bytes;
            summary.encodedBytes += encodedBytes(row);
            summary.packetCount += count;
            if ("C2S".equals(row.direction)) summary.c2sBytes += bytes;
            if ("S2C".equals(row.direction)) summary.s2cBytes += bytes;
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
            return row.client.transferredBytes > 0 ? row.client.transferredBytes : row.server.transferredBytes;
        }
        return row.server.transferredBytes > 0 ? row.server.transferredBytes : row.client.transferredBytes;
    }

    public static long encodedBytes(TrafficReport.PacketRow row) {
        if ("C2S".equals(row.direction)) {
            return row.client.encodedBytes > 0 ? row.client.encodedBytes : row.server.receivedBytes;
        }
        return row.server.encodedBytes > 0 ? row.server.encodedBytes : row.client.receivedBytes;
    }

    public static long packetCount(TrafficReport.PacketRow row) {
        return Math.max(row.client.sentCount + row.server.sentCount,
                row.client.receivedCount + row.server.receivedCount);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0));
    }

    public static String formatRatio(long encoded, long transferred) {
        if (encoded <= 0L) return "-";
        return String.format(Locale.ROOT, "%.1f%%", transferred * 100.0 / encoded);
    }

    private static String endpointDetail(String name, TrafficReport.EndpointMetrics metrics) {
        return "<b>" + name + "</b>：发送/接收 " + metrics.sentCount + " / " + metrics.receivedCount
                + "，编码/接收 " + formatBytes(metrics.encodedBytes) + " / " + formatBytes(metrics.receivedBytes)
                + "，传输 " + formatBytes(metrics.transferredBytes) + "<br>";
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

    private static <T> T max(List<T> values, Comparator<T> comparator) {
        T result = values.get(0);
        for (int index = 1; index < values.size(); index++) {
            if (comparator.compare(values.get(index), result) > 0) result = values.get(index);
        }
        return result;
    }

    public static final class ModSummary {
        public final String modId;
        public long c2sBytes;
        public long s2cBytes;
        public long trafficBytes;
        public long encodedBytes;
        public long packetCount;
        public String topPacket = "-";
        long topPacketBytes;

        private ModSummary(String modId) {
            this.modId = modId;
        }
    }
}
