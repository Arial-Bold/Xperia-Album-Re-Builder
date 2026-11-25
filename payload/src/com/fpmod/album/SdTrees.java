package com.fpmod.album;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.util.List;

/**
 * SD card write access through folder permissions.
 *
 * Sony's Album asks for access to the whole SD card and only accepts the card's top folder. Since
 * Android 11 that top folder can't be chosen, so the request always failed. Here access is given
 * per folder (DCIM, Pictures…): Album asks for the top-level folder that holds the photo, opens the
 * folder picker right there, and finds the matching permission for each file it changes.
 */
public final class SdTrees {
    private static final String AUTHORITY = "com.android.externalstorage.documents";
    private static final String EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI";

    private SdTrees() { }

    /** "/storage/1234-5678/DCIM/Camera/a.jpg" -> {"1234-5678", "DCIM/Camera/a.jpg"}; null if not on a card. */
    static String[] split(String path) {
        if (path == null || !path.startsWith("/storage/")) return null;
        String rest = path.substring("/storage/".length());
        int slash = rest.indexOf('/');
        String volume = slash < 0 ? rest : rest.substring(0, slash);
        if (volume.isEmpty() || volume.equals("emulated") || volume.equals("self")) return null;
        String rel = slash < 0 ? "" : rest.substring(slash + 1);
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        return new String[] {volume, rel};
    }

    /** The granted folder (tree URI) that contains this path, or null. */
    static Uri coveringTree(Context c, String path) {
        String[] p = split(path);
        if (p == null) return null;
        try {
            List<UriPermission> perms = c.getContentResolver().getPersistedUriPermissions();
            for (UriPermission up : perms) {
                if (!up.isWritePermission()) continue;
                Uri u = up.getUri();
                if (!AUTHORITY.equals(u.getAuthority())) continue;
                String id;
                try { id = DocumentsContract.getTreeDocumentId(u); } catch (Throwable e) { continue; }
                int colon = id.indexOf(':');
                if (colon < 0 || !id.substring(0, colon).equals(p[0])) continue;
                String root = id.substring(colon + 1);
                while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
                if (root.isEmpty() || p[1].equals(root) || p[1].startsWith(root + "/")) return u;
            }
        } catch (Throwable e) {
            Log.w(ExifDates.TAG, "couldn't read folder permissions", e);
        }
        return null;
    }

    /** True if Album can change files at this path (not on a card, or a granted folder holds it). */
    public static boolean hasAccess(Context c, String path) {
        return split(path) == null || coveringTree(c, path) != null;
    }

    /** Hook for Utils.requestSdAccessPermissionIfNecessary: whether to ask for access for this path. */
    public static boolean needAccess(Context c, String path) {
        return !hasAccess(c, path);
    }

    /** Replaces DocumentProviderUtils.convertTreeUri: the document URI for a file on the card. */
    public static Uri documentUri(Context c, File f) {
        if (f == null) return null;
        String path = f.getAbsolutePath();
        Uri tree = coveringTree(c, path);
        if (tree == null) return null;
        String[] p = split(path);
        return DocumentsContract.buildDocumentUriUsingTree(tree, p[0] + ":" + p[1]);
    }

    /** Hook for the permission screen's result: accept a folder on the card that holds the photo. */
    public static boolean accept(String granted, String cardRoot, String checkPath) {
        if (granted == null || cardRoot == null || !granted.startsWith(cardRoot)) return false;
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(granted));
            String root = id.substring(id.indexOf(':') + 1);
            while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
            String[] p = split(checkPath);
            return p == null || root.isEmpty() || p[1].equals(root) || p[1].startsWith(root + "/");
        } catch (Throwable e) {
            return false;
        }
    }

    /** The folder to ask for: the top-level folder on the card that holds the path ("DCIM"). */
    static String topFolder(String path) {
        String[] p = split(path);
        if (p == null || p[1].isEmpty()) return null;
        int slash = p[1].indexOf('/');
        return slash < 0 ? p[1] : p[1].substring(0, slash);
    }

    /**
     * Replaces Sony's "give access to the SD card" dialog: explains which folder is needed, then
     * opens the folder picker in that folder. Cancel ends the request.
     */
    public static void request(final Activity act, final Object volume, final int requestCode, final String checkPath) {
        try {
            final String[] p = split(checkPath);
            final String folder = topFolder(checkPath);
            String what = folder != null ? "the “" + folder + "” folder on your SD card" : "a folder on your SD card";
            new AlertDialog.Builder(act)
                    .setTitle("Allow access to the SD card")
                    .setMessage("To change photos and videos on the SD card, Album needs access to " + what + ".\n\n"
                            + "On the next screen, tap “Use this folder”, then Allow.")
                    .setCancelable(false)
                    .setPositiveButton("Next", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            try {
                                Intent i = ((android.os.storage.StorageVolume) volume).createOpenDocumentTreeIntent();
                                if (p != null && folder != null) {
                                    i.putExtra(EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTHORITY, p[0] + ":" + folder));
                                }
                                act.startActivityForResult(i, requestCode);
                            } catch (Throwable e) {
                                Log.w(ExifDates.TAG, "couldn't open the folder picker", e);
                                act.setResult(Activity.RESULT_CANCELED);
                                act.finish();
                            }
                        }
                    })
                    .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            act.setResult(Activity.RESULT_CANCELED);
                            act.finish();
                        }
                    })
                    .show();
        } catch (Throwable e) {
            Log.w(ExifDates.TAG, "couldn't ask for SD card access", e);
            act.setResult(Activity.RESULT_CANCELED);
            act.finish();
        }
    }
}
