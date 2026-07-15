package com.smd.checkpackagesize.mixin;

import com.smd.checkpackagesize.diagnostics.DiagnosticHooks;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NettyPacketDecoder;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(NettyPacketDecoder.class)
public abstract class MixinNettyPacketDecoder {

    @Shadow @Final private EnumPacketDirection direction;
    @Unique private int checkPackageSize$bytes;
    @Unique private int checkPackageSize$outputSize;
    @Unique private boolean checkPackageSize$active;

    @Inject(method = "decode", at = @At("HEAD"))
    private void checkPackageSize$begin(ChannelHandlerContext context, ByteBuf input, List<Object> output, CallbackInfo ci) {
        checkPackageSize$active = DiagnosticHooks.isCapturing() && input.readableBytes() > 0;
        if (checkPackageSize$active) {
            checkPackageSize$bytes = input.readableBytes();
            checkPackageSize$outputSize = output.size();
        }
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void checkPackageSize$end(ChannelHandlerContext context, ByteBuf input, List<Object> output, CallbackInfo ci) {
        if (!checkPackageSize$active) {
            return;
        }
        boolean compressed = context.pipeline().get("decompress") != null;
        for (int index = checkPackageSize$outputSize; index < output.size(); index++) {
            Object value = output.get(index);
            if (value instanceof Packet) {
                DiagnosticHooks.onDecoded(context.channel(), direction, (Packet<?>) value,
                        checkPackageSize$bytes, compressed);
            }
        }
        checkPackageSize$active = false;
    }
}
