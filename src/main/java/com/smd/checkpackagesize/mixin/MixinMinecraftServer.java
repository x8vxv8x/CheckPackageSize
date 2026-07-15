package com.smd.checkpackagesize.mixin;

import com.smd.checkpackagesize.diagnostics.DiagnosticHooks;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.concurrent.Callable;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {

    @ModifyVariable(method = "callFromMainThread", at = @At("HEAD"), argsOnly = true)
    private <V> Callable<V> checkPackageSize$wrapScheduled(Callable<V> callable) {
        return DiagnosticHooks.wrapScheduled(callable);
    }
}
