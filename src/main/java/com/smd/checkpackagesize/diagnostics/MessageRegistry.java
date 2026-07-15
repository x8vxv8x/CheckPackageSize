package com.smd.checkpackagesize.diagnostics;

import net.minecraftforge.fml.relauncher.Side;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MessageRegistry {

    private static final ConcurrentMap<Key, Entry> ENTRIES = new ConcurrentHashMap<>();

    private MessageRegistry() {
    }

    public static void register(String channel, int discriminator, Class<?> messageClass, Class<?> handlerClass, Side receivingSide) {
        if (channel == null || messageClass == null) {
            return;
        }
        String direction = receivingSide == Side.SERVER ? "C2S" : "S2C";
        String modId = ModResolver.resolve(messageClass);
        ENTRIES.put(new Key(channel, discriminator), new Entry(modId, messageClass.getName(),
                handlerClass == null ? "-" : handlerClass.getName(), direction));
    }

    public static Entry find(String channel, int discriminator) {
        return ENTRIES.get(new Key(channel, discriminator));
    }

    public static final class Entry {
        public final String modId;
        public final String messageClass;
        public final String handlerClass;
        public final String direction;

        private Entry(String modId, String messageClass, String handlerClass, String direction) {
            this.modId = modId;
            this.messageClass = messageClass;
            this.handlerClass = handlerClass;
            this.direction = direction;
        }
    }

    private static final class Key {
        private final String channel;
        private final int discriminator;

        private Key(String channel, int discriminator) {
            this.channel = channel == null ? "" : channel;
            this.discriminator = discriminator & 0xFF;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Key)) {
                return false;
            }
            Key other = (Key) object;
            return discriminator == other.discriminator && channel.equals(other.channel);
        }

        @Override
        public int hashCode() {
            return 31 * channel.hashCode() + discriminator;
        }
    }
}
