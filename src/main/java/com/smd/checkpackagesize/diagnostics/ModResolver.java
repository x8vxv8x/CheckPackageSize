package com.smd.checkpackagesize.diagnostics;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.File;
import java.net.URI;
import java.security.CodeSource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ModResolver {

    private static final ConcurrentMap<Class<?>, String> CACHE = new ConcurrentHashMap<>();

    private ModResolver() {
    }

    public static String resolve(Class<?> type) {
        if (type == null) {
            return "unknown";
        }
        return CACHE.computeIfAbsent(type, ModResolver::resolveUncached);
    }

    public static String resolveClassName(String className) {
        if (className == null || className.isEmpty()) {
            return "unknown";
        }
        if (className.startsWith("net.minecraft.")) {
            return "minecraft";
        }
        if (className.startsWith("net.minecraftforge.")) {
            return "forge";
        }
        try {
            return resolve(Class.forName(className, false, ModResolver.class.getClassLoader()));
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String resolveUncached(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("net.minecraft.")) {
            return "minecraft";
        }
        if (name.startsWith("net.minecraftforge.")) {
            return "forge";
        }
        if (name.startsWith("com.smd.checkpackagesize.")) {
            return "checkpackagesize";
        }

        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                URI uri = source.getLocation().toURI();
                File location = new File(uri).getCanonicalFile();
                for (ModContainer container : Loader.instance().getActiveModList()) {
                    File modSource = container.getSource();
                    if (modSource != null && location.equals(modSource.getCanonicalFile())) {
                        return container.getModId();
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to unknown. Diagnostics must never break networking.
        }
        return "unknown";
    }
}
