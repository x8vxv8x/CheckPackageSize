package com.smd.checkpackagesize.client;

import com.smd.checkpackagesize.Reference;
import com.smd.checkpackagesize.diagnostics.DiagnosticsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

@SideOnly(Side.CLIENT)
public final class ClientDiagnostics {

    private static final KeyBinding OPEN_KEY = new KeyBinding("打开网络诊断", Keyboard.KEY_F8, Reference.MOD_NAME);

    private ClientDiagnostics() {
    }

    public static void initialize() {
        ClientRegistry.registerKeyBinding(OPEN_KEY);
        MinecraftForge.EVENT_BUS.register(new ClientDiagnostics());
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        if (OPEN_KEY.isPressed()) {
            Minecraft.getMinecraft().displayGuiScreen(new DiagnosticsScreen());
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            DiagnosticsManager.tick();
        }
    }
}
