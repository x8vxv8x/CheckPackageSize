package com.smd.checkpackagesize.mixin;

import com.smd.checkpackagesize.diagnostics.DiagnosticHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.concurrent.Callable;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @ModifyVariable(method = "addScheduledTask(Ljava/util/concurrent/Callable;)Lcom/google/common/util/concurrent/ListenableFuture;",
            at = @At("HEAD"), argsOnly = true)
    private <V> Callable<V> checkPackageSize$wrapScheduled(Callable<V> callable) {
        return DiagnosticHooks.wrapScheduled(callable);
    }
}
