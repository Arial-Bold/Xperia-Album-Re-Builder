package android.os.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces Sony's StorageManagerProxy, which reached the SD card through hidden Android methods
 * (getVolumeList, getVolumes, getVolumePath) that Android 11+ blocks for apps. This version uses
 * only public APIs (getStorageVolumes, StorageVolume.getDirectory), with the same methods.
 */
public class StorageManagerProxy {
    public enum StorageType { INTERNAL, EXTERNAL_CARD, EXTERNAL_USB, UNKNOWN }

    private final StorageManager mStorageManager;

    private StorageManagerProxy(StorageManager sm) { mStorageManager = sm; }

    public static StorageManagerProxy createProxy(StorageManager sm) { return new StorageManagerProxy(sm); }

    public StorageVolume[] getVolumeList() {
        try {
            List<StorageVolume> l = mStorageManager.getStorageVolumes();
            return l.toArray(new StorageVolume[0]);
        } catch (Throwable e) {
            return new StorageVolume[0];
        }
    }

    public String getVolumePath(StorageType type) {
        for (StorageVolume v : getVolumeList()) {
            if (type == StorageType.INTERNAL && v.isPrimary()) return path(v);
            if (type == StorageType.EXTERNAL_CARD && v.isRemovable() && !FpStorage.isUsb(v) && FpStorage.mounted(v)) return path(v);
            if (type == StorageType.EXTERNAL_USB && v.isRemovable() && FpStorage.isUsb(v) && FpStorage.mounted(v)) return path(v);
        }
        return null;
    }

    /** One VolumeInfoProxy per storage volume. */
    public List getVolumes() {
        List<VolumeInfoProxy> l = new ArrayList<>();
        for (StorageVolume v : getVolumeList()) l.add(VolumeInfoProxy.createProxy(v));
        return l;
    }

    static String path(StorageVolume v) {
        File d = FpStorage.directory(v);
        return d == null ? null : d.getPath();
    }
}
