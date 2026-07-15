package com.smd.checkpackagesize.client;

import com.smd.checkpackagesize.diagnostics.DiagnosticsManager;
import com.smd.checkpackagesize.diagnostics.Endpoint;
import com.smd.checkpackagesize.diagnostics.ReportWriter;
import com.smd.checkpackagesize.diagnostics.TrafficReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SideOnly(Side.CLIENT)
public final class DiagnosticsScreen extends GuiScreen {

    private int durationSeconds = 30;
    private String selectedMod;
    private List<RowHit> rowHits = new ArrayList<>();
    private String statusMessage = "";

    @Override
    public void initGui() {
        buttonList.clear();
        int center = width / 2;
        buttonList.add(new GuiButton(10, center - 154, 34, 48, 20, "10 秒"));
        buttonList.add(new GuiButton(30, center - 102, 34, 48, 20, "30 秒"));
        buttonList.add(new GuiButton(60, center - 50, 34, 48, 20, "60 秒"));
        buttonList.add(new GuiButton(100, center + 6, 34, 92, 20, DiagnosticsManager.isCapturing() ? "采集中" : "开始采集"));
        buttonList.add(new GuiButton(101, center + 102, 34, 52, 20, "停止"));
        buttonList.add(new GuiButton(102, 8, height - 28, 60, 20, selectedMod == null ? "关闭" : "返回"));
        buttonList.add(new GuiButton(103, width - 108, height - 28, 100, 20, "打开报告目录"));
        updateButtons();
    }

    private void updateButtons() {
        boolean capturing = DiagnosticsManager.isCapturing();
        boolean finalizing = DiagnosticsManager.isFinalizing();
        for (GuiButton button : buttonList) {
            if (button.id == 100) {
                button.enabled = !capturing && !finalizing;
                button.displayString = capturing ? "采集中" : finalizing ? "生成报告" : "开始采集";
            } else if (button.id == 101) {
                button.enabled = capturing;
            } else if (button.id == 10 || button.id == 30 || button.id == 60) {
                button.enabled = !capturing && !finalizing;
                button.packedFGColour = 0;
                button.displayString = (button.id == durationSeconds ? "● " : "") + button.id + " 秒";
            } else if (button.id == 103) {
                TrafficReport report = DiagnosticsManager.getLastReport();
                button.enabled = report != null && report.reportDirectory != null;
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 10 || button.id == 30 || button.id == 60) {
            durationSeconds = button.id;
        } else if (button.id == 100) {
            selectedMod = null;
            boolean local = Minecraft.getMinecraft().isSingleplayer();
            if (!DiagnosticsManager.startClient(durationSeconds, local)) {
                statusMessage = "已有采集正在运行";
            } else {
                statusMessage = local ? "单机客户端与整合服务端正在联合采集" : "只采集当前客户端数据";
            }
        } else if (button.id == 101) {
            DiagnosticsManager.stop(Endpoint.CLIENT);
        } else if (button.id == 102) {
            if (selectedMod == null) {
                mc.displayGuiScreen(null);
            } else {
                selectedMod = null;
            }
        } else if (button.id == 103) {
            openReportDirectory();
        }
        updateButtons();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        updateButtons();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, "网络流量诊断", width / 2, 12, 0xFFFFFF);
        if (DiagnosticsManager.isCapturing()) {
            long remaining = DiagnosticsManager.getRemainingMillis();
            long duration = DiagnosticsManager.getDurationMillis();
            long elapsed = Math.max(0L, duration - remaining);
            drawCenteredString(fontRenderer, "正在采集：" + String.format("%.1f / %.0f 秒", elapsed / 1000.0, duration / 1000.0),
                    width / 2, 62, 0x55FF55);
            drawCenteredString(fontRenderer, statusMessage, width / 2, 76, 0xBBBBBB);
        } else if (DiagnosticsManager.isFinalizing()) {
            drawCenteredString(fontRenderer, "正在后台生成 HTML 报告…", width / 2, 66, 0xFFCC55);
        } else {
            TrafficReport report = DiagnosticsManager.getLastReport();
            if (report == null) {
                drawCenteredString(fontRenderer, "选择时长并开始采集，然后在游戏中复现问题", width / 2, 66, 0xBBBBBB);
            } else if (selectedMod == null) {
                drawModSummary(report);
            } else {
                drawPacketDetails(report, selectedMod);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawModSummary(TrafficReport report) {
        rowHits.clear();
        String source = report.localTheoretical ? "单机客户端 + 整合服务端联合报告" : "仅当前客户端报告";
        drawCenteredString(fontRenderer, source,
                width / 2, 64, 0xFFCC55);
        drawHeaders("Mod", "C2S", "S2C", "包数", "编码", "压缩率");
        List<ReportWriter.ModSummary> summaries = ReportWriter.summarize(report);
        summaries.sort(Comparator.comparingLong((ReportWriter.ModSummary value) -> value.trafficBytes).reversed());
        int y = 98;
        for (int index = 0; index < Math.min(12, summaries.size()); index++) {
            ReportWriter.ModSummary summary = summaries.get(index);
            int color = index == 0 ? 0xFFCC55 : 0xFFFFFF;
            drawString(fontRenderer, trim(summary.modId, 22), 18, y, color);
            drawRight(ReportWriter.formatBytes(summary.c2sBytes), width / 2 - 92, y, color);
            drawRight(ReportWriter.formatBytes(summary.s2cBytes), width / 2 - 8, y, color);
            drawRight(Long.toString(summary.packetCount), width / 2 + 82, y, color);
            drawRight(ReportWriter.formatBytes(summary.encodedBytes), width / 2 + 174, y, color);
            drawRight(ReportWriter.formatRatio(summary.encodedBytes, summary.trafficBytes), width - 18, y, color);
            rowHits.add(new RowHit(y - 2, y + 10, summary.modId));
            y += 13;
        }
        if (summaries.isEmpty()) {
            drawCenteredString(fontRenderer, "采集期间没有观察到网络包", width / 2, 112, 0xBBBBBB);
        } else {
            drawCenteredString(fontRenderer, "点击 Mod 查看具体包类", width / 2, height - 42, 0x888888);
        }
    }

    private void drawPacketDetails(TrafficReport report, String modId) {
        rowHits.clear();
        drawCenteredString(fontRenderer, "Mod：" + modId, width / 2, 64, 0xFFCC55);
        drawHeaders("方向 / 包类", "流量", "次数", "编码", "平均", "压缩率");
        List<TrafficReport.PacketRow> packets = new ArrayList<>();
        for (TrafficReport.PacketRow row : report.packets) {
            if (modId.equals(row.modId)) packets.add(row);
        }
        packets.sort(Comparator.comparingLong(ReportWriter::trafficBytes).reversed());
        int y = 98;
        for (int index = 0; index < Math.min(12, packets.size()); index++) {
            TrafficReport.PacketRow row = packets.get(index);
            long count = ReportWriter.packetCount(row);
            long transferred = ReportWriter.trafficBytes(row);
            long encoded = ReportWriter.encodedBytes(row);
            String simpleName = row.packetClass.substring(row.packetClass.lastIndexOf('.') + 1);
            int color = index == 0 ? 0xFFCC55 : 0xFFFFFF;
            drawString(fontRenderer, trim(row.direction + " " + simpleName, 32), 18, y, color);
            drawRight(ReportWriter.formatBytes(transferred), width / 2 - 92, y, color);
            drawRight(Long.toString(count), width / 2 - 8, y, color);
            drawRight(ReportWriter.formatBytes(encoded), width / 2 + 82, y, color);
            drawRight(ReportWriter.formatBytes(count == 0 ? 0 : transferred / count), width / 2 + 174, y, color);
            drawRight(ReportWriter.formatRatio(encoded, transferred), width - 18, y, color);
            y += 13;
        }
        drawCenteredString(fontRenderer, "完整频道、类名、Handler 和端点流量请查看 HTML 报告", width / 2, height - 42, 0x888888);
    }

    private void drawHeaders(String first, String second, String third, String fourth, String fifth, String sixth) {
        int y = 84;
        drawString(fontRenderer, first, 18, y, 0xAAAAAA);
        drawRight(second, width / 2 - 92, y, 0xAAAAAA);
        drawRight(third, width / 2 - 8, y, 0xAAAAAA);
        drawRight(fourth, width / 2 + 82, y, 0xAAAAAA);
        drawRight(fifth, width / 2 + 174, y, 0xAAAAAA);
        drawRight(sixth, width - 18, y, 0xAAAAAA);
    }

    private void drawRight(String text, int x, int y, int color) {
        drawString(fontRenderer, text, x - fontRenderer.getStringWidth(text), y, color);
    }

    private String trim(String text, int maxCharacters) {
        return text.length() <= maxCharacters ? text : text.substring(0, maxCharacters - 1) + "…";
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton == 0 && selectedMod == null && !DiagnosticsManager.isCapturing()) {
            for (RowHit hit : rowHits) {
                if (mouseY >= hit.top && mouseY <= hit.bottom) {
                    selectedMod = hit.modId;
                    return;
                }
            }
        }
    }

    private void openReportDirectory() {
        TrafficReport report = DiagnosticsManager.getLastReport();
        if (report == null || report.reportDirectory == null) return;
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(new File(report.reportDirectory));
        } catch (Exception exception) {
            statusMessage = "无法打开目录：" + exception.getMessage();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static final class RowHit {
        final int top;
        final int bottom;
        final String modId;

        private RowHit(int top, int bottom, String modId) {
            this.top = top;
            this.bottom = bottom;
            this.modId = modId;
        }
    }
}
