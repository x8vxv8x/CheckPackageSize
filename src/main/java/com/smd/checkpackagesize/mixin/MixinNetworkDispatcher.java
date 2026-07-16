package com.smd.checkpackagesize.mixin;

import com.smd.checkpackagesize.diagnostics.DiagnosticHooks;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = NetworkDispatcher.class, remap = false)
public abstract class MixinNetworkDispatcher {

    @Inject(method = "write", at = @At("HEAD"))
    private void checkPackageSize$beginWrite(ChannelHandlerContext context, Object message,
                                             ChannelPromise promise, CallbackInfo ci) {
        DiagnosticHooks.beginForgeProxyWrite(context.channel(), message);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void checkPackageSize$endWrite(ChannelHandlerContext context, Object message,
                                           ChannelPromise promise, CallbackInfo ci) {
        DiagnosticHooks.endForgeProxyWrite(message);
    }
}
