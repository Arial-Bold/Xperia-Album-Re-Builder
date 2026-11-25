package android.os.storage;

import android.content.Context;
import java.io.File;
import java.util.Locale;

/** Shared helpers for the storage proxies, using public APIs only. */
public final class FpStorage {
    static volatile Context context;

    private FpStorage() { }

    /** Called when Album starts, so volume descriptions can be read. */
    public static void init(Context c) { if (c != null) context = c.getApplicationContext() != null ? c.getApplicationContext() : c; }

    static File directory(StorageVolume v) {
        try {
            File d = v.getDirectory();                       // Android 11+
            if (d != null) return d;
        } catch (Throwable ignored) { }
        try {                                                 // Android 10 and older: the old (then allowed) method
            Object p = StorageVolume.class.getMethod("getPath").invoke(v);
            return p == null ? null : new File(p.toString());
        } catch (Throwable e) {
            return null;
        }
    }

    static boolean mounted(StorageVolume v) {
        String s = v.getState();
        return "mounted".equals(s) || "mounted_ro".equals(s);
    }

    /** Android names removable volumes "… SD card" or "… USB drive"; that's the only public hint. */
    static boolean isUsb(StorageVolume v) {
        try {
            String d = context != null ? v.getDescription(context) : null;
            return d != null && d.toLowerCase(Locale.ROOT).contains("usb");
        } catch (Throwable e) {
            return false;
        }
    }
}
