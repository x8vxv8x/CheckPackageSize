package com.smd.checkpackagesize.diagnostics;

import java.util.List;

/** A bounded, payload-free snapshot of the code path that submitted an outbound packet. */
public record CallerTrace(String pathKey, String displayClass, String displayMethod,
                          int displayLine, List<String> frames) {

    public CallerTrace {
        pathKey = value(pathKey, "unknown");
        displayClass = value(displayClass, "unknown");
        displayMethod = value(displayMethod, "unknown");
        frames = frames == null ? List.of() : List.copyOf(frames);
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
