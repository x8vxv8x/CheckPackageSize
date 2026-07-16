package com.smd.checkpackagesize.client;

import com.smd.checkpackagesize.diagnostics.DiagnosticsManager;
import com.smd.checkpackagesize.diagnostics.Endpoint;
import com.smd.checkpackagesize.diagnostics.ReportWriter;
import com.smd.checkpackagesize.diagnostics.TrafficReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@SideOnly(Side.CLIENT)
public final class DiagnosticsScreen extends GuiScreen {

    private static final int BG = 0xFF11151C;
    private static final int TOP = 0xFF171D27;
    private static final int PANEL = 0xFF1B222D;
    private static final int PANEL_ALT = 0xFF202936;
    private static final int BORDER = 0xFF303B4B;
    private static final int TEXT = 0xFFE7ECF3;
    private static final int MUTED = 0xFF8D99AA;
    private static final int ACCENT = 0xFF38BDF8;
    private static final int C2S = 0xFFF59E42;
    private static final int S2C = 0xFF4CA6FF;
    private static final int GOOD = 0xFF4ADE80;
    private static final int WARN = 0xFFFBBF24;
    private static final int DANGER = 0xFFF87171;

    private final List<HitBox> hitBoxes = new ArrayList<>();
    private int durationSeconds = 30;
    private int traceDepth = DiagnosticsManager.DEFAULT_TRACE_DEPTH;
    private Page page = Page.OVERVIEW;
    private Sort sort = Sort.TRAFFIC;
    private int scrollOffset;
    private boolean showSetup;
    private boolean includeC2s = true;
    private boolean includeS2c = true;
    private boolean livePaused;
    private String selectedMod;
    private GuiTextField searchField;
    private GuiTextField durationField;
    private GuiTextField traceDepthField;
    private String searchText = "";
    private String statusMessage = "";
    private TrafficReport liveSnapshot;
    private long nextLiveSnapshotMillis;
    private TrafficReport cachedReport;
    private Totals cachedTotals;
    private List<ReportWriter.ModSummary> cachedMods = List.of();
    private List<ReportWriter.TracedPacketSummary> cachedTracedPackets = List.of();
    private List<ReportWriter.ModSummary> cachedFilteredMods = List.of();
    private List<TrafficReport.PacketRow> cachedFilteredPackets = List.of();
    private boolean filtersDirty = true;

    @Override
    public void initGui() {
        buttonList.clear();
        showSetup = showSetup || DiagnosticsManager.getLastReport() == null;
        searchField = new GuiTextField(1, fontRenderer, 16, 72, Math.max(120, width - 430), 18);
        searchField.setMaxStringLength(96);
        searchField.setText(searchText);
        searchField.setEnableBackgroundDrawing(true);
        int panelWidth = Math.min(580, width - 32);
        int left = (width - panelWidth) / 2;
        int top = 52;
        durationField = numericField(2, left + 20, top + 165, 108, Integer.toString(durationSeconds));
        traceDepthField = numericField(3, left + 20, top + 215, 108, Integer.toString(traceDepth));
    }

    @Override
    public void updateScreen() {
        if (searchField != null) searchField.updateCursorCounter();
        if (durationField != null) durationField.updateCursorCounter();
        if (traceDepthField != null) traceDepthField.updateCursorCounter();
        if (DiagnosticsManager.isCapturing() && !livePaused && System.currentTimeMillis() >= nextLiveSnapshotMillis) {
            liveSnapshot = DiagnosticsManager.getLiveReport();
            nextLiveSnapshotMillis = System.currentTimeMillis() + 500L;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, BG);
        hitBoxes.clear();
        drawTopBar(mouseX, mouseY);

        if (DiagnosticsManager.isCapturing()) {
            showSetup = false;
            drawLive(mouseX, mouseY);
        } else if (DiagnosticsManager.isFinalizing()) {
            drawFinalizing();
        } else if (showSetup || DiagnosticsManager.getLastReport() == null) {
            drawSetup(mouseX, mouseY);
        } else {
            drawResult(DiagnosticsManager.getLastReport(), mouseX, mouseY);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawTopBar(int mouseX, int mouseY) {
        drawRect(0, 0, width, 34, TOP);
        drawRect(0, 33, width, 34, BORDER);
        drawString(fontRenderer, "CheckPackageSize", 14, 12, TEXT);
        String state = DiagnosticsManager.isCapturing() ? "LIVE" : DiagnosticsManager.isFinalizing() ? "生成报告" : "就绪";
        int stateColor = DiagnosticsManager.isCapturing() ? GOOD : DiagnosticsManager.isFinalizing() ? WARN : MUTED;
        drawPill(126, 8, state, stateColor);

        if (!DiagnosticsManager.isCapturing() && !DiagnosticsManager.isFinalizing()) {
            drawButton(width - 182, 7, 82, 20, "新建采集", Action.SETUP, null, mouseX, mouseY,
                    showSetup, ACCENT);
        }
        drawButton(width - 92, 7, 78, 20, "关闭", Action.CLOSE, null, mouseX, mouseY, false, MUTED);
    }

    private void drawSetup(int mouseX, int mouseY) {
        int panelWidth = Math.min(580, width - 32);
        int left = (width - panelWidth) / 2;
        int top = 52;
        int bottom = Math.min(height - 18, top + 350);
        drawPanel(left, top, panelWidth, bottom - top);

        drawString(fontRenderer, "开始一次流量采集", left + 20, top + 18, TEXT);
        drawString(fontRenderer, "按包定义和本端发包调用来源定位网络流量。", left + 20, top + 38, MUTED);

        boolean local = Minecraft.getMinecraft().isSingleplayer();
        drawSectionLabel(left + 20, top + 68, "采集环境");
        drawRect(left + 20, top + 84, left + panelWidth - 20, top + 127, PANEL_ALT);
        drawString(fontRenderer, local ? "单机客户端 + 整合服务端" : "远程连接 · 当前客户端", left + 32, top + 96, TEXT);
        drawString(fontRenderer, local ? "理论 Wire · 精确重新序列化与压缩" : "实际 Wire · 当前连接的压缩帧",
                left + 32, top + 112, local ? WARN : GOOD);

        drawSectionLabel(left + 20, top + 148, "持续时间");
        durationField.drawTextBox();
        drawString(fontRenderer, "秒（1–300）", left + 138, top + 172, MUTED);

        drawSectionLabel(left + 20, top + 198, "本端发包调用栈深度");
        traceDepthField.drawTextBox();
        drawString(fontRenderer, "层（0 关闭，最大 64）", left + 138, top + 222, MUTED);

        int warningY = top + 252;
        if (local) {
            drawString(fontRenderer, "单机可追溯客户端与整合服务端；深栈采集会增加开销。",
                    left + 20, warningY, WARN);
        } else {
            drawString(fontRenderer, "远程客户端只追溯本端 C2S；S2C 的服务端调用栈不可见。", left + 20, warningY, MUTED);
        }

        drawButton(left + 20, top + 282, panelWidth - 40, 34, "开始采集", Action.START, null,
                mouseX, mouseY, false, ACCENT);
        if (!statusMessage.isEmpty()) drawCenteredString(fontRenderer, statusMessage, width / 2, bottom - 18, DANGER);
    }

    private void drawLive(int mouseX, int mouseY) {
        TrafficReport report = liveSnapshot;
        if (report == null) report = DiagnosticsManager.getLiveReport();
        if (report == null) return;

        long remaining = DiagnosticsManager.getRemainingMillis();
        long total = DiagnosticsManager.getDurationMillis();
        long elapsed = Math.max(0L, total - remaining);
        drawString(fontRenderer, String.format(Locale.ROOT, "采集中  %.1f / %.0f 秒", elapsed / 1000.0, total / 1000.0),
                16, 45, TEXT);
        drawPill(145, 40, report.localTheoretical ? "THEORETICAL" : "ACTUAL",
                report.localTheoretical ? WARN : GOOD);
        if (livePaused) drawPill(246, 40, "显示已暂停", WARN);
        drawButton(width - 220, 40, 98, 22, livePaused ? "继续刷新" : "暂停显示", Action.PAUSE, null,
                mouseX, mouseY, livePaused, WARN);
        drawButton(width - 114, 40, 98, 22, "停止采集", Action.STOP, null, mouseX, mouseY, false, DANGER);

        Totals totals = totals(report);
        TrafficReport.TimeBucket latest = report.timeline.isEmpty() ? null
                : report.timeline.get(report.timeline.size() > 1 ? report.timeline.size() - 2 : 0);
        long currentBytes = latest == null ? 0L : latest.c2sBytes + latest.s2cBytes;
        long currentPackets = latest == null ? 0L : latest.c2sPackets + latest.s2cPackets;
        int cardsTop = 72;
        drawKpiCards(totals, currentBytes, currentPackets, report, cardsTop);
        drawTimeline(report, 16, cardsTop + 62, width - 32, 112);
        drawTopMods(report, 16, cardsTop + 184, width - 32, Math.max(70, height - cardsTop - 202));
    }

    private void drawFinalizing() {
        int panelWidth = Math.min(480, width - 32);
        int left = (width - panelWidth) / 2;
        int top = height / 2 - 52;
        drawPanel(left, top, panelWidth, 104);
        drawCenteredString(fontRenderer, "正在生成 HTML 报告", width / 2, top + 30, WARN);
        drawCenteredString(fontRenderer, "聚合结果完成后会自动进入分析页面", width / 2, top + 54, MUTED);
    }

    private void drawResult(TrafficReport report, int mouseX, int mouseY) {
        drawTabs(mouseX, mouseY);
        if (page == Page.OVERVIEW) {
            drawOverview(report, mouseX, mouseY);
        } else {
            drawExplorer(report, mouseX, mouseY);
        }
    }

    private void drawTabs(int mouseX, int mouseY) {
        drawButton(16, 42, 78, 22, "概览", Action.PAGE, Page.OVERVIEW, mouseX, mouseY,
                page == Page.OVERVIEW, ACCENT);
        drawButton(100, 42, 78, 22, "Mod", Action.PAGE, Page.MODS, mouseX, mouseY,
                page == Page.MODS, ACCENT);
        drawButton(184, 42, 78, 22, "包类型", Action.PAGE, Page.PACKETS, mouseX, mouseY,
                page == Page.PACKETS, ACCENT);
        drawButton(width - 226, 42, 100, 22, "打开报告", Action.OPEN_REPORT, null, mouseX, mouseY, false, MUTED);
        drawButton(width - 118, 42, 102, 22, "打开目录", Action.OPEN_DIRECTORY, null, mouseX, mouseY, false, MUTED);
    }

    private void drawOverview(TrafficReport report, int mouseX, int mouseY) {
        Totals totals = totals(report);
        int top = 76;
        drawKpiCards(totals, peakBytes(report), peakPackets(report), report, top);
        drawTimeline(report, 16, top + 62, width - 32, 112);
        int lowerTop = top + 184;
        int lowerHeight = Math.max(80, height - lowerTop - 16);
        drawTopMods(report, 16, lowerTop, width - 32, lowerHeight);
    }

    private void drawKpiCards(Totals totals, long rateBytes, long ratePackets, TrafficReport report, int top) {
        int gap = 8;
        int cardWidth = (width - 32 - gap * 4) / 5;
        drawKpi(16, top, cardWidth, "当前/峰值 Wire", ReportWriter.formatRate(rateBytes), ACCENT);
        drawKpi(16 + (cardWidth + gap), top, cardWidth, "Packets/s", Long.toString(ratePackets), TEXT);
        drawKpi(16 + (cardWidth + gap) * 2, top, cardWidth, "C2S", ReportWriter.formatBytes(totals.c2sBytes), C2S);
        drawKpi(16 + (cardWidth + gap) * 3, top, cardWidth, "S2C", ReportWriter.formatBytes(totals.s2cBytes), S2C);
        int healthColor = report.droppedMeasurements == 0 ? GOOD : DANGER;
        drawKpi(16 + (cardWidth + gap) * 4, top, cardWidth, "丢弃 / 待处理",
                report.droppedMeasurements + " / " + report.pendingLocalMeasurements, healthColor);
    }

    private void drawKpi(int x, int y, int w, String label, String value, int color) {
        drawPanel(x, y, w, 52);
        drawString(fontRenderer, label, x + 9, y + 9, MUTED);
        drawString(fontRenderer, trim(value, w - 18), x + 9, y + 29, color);
    }

    private void drawTimeline(TrafficReport report, int x, int y, int w, int h) {
        drawPanel(x, y, w, h);
        drawString(fontRenderer, "最近 60 秒 · Minecraft Wire", x + 10, y + 9, TEXT);
        drawString(fontRenderer, "C2S", x + w - 92, y + 9, C2S);
        drawString(fontRenderer, "S2C", x + w - 50, y + 9, S2C);
        int graphX = x + 10;
        int graphY = y + 28;
        int graphW = w - 20;
        int graphH = h - 40;
        drawRect(graphX, graphY, graphX + graphW, graphY + graphH, 0xFF141A22);
        long maximum = 1L;
        for (TrafficReport.TimeBucket bucket : report.timeline) maximum = Math.max(maximum, bucket.c2sBytes + bucket.s2cBytes);
        int slots = 60;
        int offset = Math.max(0, slots - report.timeline.size());
        for (int index = 0; index < report.timeline.size(); index++) {
            TrafficReport.TimeBucket bucket = report.timeline.get(index);
            int left = graphX + (offset + index) * graphW / slots;
            int right = Math.max(left + 1, graphX + (offset + index + 1) * graphW / slots - 1);
            int c2sHeight = (int) (bucket.c2sBytes * graphH / maximum);
            int s2cHeight = (int) (bucket.s2cBytes * graphH / maximum);
            int bottom = graphY + graphH;
            if (c2sHeight > 0) drawRect(left, bottom - c2sHeight, right, bottom, C2S);
            if (s2cHeight > 0) drawRect(left, bottom - c2sHeight - s2cHeight, right, bottom - c2sHeight, S2C);
        }
        drawString(fontRenderer, ReportWriter.formatRate(maximum), graphX + 3, graphY + 3, MUTED);
        drawRight("60s", graphX + graphW - 2, graphY + graphH - 10, MUTED);
    }

    private void drawTopMods(TrafficReport report, int x, int y, int w, int h) {
        drawPanel(x, y, w, h);
        drawString(fontRenderer, "主要流量来源", x + 10, y + 9, TEXT);
        ensureCache(report);
        List<ReportWriter.ModSummary> mods = cachedMods;
        long total = 0L;
        for (ReportWriter.ModSummary mod : mods) total += mod.trafficBytes;
        int rowY = y + 27;
        int maxRows = Math.max(1, (h - 34) / 17);
        for (int index = 0; index < Math.min(maxRows, mods.size()); index++) {
            ReportWriter.ModSummary mod = mods.get(index);
            int color = index == 0 ? ACCENT : TEXT;
            drawString(fontRenderer, trim(mod.modId, Math.max(80, w / 2)), x + 10, rowY, color);
            drawRight(ReportWriter.formatBytes(mod.trafficBytes), x + w - 112, rowY, TEXT);
            drawRight(percent(mod.trafficBytes, total), x + w - 14, rowY, MUTED);
            rowY += 17;
        }
        if (mods.isEmpty()) drawString(fontRenderer, "尚未观察到流量", x + 10, rowY, MUTED);
    }

    private void drawExplorer(TrafficReport report, int mouseX, int mouseY) {
        searchField.drawTextBox();
        if (searchText.isEmpty() && !searchField.isFocused())
            drawString(fontRenderer, "搜索 Mod、频道、类名、Handler 或调用栈…", 21, 78, MUTED);
        drawButton(width - 402, 71, 58, 20, "C2S", Action.TOGGLE_C2S, null, mouseX, mouseY, includeC2s, C2S);
        drawButton(width - 338, 71, 58, 20, "S2C", Action.TOGGLE_S2C, null, mouseX, mouseY, includeS2c, S2C);
        drawButton(width - 274, 71, 72, 20, "按流量", Action.SORT, Sort.TRAFFIC, mouseX, mouseY, sort == Sort.TRAFFIC, ACCENT);
        drawButton(width - 196, 71, 72, 20, "按次数", Action.SORT, Sort.COUNT, mouseX, mouseY, sort == Sort.COUNT, ACCENT);
        drawButton(width - 118, 71, 102, 20, "按名称", Action.SORT, Sort.NAME, mouseX, mouseY, sort == Sort.NAME, ACCENT);

        int detailWidth = selectedMod != null ? Math.clamp(width / 3, 220, 280) : 0;
        int tableX = 16;
        int tableY = 99;
        int tableW = width - 32 - (detailWidth == 0 ? 0 : detailWidth + 8);
        int tableH = height - tableY - 16;
        drawPanel(tableX, tableY, tableW, tableH);
        if (page == Page.MODS) drawModTable(report, tableX, tableY, tableW, tableH, mouseX, mouseY);
        else drawPacketTable(report, tableX, tableY, tableW, tableH, mouseX, mouseY);
        if (detailWidth > 0) drawDetails(report, tableX + tableW + 8, tableY, detailWidth, tableH, mouseX, mouseY);
    }

    private void drawModTable(TrafficReport report, int x, int y, int w, int h, int mouseX, int mouseY) {
        List<ReportWriter.ModSummary> rows = filteredMods(report);
        drawTableHeader(x, y, w, "Mod", "Wire", "包数", "Frame", "占比");
        long allBytes = 0L;
        for (ReportWriter.ModSummary row : rows) allBytes += visibleBytes(row.c2sBytes, row.s2cBytes);
        int visibleRows = Math.max(1, (h - 42) / 18);
        scrollOffset = clamp(scrollOffset, 0, Math.max(0, rows.size() - visibleRows));
        int rowY = y + 25;
        for (int index = scrollOffset; index < Math.min(rows.size(), scrollOffset + visibleRows); index++) {
            ReportWriter.ModSummary row = rows.get(index);
            boolean selected = row.modId.equals(selectedMod);
            if (selected || inside(mouseX, mouseY, x + 2, rowY - 3, w - 4, 17))
                drawRect(x + 2, rowY - 3, x + w - 2, rowY + 13, selected ? 0xFF264256 : PANEL_ALT);
            long visibleBytes = visibleBytes(row.c2sBytes, row.s2cBytes);
            drawString(fontRenderer, trim(row.modId, Math.max(80, w * 42 / 100)), x + 8, rowY, selected ? ACCENT : TEXT);
            drawRight(ReportWriter.formatBytes(visibleBytes), x + w * 58 / 100, rowY, TEXT);
            drawRight(Long.toString(visiblePackets(row)), x + w * 72 / 100, rowY, MUTED);
            drawRight(ReportWriter.formatBytes(visibleEncoded(row)), x + w * 87 / 100, rowY, MUTED);
            drawRight(percent(visibleBytes, allBytes), x + w - 8, rowY, MUTED);
            hitBoxes.add(new HitBox(x + 2, rowY - 3, x + w - 2, rowY + 13, Action.SELECT_MOD, row.modId));
            rowY += 18;
        }
        drawRowCount(x, y, w, h, rows.size());
    }

    private void drawPacketTable(TrafficReport report, int x, int y, int w, int h, int mouseX, int mouseY) {
        List<TrafficReport.PacketRow> rows = filteredPackets(report);
        drawTableHeader(x, y, w, "方向 / 包类型", "Wire", "包数", "路径", "平均");
        int visibleRows = Math.max(1, (h - 42) / 18);
        scrollOffset = clamp(scrollOffset, 0, Math.max(0, rows.size() - visibleRows));
        int rowY = y + 25;
        for (int index = scrollOffset; index < Math.min(rows.size(), scrollOffset + visibleRows); index++) {
            TrafficReport.PacketRow row = rows.get(index);
            if (inside(mouseX, mouseY, x + 2, rowY - 3, w - 4, 17))
                drawRect(x + 2, rowY - 3, x + w - 2, rowY + 13, PANEL_ALT);
            long wire = ReportWriter.trafficBytes(row);
            long count = ReportWriter.packetCount(row);
            ReportWriter.TracedPacketSummary trace = traceFor(row);
            String name = simpleName(row.packetClass);
            drawString(fontRenderer, row.direction, x + 8, rowY, "C2S".equals(row.direction) ? C2S : S2C);
            drawString(fontRenderer, trim(name, Math.max(70, w * 34 / 100)), x + 35, rowY, TEXT);
            drawRight(ReportWriter.formatBytes(wire), x + w * 58 / 100, rowY, TEXT);
            drawRight(Long.toString(count), x + w * 72 / 100, rowY, MUTED);
            drawRight(traceLabel(report, row, trace), x + w * 87 / 100, rowY,
                    trace == null ? MUTED : ACCENT);
            drawRight(ReportWriter.formatBytes(count == 0 ? 0 : wire / count), x + w - 8, rowY, MUTED);
            hitBoxes.add(new HitBox(x + 2, rowY - 3, x + w - 2, rowY + 13, Action.OPEN_PACKET, row));
            rowY += 18;
        }
        drawRowCount(x, y, w, h, rows.size());
    }

    private void drawDetails(TrafficReport report, int x, int y, int w, int h, int mouseX, int mouseY) {
        drawPanel(x, y, w, h);
        drawString(fontRenderer, "检查器", x + 10, y + 10, TEXT);
        drawButton(x + w - 28, y + 5, 20, 18, "×", Action.CLEAR_SELECTION, null, mouseX, mouseY, false, MUTED);
        if (selectedMod == null) return;

        int line = y + 34;
        ensureCache(report);
        ReportWriter.ModSummary selected = null;
        for (ReportWriter.ModSummary mod : cachedMods) if (mod.modId.equals(selectedMod)) selected = mod;
        if (selected == null) return;
        drawWrappedValue(x, w, line, "Mod", selected.modId); line += 38;
        drawDetailLine(x, w, line, "Wire", ReportWriter.formatBytes(selected.trafficBytes), ACCENT); line += 20;
        drawDetailLine(x, w, line, "C2S", ReportWriter.formatBytes(selected.c2sBytes), C2S); line += 20;
        drawDetailLine(x, w, line, "S2C", ReportWriter.formatBytes(selected.s2cBytes), S2C); line += 20;
        drawDetailLine(x, w, line, "包数", Long.toString(selected.packetCount), TEXT); line += 20;
        drawDetailLine(x, w, line, "Frame", ReportWriter.formatBytes(selected.encodedBytes), TEXT); line += 20;
        drawDetailLine(x, w, line, "压缩后占比",
                ReportWriter.formatRatio(selected.encodedBytes, selected.trafficBytes), TEXT); line += 20;
        drawWrappedValue(x, w, line + 8, "主要包", simpleName(selected.topPacket));
    }

    private List<ReportWriter.ModSummary> filteredMods(TrafficReport report) {
        ensureFilters(report);
        return cachedFilteredMods;
    }

    private void rebuildFilters(TrafficReport report) {
        ensureCache(report);
        List<ReportWriter.ModSummary> rows = new ArrayList<>(cachedMods);
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        rows.removeIf(row -> (!includeC2s && row.s2cBytes == 0L) || (!includeS2c && row.c2sBytes == 0L)
                || (!query.isEmpty() && !row.modId.toLowerCase(Locale.ROOT).contains(query)));
        Comparator<ReportWriter.ModSummary> modComparator;
        if (sort == Sort.NAME) modComparator = Comparator.comparing(row -> row.modId);
        else if (sort == Sort.COUNT) modComparator = Comparator.comparingLong(
                this::visiblePackets).reversed();
        else modComparator = Comparator.comparingLong((ReportWriter.ModSummary row) -> visibleBytes(row.c2sBytes, row.s2cBytes)).reversed();
        rows.sort(modComparator);
        cachedFilteredMods = rows;

        List<TrafficReport.PacketRow> packets = new ArrayList<>();
        for (TrafficReport.PacketRow row : report.packets) {
            if ((!includeC2s && "C2S".equals(row.direction)) || (!includeS2c && "S2C".equals(row.direction))) continue;
            StringBuilder searchable = new StringBuilder(row.modId).append(' ').append(row.channel).append(' ')
                    .append(row.packetClass).append(' ').append(row.handlerClass).append(' ').append(row.discriminator);
            ReportWriter.TracedPacketSummary trace = traceFor(row);
            if (trace != null) for (ReportWriter.TracePathSummary path : trace.paths) {
                searchable.append(' ').append(path.displayClass).append(' ').append(path.displayMethod);
                for (String frame : path.sampleStack) searchable.append(' ').append(frame);
            }
            if (!query.isEmpty() && !searchable.toString().toLowerCase(Locale.ROOT).contains(query)) continue;
            packets.add(row);
        }
        Comparator<TrafficReport.PacketRow> packetComparator;
        if (sort == Sort.NAME) packetComparator = Comparator.comparing(row -> row.packetClass);
        else if (sort == Sort.COUNT) packetComparator = Comparator.comparingLong(ReportWriter::packetCount).reversed();
        else packetComparator = Comparator.comparingLong(ReportWriter::trafficBytes).reversed();
        packets.sort(packetComparator);
        cachedFilteredPackets = packets;

        filtersDirty = false;
    }

    private List<TrafficReport.PacketRow> filteredPackets(TrafficReport report) {
        ensureFilters(report);
        return cachedFilteredPackets;
    }

    private void ensureFilters(TrafficReport report) {
        ensureCache(report);
        if (filtersDirty) rebuildFilters(report);
    }

    private void drawTableHeader(int x, int y, int w, String first, String second, String third, String fourth, String fifth) {
        drawRect(x + 1, y + 1, x + w - 1, y + 21, PANEL_ALT);
        drawString(fontRenderer, first, x + 8, y + 7, MUTED);
        drawRight(second, x + w * 58 / 100, y + 7, MUTED);
        drawRight(third, x + w * 72 / 100, y + 7, MUTED);
        drawRight(fourth, x + w * 87 / 100, y + 7, MUTED);
        drawRight(fifth, x + w - 8, y + 7, MUTED);
    }

    private void drawRowCount(int x, int y, int w, int h, int count) {
        drawRight(count + " 项", x + w - 8, y + h - 12, MUTED);
    }

    private void drawDetailLine(int x, int w, int y, String label, String value, int color) {
        drawString(fontRenderer, label, x + 10, y, MUTED);
        drawRight(trim(value, w - 82), x + w - 10, y, color);
    }

    private void drawWrappedValue(int x, int w, int y, String label, String value) {
        drawString(fontRenderer, label, x + 10, y, MUTED);
        drawString(fontRenderer, trim(value == null ? "-" : value, w - 20), x + 10, y + 14, TEXT);
    }

    private void drawSectionLabel(int x, int y, String text) {
        drawString(fontRenderer, text, x, y, MUTED);
    }

    private void drawPanel(int x, int y, int w, int h) {
        drawRect(x, y, x + w, y + h, BORDER);
        drawRect(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
    }

    private void drawPill(int x, int y, String text, int color) {
        int w = fontRenderer.getStringWidth(text) + 12;
        drawRect(x, y, x + w, y + 18, 0xFF202936);
        drawString(fontRenderer, text, x + 6, y + 5, color);
    }

    private void drawButton(int x, int y, int w, int h, String label, Action action, Object payload,
                            int mouseX, int mouseY, boolean selected, int accent) {
        boolean hovered = inside(mouseX, mouseY, x, y, w, h);
        int fill = selected ? 0xFF263B4A : hovered ? PANEL_ALT : PANEL;
        drawRect(x, y, x + w, y + h, selected ? accent : BORDER);
        drawRect(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        drawCenteredString(fontRenderer, label, x + w / 2, y + (h - 8) / 2, selected ? accent : TEXT);
        hitBoxes.add(new HitBox(x, y, x + w, y + h, action, payload));
    }

    private GuiTextField numericField(int id, int x, int y, int w, String value) {
        GuiTextField field = new GuiTextField(id, fontRenderer, x, y, w, 22);
        field.setMaxStringLength(3);
        field.setText(value);
        field.setEnableBackgroundDrawing(true);
        field.setValidator(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        return field;
    }

    private Totals totals(TrafficReport report) {
        ensureCache(report);
        return cachedTotals;
    }

    private void ensureCache(TrafficReport report) {
        if (cachedReport == report) return;
        cachedReport = report;
        cachedTotals = computeTotals(report);
        List<ReportWriter.ModSummary> mods = ReportWriter.summarize(report);
        mods.sort(Comparator.comparingLong((ReportWriter.ModSummary value) -> value.trafficBytes).reversed());
        cachedMods = mods;
        List<ReportWriter.TracedPacketSummary> tracedPackets = ReportWriter.summarizeTraces(report);
        tracedPackets.sort(Comparator.comparing(ReportWriter.TracedPacketSummary::registrationKey));
        cachedTracedPackets = tracedPackets;
        filtersDirty = true;
    }

    private Totals computeTotals(TrafficReport report) {
        Totals totals = new Totals();
        for (TrafficReport.PacketRow row : report.packets) {
            long wire = ReportWriter.trafficBytes(row);
            totals.wireBytes += wire;
            totals.frameBytes += ReportWriter.encodedBytes(row);
            totals.packetCount += ReportWriter.packetCount(row);
            if ("C2S".equals(row.direction)) totals.c2sBytes += wire;
            else totals.s2cBytes += wire;
        }
        return totals;
    }

    private void invalidateFilters() {
        filtersDirty = true;
        scrollOffset = 0;
    }

    private long peakBytes(TrafficReport report) {
        long peak = 0L;
        for (TrafficReport.TimeBucket bucket : report.timeline) peak = Math.max(peak, bucket.c2sBytes + bucket.s2cBytes);
        return peak;
    }

    private long peakPackets(TrafficReport report) {
        long peak = 0L;
        for (TrafficReport.TimeBucket bucket : report.timeline) peak = Math.max(peak, bucket.c2sPackets + bucket.s2cPackets);
        return peak;
    }

    private long visibleBytes(long c2s, long s2c) {
        return (includeC2s ? c2s : 0L) + (includeS2c ? s2c : 0L);
    }

    private long visibleEncoded(ReportWriter.ModSummary row) {
        return (includeC2s ? row.c2sEncodedBytes : 0L) + (includeS2c ? row.s2cEncodedBytes : 0L);
    }

    private long visiblePackets(ReportWriter.ModSummary row) {
        return (includeC2s ? row.c2sPackets : 0L) + (includeS2c ? row.s2cPackets : 0L);
    }

    private ReportWriter.TracedPacketSummary traceFor(TrafficReport.PacketRow row) {
        for (ReportWriter.TracedPacketSummary trace : cachedTracedPackets) if (trace.matches(row)) return trace;
        return null;
    }

    private String traceLabel(TrafficReport report, TrafficReport.PacketRow row,
                              ReportWriter.TracedPacketSummary trace) {
        if (trace != null) return Integer.toString(trace.paths.size());
        if (report.traceDepth == 0) return "关闭";
        if (("CLIENT_ONLY".equals(report.mode) && "S2C".equals(row.direction))
                || ("SERVER_ONLY".equals(report.mode) && "C2S".equals(row.direction))) return "远端";
        return "0";
    }

    private String trim(String value, int pixels) {
        if (value == null) return "-";
        return fontRenderer.trimStringToWidth(value, Math.max(8, pixels));
    }

    private String simpleName(String value) {
        if (value == null) return "-";
        int separator = Math.max(value.lastIndexOf('.'), value.lastIndexOf('$'));
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private String percent(long part, long total) {
        if (total <= 0L) return "-";
        return String.format(Locale.ROOT, "%.1f%%", part * 100.0 / total);
    }

    private void drawRight(String text, int x, int y, int color) {
        drawString(fontRenderer, text, x - fontRenderer.getStringWidth(text), y, color);
    }

    private boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        boolean setupVisible = showSetup || DiagnosticsManager.getLastReport() == null;
        if (!setupVisible && searchField != null) searchField.mouseClicked(mouseX, mouseY, mouseButton);
        if (setupVisible && durationField != null) durationField.mouseClicked(mouseX, mouseY, mouseButton);
        if (setupVisible && traceDepthField != null) traceDepthField.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton == 0) {
            for (int index = hitBoxes.size() - 1; index >= 0; index--) {
                HitBox hit = hitBoxes.get(index);
                if (mouseX >= hit.left && mouseX < hit.right && mouseY >= hit.top && mouseY < hit.bottom) {
                    perform(hit.action, hit.payload);
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (selectedMod != null) {
                clearSelection();
                return;
            }
            mc.displayGuiScreen(null);
            return;
        }
        boolean setupVisible = showSetup || DiagnosticsManager.getLastReport() == null;
        if (!setupVisible && searchField != null && searchField.isFocused()
                && searchField.textboxKeyTyped(typedChar, keyCode)) {
            searchText = searchField.getText();
            invalidateFilters();
            return;
        }
        if (setupVisible && durationField != null && durationField.isFocused()
                && durationField.textboxKeyTyped(typedChar, keyCode)) return;
        if (setupVisible && traceDepthField != null && traceDepthField.isFocused()
                && traceDepthField.textboxKeyTyped(typedChar, keyCode)) return;
        if (keyCode == Keyboard.KEY_F && isCtrlKeyDown() && searchField != null) searchField.setFocused(true);
        else super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && !showSetup && !DiagnosticsManager.isCapturing()) {
            scrollOffset = Math.max(0, scrollOffset + (wheel < 0 ? 3 : -3));
        }
    }

    private void perform(Action action, Object payload) {
        switch (action) {
            case CLOSE -> mc.displayGuiScreen(null);
            case SETUP -> {
                showSetup = true;
                clearSelection();
            }
            case START -> {
                Integer parsedDuration = parseSetting(durationField, 1, 300, "采集时长必须是 1–300 秒");
                if (parsedDuration == null) return;
                Integer parsedDepth = parseSetting(traceDepthField, 0, DiagnosticsManager.MAX_TRACE_DEPTH,
                        "调用栈深度必须是 0–64");
                if (parsedDepth == null) return;
                durationSeconds = parsedDuration;
                traceDepth = parsedDepth;
                boolean local = Minecraft.getMinecraft().isSingleplayer();
                if (DiagnosticsManager.startClient(durationSeconds, traceDepth, local)) {
                    statusMessage = "";
                    liveSnapshot = null;
                    livePaused = false;
                    showSetup = false;
                } else statusMessage = "已有采集或报告任务正在运行";
            }
            case STOP -> DiagnosticsManager.stop(Endpoint.CLIENT);
            case PAUSE -> livePaused = !livePaused;
            case PAGE -> {
                page = (Page) payload;
                scrollOffset = 0;
                clearSelection();
            }
            case TOGGLE_C2S -> {
                includeC2s = !includeC2s;
                if (!includeC2s && !includeS2c) includeS2c = true;
                invalidateFilters();
            }
            case TOGGLE_S2C -> {
                includeS2c = !includeS2c;
                if (!includeC2s && !includeS2c) includeC2s = true;
                invalidateFilters();
            }
            case SORT -> {
                sort = (Sort) payload;
                invalidateFilters();
            }
            case SELECT_MOD -> selectedMod = (String) payload;
            case OPEN_PACKET -> {
                TrafficReport.PacketRow packet = (TrafficReport.PacketRow) payload;
                mc.displayGuiScreen(new PacketDetailsScreen(this, cachedReport, packet, traceFor(packet)));
            }
            case CLEAR_SELECTION -> clearSelection();
            case OPEN_REPORT -> openReport(false);
            case OPEN_DIRECTORY -> openReport(true);
        }
    }

    private void clearSelection() {
        selectedMod = null;
    }

    private Integer parseSetting(GuiTextField field, int minimum, int maximum, String error) {
        try {
            int value = Integer.parseInt(field.getText());
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ignored) {
            statusMessage = error;
            field.setFocused(true);
            return null;
        }
    }

    private void openReport(boolean directory) {
        TrafficReport report = DiagnosticsManager.getLastReport();
        if (report == null || report.reportDirectory == null) return;
        try {
            File target = directory ? new File(report.reportDirectory) : new File(report.reportDirectory, "report.html");
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(target);
        } catch (Exception exception) {
            statusMessage = "无法打开：" + exception.getMessage();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private enum Page { OVERVIEW, MODS, PACKETS }
    private enum Sort { TRAFFIC, COUNT, NAME }
    private enum Action {
        CLOSE, SETUP, START, STOP, PAUSE, PAGE, TOGGLE_C2S, TOGGLE_S2C, SORT,
        SELECT_MOD, OPEN_PACKET, CLEAR_SELECTION, OPEN_REPORT, OPEN_DIRECTORY
    }

    private record HitBox(int left, int top, int right, int bottom, Action action, Object payload) { }

    private static final class Totals {
        long c2sBytes;
        long s2cBytes;
        long wireBytes;
        long frameBytes;
        long packetCount;
    }
}
