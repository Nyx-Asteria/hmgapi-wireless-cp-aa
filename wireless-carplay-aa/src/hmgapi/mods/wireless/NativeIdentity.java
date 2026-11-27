package hmgapi.mods.wireless;

/** JNI boundary for the verified libiap2session.so identification serializer hook. */
final class NativeIdentity {
    private NativeIdentity() {}
    /** Load through Runtime so HMG-API can apply the queued in-memory recipe. */
    static void loadTarget() { System.load("/system/lib/libiap2session.so"); }
    static void load() { System.load("/system/lib/libcarplayidentity.so"); }
    static native int configure(byte[] displayName, int engineType, int dcConnector,
            int dcPowerW, int acConnector, int acPowerW, byte[] themeIdentity, boolean legacyTheme);
    static native int shutdown();
    static native boolean identityBuilt();
    // Most recent successful builder: engine=1, theme=2, name=4.
    static native int identityEvidence();
    // Atomic observation: identity bits 0-2, capability bits 3-4, payload bit 5, aggregate bit 6; -1 unavailable.
    static native int negotiationEvidence();
    static native int startSession(byte[] message);
    static native int startSessionStatus(int which);
}
