package com.smd.checkpackagesize.proxy;

import com.smd.checkpackagesize.client.ClientDiagnostics;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        ClientDiagnostics.initialize();
    }
}
