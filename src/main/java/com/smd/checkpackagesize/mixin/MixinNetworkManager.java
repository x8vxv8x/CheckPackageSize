package com.smd.checkpackagesize.mixin;

import com.smd.checkpackagesize.diagnostics.DiagnosticHooks;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetworkManager.class)
public abstract class MixinNetworkManager {

    @Shadow @Final private EnumPacketDirection direction;
    @Shadow private Channel channel;

    @Inject(method = "sendPacket(Lnet/minecraft/network/Packet;)V", at = @At("HEAD"))
    private void checkPackageSize$onSend(Packet<?> packet, CallbackInfo ci) {
        checkPackageSize$captureQueued(packet);
    }

    @Inject(method = "sendPacket(Lnet/minecraft/network/Packet;Lio/netty/util/concurrent/GenericFutureListener;[Lio/netty/util/concurrent/GenericFutureListener;)V", at = @At("HEAD"))
    private void checkPackageSize$onSendWithListeners(Packet<?> packet,
                                                      GenericFutureListener<? extends Future<? super Void>> listener,
                                                      GenericFutureListener<? extends Future<? super Void>>[] listeners,
                                                      CallbackInfo ci) {
        checkPackageSize$captureQueued(packet);
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V", at = @At("HEAD"))
    private void checkPackageSize$beginInbound(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        DiagnosticHooks.beginInbound(packet, direction);
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V", at = @At("RETURN"))
    private void checkPackageSize$endInbound(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        DiagnosticHooks.endInbound();
    }

    private void checkPackageSize$captureQueued(Packet<?> packet) {
        if (!DiagnosticHooks.isCapturing()) {
            return;
        }
        boolean local = channel != null && ((NetworkManager) (Object) this).isLocalChannel();
        EnumConnectionState state = channel == null ? null : channel.attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get();
        DiagnosticHooks.onPacketQueued(packet, direction, local, state);
    }
}
