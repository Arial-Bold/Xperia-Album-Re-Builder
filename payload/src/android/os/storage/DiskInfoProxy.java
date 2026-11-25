package android.os.storage;

/** Replaces Sony's DiskInfoProxy (hidden android.os.storage.DiskInfo): SD card or USB drive. */
public class DiskInfoProxy {
    private final StorageVolume mVolume;

    private DiskInfoProxy(StorageVolume v) { mVolume = v; }

    static DiskInfoProxy createProxy(Object v) { return new DiskInfoProxy(v instanceof StorageVolume ? (StorageVolume) v : null); }

    public boolean isSd() { return mVolume != null && mVolume.isRemovable() && !FpStorage.isUsb(mVolume); }

    public boolean isUsb() { return mVolume != null && mVolume.isRemovable() && FpStorage.isUsb(mVolume); }
}
