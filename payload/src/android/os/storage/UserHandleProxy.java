package android.os.storage;

/** Replaces Sony's UserHandleProxy (hidden UserHandle.myUserId) with a public equivalent. */
public class UserHandleProxy {
    public UserHandleProxy() { }

    public static int myUserId() {
        try { return android.os.Process.myUserHandle().hashCode(); }   // UserHandle.hashCode() is the user id
        catch (Throwable e) { return android.os.Process.myUid() / 100000; }
    }
}
