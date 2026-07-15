package com.smd.checkpackagesize.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.smd.checkpackagesize.Reference;
import com.smd.checkpackagesize.diagnostics.DiagnosticsManager;
import com.smd.checkpackagesize.diagnostics.TrafficReport;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IThreadListener;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public final class NetworkBridge {

    private static final Gson GSON = new GsonBuilder().create();
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("cpsdiag");
    private static volatile UUID requester;
    private static volatile String clientStatus = "";

    private NetworkBridge() {
    }

    public static void initialize() {
        CHANNEL.registerMessage(StartHandler.class, StartMessage.class, 0, Side.SERVER);
        CHANNEL.registerMessage(StopHandler.class, StopMessage.class, 1, Side.SERVER);
        CHANNEL.registerMessage(ResultHandler.class, ResultMessage.class, 2, Side.CLIENT);
    }

    public static boolean startFromClient(int durationSeconds, boolean singleplayer) {
        boolean started = DiagnosticsManager.start(durationSeconds, singleplayer);
        if (!started) return false;
        clientStatus = singleplayer ? "请复现异常行为，采集会自动结束" : "本地与服务端正在同步采集";
        if (!singleplayer) CHANNEL.sendToServer(new StartMessage(durationSeconds));
        return true;
    }

    public static void stopFromClient(boolean singleplayer) {
        DiagnosticsManager.stop();
        if (!singleplayer) CHANNEL.sendToServer(new StopMessage());
    }

    public static String getClientStatus() {
        return clientStatus;
    }

    public static void onServerReport(TrafficReport report) {
        UUID target = requester;
        requester = null;
        if (target == null) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(target);
        if (player != null) CHANNEL.sendTo(new ResultMessage(report, null), player);
    }

    private static boolean canControl(EntityPlayerMP player) {
        return player != null && player.canUseCommand(2, Reference.MOD_ID);
    }

    public static final class StartMessage implements IMessage {
        private int durationSeconds;

        public StartMessage() {
        }

        StartMessage(int durationSeconds) {
            this.durationSeconds = durationSeconds;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            durationSeconds = buffer.readInt();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeInt(durationSeconds);
        }
    }

    public static final class StopMessage implements IMessage {
        @Override public void fromBytes(ByteBuf buffer) { }
        @Override public void toBytes(ByteBuf buffer) { }
    }

    public static final class ResultMessage implements IMessage {
        private TrafficReport report;
        private String error;

        public ResultMessage() {
        }

        ResultMessage(TrafficReport report, String error) {
            this.report = report;
            this.error = error;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            try {
                int length = buffer.readInt();
                if (length < 0 || length > 8 * 1024 * 1024 || length > buffer.readableBytes()) {
                    throw new IllegalArgumentException("Invalid report length " + length);
                }
                byte[] compressed = new byte[length];
                buffer.readBytes(compressed);
                byte[] json = gunzip(compressed);
                WireResult value = GSON.fromJson(new String(json, StandardCharsets.UTF_8), WireResult.class);
                report = value.report;
                error = value.error;
            } catch (Exception exception) {
                error = "无法读取服务端报告：" + exception.getMessage();
            }
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            try {
                WireResult value = new WireResult();
                value.report = report;
                value.error = error;
                byte[] compressed = gzip(GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
                buffer.writeInt(compressed.length);
                buffer.writeBytes(compressed);
            } catch (Exception exception) {
                buffer.writeInt(0);
            }
        }
    }

    public static final class StartHandler implements IMessageHandler<StartMessage, IMessage> {
        @Override
        public IMessage onMessage(StartMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            MinecraftServer server = player.getServer();
            server.addScheduledTask(() -> {
                if (!canControl(player)) {
                    CHANNEL.sendTo(new ResultMessage(null, "需要服务器 OP 权限才能启动全服采集"), player);
                    return;
                }
                if (requester != null || !DiagnosticsManager.start(message.durationSeconds, false)) {
                    CHANNEL.sendTo(new ResultMessage(null, "服务端已有采集正在运行"), player);
                    return;
                }
                requester = player.getUniqueID();
            });
            return null;
        }
    }

    public static final class StopHandler implements IMessageHandler<StopMessage, IMessage> {
        @Override
        public IMessage onMessage(StopMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServer().addScheduledTask(() -> {
                if (canControl(player) && player.getUniqueID().equals(requester)) DiagnosticsManager.stop();
            });
            return null;
        }
    }

    public static final class ResultHandler implements IMessageHandler<ResultMessage, IMessage> {
        @Override
        public IMessage onMessage(ResultMessage message, MessageContext context) {
            IThreadListener thread = FMLCommonHandler.instance().getWorldThread(context.netHandler);
            thread.addScheduledTask(() -> {
                if (message.error != null) {
                    clientStatus = message.error;
                } else if (message.report != null) {
                    DiagnosticsManager.mergeRemoteReport(message.report);
                    clientStatus = "服务端报告已合并";
                }
            });
            return null;
        }
    }

    private static byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }

    private static byte[] gunzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static final class WireResult {
        TrafficReport report;
        String error;
    }
}
