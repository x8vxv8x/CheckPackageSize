package com.smd.checkpackagesize.diagnostics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

final class ForgeIdentityResolver {

    private static final ConcurrentMap<String, AtomicReferenceArray<PacketIdentity>> UNKNOWN = new ConcurrentHashMap<>();

    private ForgeIdentityResolver() {
    }

    static PacketIdentity resolve(String channel, int discriminator, String direction) {
        MessageRegistry.Entry entry = MessageRegistry.find(channel, discriminator);
        if (entry != null) return entry.identity(direction);

        String channelName = channel == null ? "" : channel;
        AtomicReferenceArray<PacketIdentity> identities = UNKNOWN.computeIfAbsent(channelName,
                ignored -> new AtomicReferenceArray<>(514));
        int slot = (discriminator + 1) * 2 + ("C2S".equals(direction) ? 0 : 1);
        PacketIdentity identity = identities.get(slot);
        if (identity != null) return identity;
        String modId = channelName.isEmpty() ? "forge" : channelName;
        PacketIdentity created = new PacketIdentity(direction, modId, channelName,
                "net.minecraftforge.fml.common.network.internal.FMLProxyPacket", "-", discriminator);
        if (identities.compareAndSet(slot, null, created)) return created;
        return identities.get(slot);
    }
}
