package com.smd.checkpackagesize.diagnostics;

import net.minecraftforge.fml.relauncher.Side;

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
        ENTRIES.computeIfAbsent(channel, ignored -> new AtomicReferenceArray<>(256))
                .set(discriminator & 0xFF, new Entry(modId, channel, messageClass.getName(),
                        handlerClass == null ? "-" : handlerClass.getName(), direction));
    }

    public static Entry find(String channel, int discriminator) {
        AtomicReferenceArray<Entry> entries = ENTRIES.get(channel == null ? "" : channel);
        return entries == null ? null : entries.get(discriminator & 0xFF);
    }

    public static final class Entry {
        public final String modId;
        public final String messageClass;
        public final String handlerClass;
        public final String direction;
        private final PacketIdentity c2sIdentity;
        private final PacketIdentity s2cIdentity;

        private Entry(String modId, String channel, String messageClass, String handlerClass, String direction) {
            this.modId = modId;
            this.messageClass = messageClass;
            this.handlerClass = handlerClass;
            this.direction = direction;
            this.c2sIdentity = new PacketIdentity("C2S", modId, channel, messageClass, handlerClass);
            this.s2cIdentity = new PacketIdentity("S2C", modId, channel, messageClass, handlerClass);
        }

        public PacketIdentity identity(String actualDirection) {
            return "C2S".equals(actualDirection) ? c2sIdentity : s2cIdentity;
        }
    }
}
