package android.os.storage;

/** Replaces Sony's StorageVolumeProxy (hidden StorageVolume.getPath) with public APIs. */
public class StorageVolumeProxy {
    private final StorageVolume mStorageVolume;

    private StorageVolumeProxy(StorageVolume v) { mStorageVolume = v; }

    public static StorageVolumeProxy createProxy(StorageVolume v) { return new StorageVolumeProxy(v); }

    public String getPath() { return mStorageVolume == null ? null : StorageManagerProxy.path(mStorageVolume); }

    public String getUuid() { return mStorageVolume == null ? null : mStorageVolume.getUuid(); }
}
