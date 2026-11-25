package com.fpmod.album;

/**
 * Album checks that hidden Android storage methods exist before it offers SD-card features
 * ("dependencies" STORAGE_MANAGER_*). Android 11+ blocks those methods, so the checks fail and
 * SD-card access is never requested. The storage proxies now use public APIs instead, so those
 * checks can report available.
 */
public final class SdAccess {
    private SdAccess() { }

    public static boolean storageReady(Object dependency) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 30) return false;
            return dependency instanceof Enum && ((Enum<?>) dependency).name().startsWith("STORAGE_MANAGER_");
        } catch (Throwable e) {
            return false;
        }
    }
}
