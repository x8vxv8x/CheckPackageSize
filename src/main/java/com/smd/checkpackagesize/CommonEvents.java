package com.smd.checkpackagesize;

import com.smd.checkpackagesize.diagnostics.DiagnosticsManager;
import com.smd.checkpackagesize.diagnostics.TrafficReport;
import com.smd.checkpackagesize.network.NetworkBridge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class CommonEvents {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            DiagnosticsManager.tick();
            TrafficReport report = DiagnosticsManager.pollCompleted();
            if (report != null) {
                NetworkBridge.onServerReport(report);
            }
        }
    }
}
