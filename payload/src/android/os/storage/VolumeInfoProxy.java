package android.os.storage;

import java.io.File;

/**
 * Replaces Sony's VolumeInfoProxy (hidden android.os.storage.VolumeInfo) with a public
 * StorageVolume underneath. Types and states use VolumeInfo's values, which Album compares against.
 */
public class VolumeInfoProxy {
    static final int TYPE_PUBLIC = 0, TYPE_PRIVATE = 1, TYPE_EMULATED = 2;
    static final int STATE_UNMOUNTED = 0, STATE_MOUNTED = 2, STATE_MOUNTED_READ_ONLY = 3, STATE_REMOVED = 7;

    private final StorageVolume mVolume;

    private VolumeInfoProxy(StorageVolume v) { mVolume = v; }

    static VolumeInfoProxy createProxy(Object v) {
        return new VolumeInfoProxy(v instanceof StorageVolume ? (StorageVolume) v : null);
    }

    /** The disk, for SD cards and USB drives; null for the phone's own storage (as before). */
    public DiskInfoProxy getDisk() {
        if (mVolume == null || !mVolume.isRemovable()) return null;
        return DiskInfoProxy.createProxy(mVolume);
    }

    public String getEnvironmentForState(int state) {
        switch (state) {
            case STATE_MOUNTED: return "mounted";
            case STATE_MOUNTED_READ_ONLY: return "mounted_ro";
            case STATE_REMOVED: return "removed";
            default: return "unmounted";
        }
    }

    public File getPath() { return mVolume == null ? null : FpStorage.directory(mVolume); }

    public File getPathForUser(int user) { return getPath(); }

    public int getState() {
        if (mVolume == null) return STATE_UNMOUNTED;
        String s = mVolume.getState();
        if ("mounted".equals(s)) return STATE_MOUNTED;
        if ("mounted_ro".equals(s)) return STATE_MOUNTED_READ_ONLY;
        if ("removed".equals(s)) return STATE_REMOVED;
        return STATE_UNMOUNTED;
    }

    public int getType() {
        if (mVolume == null) return TYPE_PRIVATE;
        if (mVolume.isEmulated()) return TYPE_EMULATED;
        return mVolume.isRemovable() ? TYPE_PUBLIC : TYPE_PRIVATE;
    }

    public boolean isEmulatedType(int type) { return type == TYPE_EMULATED; }

    public boolean isPublicType(int type) { return type == TYPE_PUBLIC; }

    public boolean isMountedWritable() { return getState() == STATE_MOUNTED; }
}
