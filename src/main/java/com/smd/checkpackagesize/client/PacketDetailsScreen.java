package com.smd.checkpackagesize.client;

import com.smd.checkpackagesize.diagnostics.ReportWriter;
import com.smd.checkpackagesize.diagnostics.TrafficReport;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SideOnly(Side.CLIENT)
final class PacketDetailsScreen extends GuiScreen {

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
    private static final int WARN = 0xFFFBBF24;

    private final GuiScreen parent;
    private final TrafficReport report;
    private final TrafficReport.PacketRow packet;
    private final ReportWriter.TracedPacketSummary traced;
    private final List<ReportWriter.TracePathSummary> paths;
    private final List<HitBox> hitBoxes = new ArrayList<>();
    private ReportWriter.TracePathSummary selectedPath;
    private List<String> stackLines = List.of();
    private ReportWriter.TracePathSummary cachedStackPath;
    private int cachedStackWidth = -1;
    private int pathScroll;
    private int stackScroll;
    private int pathX;
    private int pathY;
    private int pathW;
    private int pathH;
    private int stackX;
    private int stackY;
    private int stackW;
    private int stackH;

    PacketDetailsScreen(GuiScreen parent, TrafficReport report, TrafficReport.PacketRow packet,
                        ReportWriter.TracedPacketSummary traced) {
        this.parent = parent;
        this.report = report;
        this.packet = packet;
        this.traced = traced;
        this.paths = traced == null ? new ArrayList<>() : new ArrayList<>(traced.paths);
        this.paths.sort(Comparator.comparingLong((ReportWriter.TracePathSummary value) -> value.packetCount).reversed());
        this.selectedPath = paths.isEmpty() ? null : paths.getFirst();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, BG);
        hitBoxes.clear();
        drawHeader(mouseX, mouseY);
        drawPacketSummary();
        layoutPanels();
        drawPathPanel(mouseX, mouseY);
        drawStackPanel(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawHeader(int mouseX, int mouseY) {
        drawRect(0, 0, width, 34, TOP);
        drawRect(0, 33, width, 34, BORDER);
        drawButton(12, 7, 64, 20, "← 返回", Action.BACK, null, mouseX, mouseY, false, MUTED);
        drawString(fontRenderer, trim(simpleName(packet.packetClass), Math.max(100, width - 260)), 88, 12, TEXT);
        drawPill(width - 66, 8, packet.direction,
                "C2S".equals(packet.direction) ? C2S : S2C);
    }

    private void drawPacketSummary() {
        int x = 16;
        int y = 44;
        int w = width - 32;
        drawPanel(x, y, w, 78);
        drawString(fontRenderer, "包所属 Mod", x + 10, y + 10, MUTED);
        drawString(fontRenderer, trim(packet.modId, Math.max(80, w / 4)), x + 88, y + 10, TEXT);
        drawString(fontRenderer, "Channel", x + w / 3, y + 10, MUTED);
        drawString(fontRenderer, trim(packet.channel, Math.max(80, w / 4)), x + w / 3 + 52, y + 10, TEXT);
        drawString(fontRenderer, "Discriminator", x + w * 2 / 3, y + 10, MUTED);
        drawString(fontRenderer, packet.discriminator < 0 ? "-" : Integer.toString(packet.discriminator),
                x + w * 2 / 3 + 80, y + 10, TEXT);

        long totalCount = ReportWriter.packetCount(packet);
        long tracedCount = traced == null ? 0L : traced.packetCount;
        long missingCount = Math.max(0L, totalCount - tracedCount);
        int metricY = y + 38;
        drawMetric(x + 10, metricY, "总包数", Long.toString(totalCount), TEXT);
        drawMetric(x + w / 5, metricY, "已追溯本端", Long.toString(tracedCount), ACCENT);
        drawMetric(x + w * 2 / 5, metricY, "无本地栈", Long.toString(missingCount), WARN);
        drawMetric(x + w * 3 / 5, metricY, "Wire", ReportWriter.formatBytes(ReportWriter.trafficBytes(packet)), ACCENT);
        drawMetric(x + w * 4 / 5, metricY, "Frame", ReportWriter.formatBytes(ReportWriter.encodedBytes(packet)), TEXT);
    }

    private void layoutPanels() {
        int top = 132;
        int bottom = height - 16;
        if (width >= 700) {
            pathX = 16;
            pathY = top;
            pathW = Math.clamp(width * 36 / 100, 250, 390);
            pathH = bottom - top;
            stackX = pathX + pathW + 8;
            stackY = top;
            stackW = width - stackX - 16;
            stackH = pathH;
        } else {
            pathX = 16;
            pathY = top;
            pathW = width - 32;
            pathH = Math.clamp((bottom - top) * 42 / 100, 105, 180);
            stackX = 16;
            stackY = pathY + pathH + 8;
            stackW = width - 32;
            stackH = bottom - stackY;
        }
    }

    private void drawPathPanel(int mouseX, int mouseY) {
        drawPanel(pathX, pathY, pathW, pathH);
        drawString(fontRenderer, "调用路径 · " + paths.size(), pathX + 10, pathY + 9, TEXT);
        if (paths.isEmpty()) {
            drawString(fontRenderer, unavailableReason(), pathX + 10, pathY + 32, WARN);
            return;
        }

        int visibleRows = Math.max(1, (pathH - 38) / 32);
        pathScroll = clamp(pathScroll, 0, Math.max(0, paths.size() - visibleRows));
        int rowY = pathY + 27;
        for (int index = pathScroll; index < Math.min(paths.size(), pathScroll + visibleRows); index++) {
            ReportWriter.TracePathSummary path = paths.get(index);
            boolean selected = path == selectedPath;
            boolean hovered = inside(mouseX, mouseY, pathX + 2, rowY - 2, pathW - 4, 30);
            if (selected || hovered) drawRect(pathX + 2, rowY - 2, pathX + pathW - 2, rowY + 28,
                    selected ? 0xFF264256 : PANEL_ALT);
            drawString(fontRenderer, trim(path.displayName(), pathW - 20), pathX + 9, rowY + 2,
                    selected ? ACCENT : TEXT);
            drawString(fontRenderer, path.packetCount + " 包 · " + ReportWriter.formatBytes(path.trafficBytes),
                    pathX + 9, rowY + 16, MUTED);
            hitBoxes.add(new HitBox(pathX + 2, rowY - 2, pathX + pathW - 2, rowY + 28,
                    Action.SELECT_PATH, path));
            rowY += 32;
        }
    }

    private void drawStackPanel(int mouseX, int mouseY) {
        drawPanel(stackX, stackY, stackW, stackH);
        drawString(fontRenderer, "完整调用栈 · 配置深度 " + report.traceDepth, stackX + 10, stackY + 9, TEXT);
        if (selectedPath == null) {
            drawString(fontRenderer, "选择左侧调用路径查看完整栈", stackX + 10, stackY + 32, MUTED);
            return;
        }

        drawString(fontRenderer, selectedPath.packetCount + " 包 · "
                + ReportWriter.formatBytes(selectedPath.trafficBytes), stackX + 10, stackY + 25, MUTED);
        ensureStackLines(stackW - 20);
        int contentTop = stackY + 43;
        int buttonTop = stackY + stackH - 30;
        int visibleRows = Math.max(1, (buttonTop - contentTop - 4) / 13);
        stackScroll = clamp(stackScroll, 0, Math.max(0, stackLines.size() - visibleRows));
        int lineY = contentTop;
        for (int index = stackScroll; index < Math.min(stackLines.size(), stackScroll + visibleRows); index++) {
            drawString(fontRenderer, stackLines.get(index), stackX + 10, lineY, TEXT);
            lineY += 13;
        }
        drawButton(stackX + 10, buttonTop, stackW - 20, 22, "复制完整调用栈", Action.COPY_STACK,
                null, mouseX, mouseY, false, MUTED);
    }

    private void ensureStackLines(int availableWidth) {
        if (cachedStackPath == selectedPath && cachedStackWidth == availableWidth) return;
        ArrayList<String> result = new ArrayList<>();
        for (int index = 0; index < selectedPath.sampleStack.size(); index++) {
            String frame = String.format("%02d  %s", index + 1, selectedPath.sampleStack.get(index));
            result.addAll(fontRenderer.listFormattedStringToWidth(frame, Math.max(40, availableWidth)));
        }
        stackLines = result;
        cachedStackPath = selectedPath;
        cachedStackWidth = availableWidth;
        stackScroll = 0;
    }

    private String unavailableReason() {
        if (report.traceDepth == 0) return "本次采集关闭了调用栈追溯";
        if (("CLIENT_ONLY".equals(report.mode) && "S2C".equals(packet.direction))
                || ("SERVER_ONLY".equals(report.mode) && "C2S".equals(packet.direction))) {
            return "该包由远端发出，本端调用栈不可见";
        }
        return "没有捕获到该包的本端发送路径";
    }

    private void drawMetric(int x, int y, String label, String value, int color) {
        drawString(fontRenderer, label, x, y, MUTED);
        drawString(fontRenderer, value, x, y + 13, color);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
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
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mouseX = Mouse.getEventX() * width / Math.max(1, mc.displayWidth);
        int mouseY = height - Mouse.getEventY() * height / Math.max(1, mc.displayHeight) - 1;
        int delta = wheel < 0 ? 3 : -3;
        if (inside(mouseX, mouseY, pathX, pathY, pathW, pathH)) pathScroll = Math.max(0, pathScroll + delta);
        else if (inside(mouseX, mouseY, stackX, stackY, stackW, stackH)) stackScroll = Math.max(0, stackScroll + delta);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void perform(Action action, Object payload) {
        switch (action) {
            case BACK -> mc.displayGuiScreen(parent);
            case SELECT_PATH -> {
                selectedPath = (ReportWriter.TracePathSummary) payload;
                cachedStackPath = null;
                stackScroll = 0;
            }
            case COPY_STACK -> {
                if (selectedPath != null) setClipboardString(String.join("\n", selectedPath.sampleStack));
            }
        }
    }

    private void drawPanel(int x, int y, int w, int h) {
        drawRect(x, y, x + w, y + h, BORDER);
        drawRect(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
    }

    private void drawPill(int x, int y, String text, int color) {
        int w = fontRenderer.getStringWidth(text) + 12;
        drawRect(x, y, x + w, y + 18, PANEL_ALT);
        drawString(fontRenderer, text, x + 6, y + 5, color);
    }

    private void drawButton(int x, int y, int w, int h, String label, Action action, Object payload,
                            int mouseX, int mouseY, boolean selected, int accent) {
        boolean hovered = inside(mouseX, mouseY, x, y, w, h);
        drawRect(x, y, x + w, y + h, selected ? accent : BORDER);
        drawRect(x + 1, y + 1, x + w - 1, y + h - 1, selected ? 0xFF263B4A : hovered ? PANEL_ALT : PANEL);
        drawCenteredString(fontRenderer, label, x + w / 2, y + (h - 8) / 2, selected ? accent : TEXT);
        hitBoxes.add(new HitBox(x, y, x + w, y + h, action, payload));
    }

    private String trim(String value, int pixels) {
        return fontRenderer.trimStringToWidth(value == null ? "-" : value, Math.max(8, pixels));
    }

    private String simpleName(String value) {
        if (value == null) return "-";
        int separator = Math.max(value.lastIndexOf('.'), value.lastIndexOf('$'));
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private enum Action { BACK, SELECT_PATH, COPY_STACK }

    private record HitBox(int left, int top, int right, int bottom, Action action, Object payload) { }
}
