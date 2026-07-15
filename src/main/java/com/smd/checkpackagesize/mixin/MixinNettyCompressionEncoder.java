package com.smd.checkpackagesize.mixin;

import com.smd.checkpackagesize.diagnostics.DiagnosticHooks;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.NettyCompressionEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NettyCompressionEncoder.class)
public abstract class MixinNettyCompressionEncoder {

    @Unique private int checkPackageSize$writerIndex;
    @Unique private boolean checkPackageSize$active;

    @Inject(method = "encode", at = @At("HEAD"))
    private void checkPackageSize$begin(ChannelHandlerContext context, ByteBuf input, ByteBuf output, CallbackInfo ci) {
        checkPackageSize$active = DiagnosticHooks.isCapturing();
        if (checkPackageSize$active) {
            checkPackageSize$writerIndex = output.writerIndex();
        }
    }

    @Inject(method = "encode", at = @At("RETURN"))
    private void checkPackageSize$end(ChannelHandlerContext context, ByteBuf input, ByteBuf output, CallbackInfo ci) {
        if (checkPackageSize$active) {
            DiagnosticHooks.onCompressedOutbound(context.channel(), output.writerIndex() - checkPackageSize$writerIndex);
            checkPackageSize$active = false;
        }
    }
}
