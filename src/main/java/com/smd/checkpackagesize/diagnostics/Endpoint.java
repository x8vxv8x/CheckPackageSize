package com.smd.checkpackagesize.diagnostics;

public enum Endpoint {
    CLIENT,
    SERVER;

    public static Endpoint inbound(String direction) {
        return "S2C".equals(direction) ? CLIENT : SERVER;
    }

    public static Endpoint outbound(String direction) {
        return "C2S".equals(direction) ? CLIENT : SERVER;
    }
}
