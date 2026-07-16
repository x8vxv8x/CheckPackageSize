package com.smd.checkpackagesize.command;

import com.smd.checkpackagesize.diagnostics.DiagnosticsManager;
import com.smd.checkpackagesize.diagnostics.Endpoint;
import com.smd.checkpackagesize.diagnostics.TrafficReport;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

import javax.annotation.Nullable;
import java.util.List;

public final class DiagnosticsCommand extends CommandBase {

    @Override
    public String getName() {
        return "cps";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/cps <start [秒数] [调用栈深度]|stop|status|report>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sender.sendMessage(new TextComponentString(DiagnosticsManager.isCapturing(Endpoint.SERVER)
                    ? "[CPS] 服务端正在采集，剩余 " + DiagnosticsManager.getRemainingMillis() / 1000.0
                            + " 秒，调用栈深度 " + DiagnosticsManager.getTraceDepth()
                    : "[CPS] 服务端当前没有采集任务"));
            return;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> {
                int seconds = args.length > 1 ? parseInt(args[1], 1, 300) : 30;
                int traceDepth = args.length > 2 ? parseInt(args[2], 0, DiagnosticsManager.MAX_TRACE_DEPTH)
                        : DiagnosticsManager.DEFAULT_TRACE_DEPTH;
                boolean started = DiagnosticsManager.startServer(seconds, traceDepth);
                sender.sendMessage(new TextComponentString(started
                        ? "[CPS] 已开始服务端采集，持续 " + seconds + " 秒，调用栈深度 " + traceDepth
                        : "[CPS] 已有采集任务正在运行"));
            }
            case "stop" -> {
                DiagnosticsManager.stop(Endpoint.SERVER);
                sender.sendMessage(new TextComponentString("[CPS] 服务端采集已停止，报告正在后台生成"));
            }
            case "report" -> {
                TrafficReport report = DiagnosticsManager.getLastReport();
                sender.sendMessage(new TextComponentString(report == null || report.reportDirectory == null
                        ? "[CPS] 暂无报告"
                        : "[CPS] 报告目录：" + report.reportDirectory));
            }
            default -> throw new CommandException(getUsage(sender));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "start", "stop", "status", "report");
        if (args.length == 2 && "start".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "10", "30", "60");
        }
        if (args.length == 3 && "start".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "0", "8", "16", "32");
        }
        return List.of();
    }
}
