package com.smd.checkpackagesize;

import com.smd.checkpackagesize.diagnostics.DiagnosticsManager;
import com.smd.checkpackagesize.network.NetworkBridge;
import com.smd.checkpackagesize.proxy.IProxy;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(   modid = Reference.MOD_ID,
        name = Reference.MOD_NAME,
        version = Reference.VERSION
)
public class CheckPackageSize {

    public static final Logger LOGGER = LogManager.getLogger(Reference.MOD_NAME);

    @SidedProxy(modId = Reference.MOD_ID, clientSide = "com.smd.checkpackagesize.proxy.ClientProxy",
                                          serverSide = "com.smd.checkpackagesize.proxy.CommonProxy")
    public static IProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        DiagnosticsManager.initialize(event.getModConfigurationDirectory().getParentFile());
        NetworkBridge.initialize();
        MinecraftForge.EVENT_BUS.register(new CommonEvents());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (event.getSide().isClient()) {
            try {
                Class.forName("com.smd.checkpackagesize.client.ClientDiagnostics")
                        .getMethod("initialize").invoke(null);
            } catch (ReflectiveOperationException exception) {
                throw new RuntimeException("Unable to initialize client diagnostics", exception);
            }
        }
    }

}
