package com.jbeats;

/**
 * Initialized at native-image build time so the version string
 * gets baked into the binary rather than resolved at runtime.
 */
public final class Version {
    public static final String VALUE = System.getProperty("jbeats.version", "dev");

    private Version() {}
}
