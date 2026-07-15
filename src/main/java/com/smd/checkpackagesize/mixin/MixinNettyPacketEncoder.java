package com.smd.checkpackagesize.mixin;

import com.smd.checkpackagesize.diagnostics.DiagnosticHooks;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NettyPacketEncoder;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NettyPacketEncoder.class)
public abstract class MixinNettyPacketEncoder {

    @Shadow @Final private EnumPacketDirection direction;
    @Unique private int checkPackageSize$writerIndex;
    @Unique private boolean checkPackageSize$active;

    @Inject(method = "encode", at = @At("HEAD"))
    private void checkPackageSize$begin(ChannelHandlerContext context, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
        checkPackageSize$active = DiagnosticHooks.isCapturing();
        if (checkPackageSize$active) {
            checkPackageSize$writerIndex = output.writerIndex();
        }
    }

    @Inject(method = "encode", at = @At("RETURN"))
    private void checkPackageSize$end(ChannelHandlerContext context, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
        if (checkPackageSize$active) {
            int bytes = output.writerIndex() - checkPackageSize$writerIndex;
            boolean compressed = context.pipeline().get("compress") != null;
            DiagnosticHooks.onEncoded(context.channel(), direction, packet, bytes, compressed);
            checkPackageSize$active = false;
        }
    }
}
