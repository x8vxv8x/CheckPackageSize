package com.smd.checkpackagesize.diagnostics;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.common.Loader;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class MessageRegistry {

    private static final ConcurrentMap<String, AtomicReferenceArray<Entry>> ENTRIES = new ConcurrentHashMap<>();

    private MessageRegistry() {
    }

    public static void register(String channel, int discriminator, Class<?> messageClass, Class<?> handlerClass, Side receivingSide) {
        if (channel == null || messageClass == null) {
            return;
        }
        String direction = receivingSide == Side.SERVER ? "C2S" : "S2C";
        String modId = ModResolver.resolve(messageClass);
        if ("unknown".equals(modId) && handlerClass != null) modId = ModResolver.resolve(handlerClass);
        if ("unknown".equals(modId) && Loader.isModLoaded(channel)) modId = channel;
        registerDescriptor(channel, discriminator, modId, messageClass.getName(),
                handlerClass == null ? "-" : handlerClass.getName(), direction);
    }

    static void registerDescriptor(String channel, int discriminator, String modId, String messageClass,
                                   String handlerClass, String direction) {
        ENTRIES.computeIfAbsent(channel, ignored -> new AtomicReferenceArray<>(256))
                .set(discriminator & 0xFF, new Entry(modId, channel, messageClass, handlerClass, direction, discriminator));
    }

    public static Entry find(String channel, int discriminator) {
        if (discriminator < 0 || discriminator > 255) return null;
        AtomicReferenceArray<Entry> entries = ENTRIES.get(channel == null ? "" : channel);
        return entries == null ? null : entries.get(discriminator & 0xFF);
    }

    public static boolean hasChannel(String channel) {
        return ENTRIES.containsKey(channel == null ? "" : channel);
    }

    public static final class Entry {
        public final String modId;
        public final String messageClass;
        public final String handlerClass;
        public final String direction;
        private final PacketIdentity c2sIdentity;
        private final PacketIdentity s2cIdentity;

        private Entry(String modId, String channel, String messageClass, String handlerClass, String direction,
                      int discriminator) {
            this.modId = modId;
            this.messageClass = messageClass;
            this.handlerClass = handlerClass;
            this.direction = direction;
            this.c2sIdentity = new PacketIdentity("C2S", modId, channel, messageClass, handlerClass, discriminator);
            this.s2cIdentity = new PacketIdentity("S2C", modId, channel, messageClass, handlerClass, discriminator);
        }

        public PacketIdentity identity(String actualDirection) {
            return "C2S".equals(actualDirection) ? c2sIdentity : s2cIdentity;
        }
    }
}
