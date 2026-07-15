package com.smd.checkpackagesize.mixin;

import com.smd.checkpackagesize.diagnostics.DiagnosticHooks;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.NettyCompressionDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(NettyCompressionDecoder.class)
public abstract class MixinNettyCompressionDecoder {

    @Inject(method = "decode", at = @At("HEAD"))
    private void checkPackageSize$record(ChannelHandlerContext context, ByteBuf input, List<Object> output, CallbackInfo ci) {
        if (DiagnosticHooks.isCapturing() && input.readableBytes() > 0) {
            DiagnosticHooks.onCompressedInbound(context.channel(), input.readableBytes());
        }
    }
}
