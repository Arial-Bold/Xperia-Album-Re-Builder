package android.media; public class MediaScannerConnection { public static void scanFile(android.content.Context c, String[] paths, String[] mimes, OnScanCompletedListener l){}
 public interface OnScanCompletedListener { void onScanCompleted(String path, android.net.Uri uri); } }
