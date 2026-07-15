package com.smd.checkpackagesize.mixin;

import com.smd.checkpackagesize.diagnostics.MessageRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SimpleNetworkWrapper.class, remap = false)
public abstract class MixinSimpleNetworkWrapper {

    @Unique private String checkPackageSize$channelName;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void checkPackageSize$rememberChannel(String channelName, CallbackInfo ci) {
        checkPackageSize$channelName = channelName;
    }

    @Inject(method = "registerMessage(Lnet/minecraftforge/fml/common/network/simpleimpl/IMessageHandler;Ljava/lang/Class;ILnet/minecraftforge/fml/relauncher/Side;)V",
            at = @At("HEAD"))
    private void checkPackageSize$register(IMessageHandler<?, ?> handler, Class<? extends IMessage> messageClass,
                                           int discriminator, Side side, CallbackInfo ci) {
        MessageRegistry.register(checkPackageSize$channelName, discriminator, messageClass,
                handler == null ? null : handler.getClass(), side);
    }
}
