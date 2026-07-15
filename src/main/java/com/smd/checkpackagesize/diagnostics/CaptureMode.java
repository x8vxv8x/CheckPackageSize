package com.smd.checkpackagesize.diagnostics;

import java.util.EnumSet;

public enum CaptureMode {
    CLIENT_ONLY(EnumSet.of(Endpoint.CLIENT), false),
    SERVER_ONLY(EnumSet.of(Endpoint.SERVER), false),
    SINGLEPLAYER_COMBINED(EnumSet.of(Endpoint.CLIENT, Endpoint.SERVER), true);

    private final EnumSet<Endpoint> endpoints;
    private final boolean localTheoretical;

    CaptureMode(EnumSet<Endpoint> endpoints, boolean localTheoretical) {
        this.endpoints = endpoints;
        this.localTheoretical = localTheoretical;
    }

    public boolean includes(Endpoint endpoint) {
        return endpoints.contains(endpoint);
    }

    public boolean localTheoretical() {
        return localTheoretical;
    }
}
