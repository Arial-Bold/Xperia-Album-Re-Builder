package com.fpmod.album;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Sorts Album by each photo's EXIF "date taken".
 *
 * Android records a date taken for every photo, but leaves it empty when the EXIF date has no
 * time zone and the file's modified date is more than a day away from it (common for copied or
 * transferred photos); those photos then sort by the date they were copied. Sony's own phones
 * fixed this with an extra "somcdatetaken" column, and Album asks getDateTakenColumnName() which
 * date to use. This class answers that (and the few other date expressions in Album's queries)
 * with an SQL CASE that gives the EXIF date for exactly those photos and the usual date for the rest.
 *
 * EXIF dates are read once per photo and kept in a small cache file, so the expression is ready
 * as soon as Album starts. Only photos whose recorded date is missing, or more than 36 hours off
 * (beyond any time-zone difference), are overridden.
 */
public final class ExifDates {
    static final String TAG = "FpAlbumDates";
    static final String PREFS = "fpmod_album";
    static final String KEY_SORT = "fpmod_sort_exif";
    private static final String CACHE = "fpmod_exif_dates.bin";
    private static final int CACHE_VERSION = 1;
    private static final long TOLERANCE_MS = 36L * 3600 * 1000;
    private static final int MAX_OVERRIDES = 8000;            // keeps the SQL a sensible size
    private static final String ORIG_DATE = "IFNULL (datetaken,date_modified*1000)";

    private static Context app;
    private static volatile String lookup;                     // SQL giving the EXIF date for overridden ids, else NULL
    private static volatile boolean usable;                    // Android accepted a test query with it
    private static volatile List<Target> targets = new ArrayList<>();
    private static final Object scanLock = new Object();

    /** A photo whose recorded date is missing or wrong, with its EXIF date. */
    static final class Target {
        final long id; final String path; final long exifMs; final long recordedMs; final boolean missing;
        Target(long id, String path, long exifMs, long recordedMs, boolean missing) {
            this.id = id; this.path = path; this.exifMs = exifMs; this.recordedMs = recordedMs; this.missing = missing;
        }
    }

    /** Cached EXIF date of one photo (by MediaStore id), valid while its modified date is unchanged. */
    private static final class Entry {
        final long modified; final long exifMs;
        Entry(long modified, long exifMs) { this.modified = modified; this.exifMs = exifMs; }
    }

    private ExifDates() { }

    // ------------------------------------------------------------- start-up

    /** Called from AlbumApplication.onCreate. Loads the cached dates, then rescans in the background. */
    public static void init(Context c) {
        try {
            if (c == null || app != null) return;
            android.os.storage.FpStorage.init(c);
            app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            VideoSettings.init(app);
            // accent colours in every process: the video player and its subtitle screen run in their own
            if (app instanceof android.app.Application) AccentColours.install((android.app.Application) app);
            if (!isMainProcess(app)) return;
            AppIconSettings.install(app);
            Map<Long, Entry> cache = loadCache();
            rebuildFromCache(cache, null);
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        android.os.Process.setThreadPriority(10);   // background
                        Thread.sleep(4000);                         // let Album start first
                        scan(app);
                    } catch (Throwable e) {
                        Log.w(TAG, "background scan failed", e);
                    }
                }
            }, "FpAlbumDates").start();
        } catch (Throwable e) {
            Log.w(TAG, "init failed", e);
        }
    }

    private static boolean isMainProcess(Context c) {
        try {
            String p = android.app.Application.getProcessName();
            return p == null || p.equals(c.getPackageName());
        } catch (Throwable e) {
            return true;
        }
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean sortEnabled() {
        return app == null || prefs(app).getBoolean(KEY_SORT, true);
    }

    // ------------------------------------------------- the date in Album's SQL

    private static boolean active() {
        return usable && lookup != null && sortEnabled();
    }

    /** Album's date column (getDateTakenColumnName, and the main grid's projection). */
    public static String column(String original) {
        try {
            if (active() && "datetaken".equals(original)) return "IFNULL(" + lookup + ",datetaken) AS datetaken";
        } catch (Throwable ignored) { }
        return original;
    }

    /** Album's sort orders and folder-cover selections that use IFNULL(datetaken, date_modified*1000). */
    public static String sql(String original) {
        try {
            if (original != null && active() && original.contains(ORIG_DATE)) {
                return original.replace(ORIG_DATE, "IFNULL(" + lookup + ",IFNULL(datetaken,date_modified*1000))");
            }
        } catch (Throwable ignored) { }
        return original;
    }

    // ---------------------------------------------------------------- scan

    /**
     * Reads EXIF dates for photos that are new or changed since the last scan, updates the cache,
     * and works out which photos need their date overridden. Returns those photos.
     */
    static List<Target> scan(Context c) {
        synchronized (scanLock) {
            Map<Long, Entry> cache = loadCache();
            Map<Long, Entry> fresh = new HashMap<>();
            List<Target> found = new ArrayList<>();
            ContentResolver cr = c.getContentResolver();
            Uri uri = Uri.parse("content://media/external/images/media");
            Cursor q = null;
            int read = 0;
            try {
                q = cr.query(uri, new String[]{"_id", "_data", "datetaken", "date_modified", "mime_type"}, null, null, null);
                if (q == null) return targets;
                while (q.moveToNext()) {
                    long id = q.getLong(0);
                    String path = q.getString(1);
                    boolean missing = q.isNull(2);
                    long taken = missing ? 0 : q.getLong(2);
                    long modified = q.getLong(3);
                    String mime = q.isNull(4) ? "" : q.getString(4);
                    Entry e = cache.get(id);
                    if (e == null || e.modified != modified) {
                        e = new Entry(modified, readExifDate(path, mime));
                        read++;
                    }
                    fresh.put(id, e);
                    if (e.exifMs <= 0) continue;
                    long recorded = missing ? modified * 1000L : taken;
                    if (missing || Math.abs(recorded - e.exifMs) > TOLERANCE_MS) {
                        found.add(new Target(id, path, e.exifMs, recorded, missing));
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "scan failed", e);
                return targets;
            } finally {
                if (q != null) try { q.close(); } catch (Throwable ignored) { }
            }
            saveCache(fresh);
            targets = found;
            Log.i(TAG, "Scanned " + fresh.size() + " photos (" + read + " read), " + found.size() + " sorted by EXIF date");
            build(found);
            return found;
        }
    }

    static List<Target> lastTargets() { return targets; }

    private static void rebuildFromCache(Map<Long, Entry> cache, List<Target> unused) {
        // Until the first scan of this session, use the overrides from last time (the cache only
        // keeps photos' EXIF dates; which ones need overriding was saved alongside).
        List<Target> t = loadOverrides();
        targets = t;
        build(t);
    }

    /**
     * Builds the lookup and checks Android accepts it in a query like Album's. The lookup is a
     * balanced tree of CASE expressions on _id, so each photo needs about log2(n) comparisons
     * rather than one per overridden photo.
     */
    private static void build(List<Target> t) {
        if (t.isEmpty()) { lookup = null; usable = false; saveOverrides(t); return; }
        int n = Math.min(t.size(), MAX_OVERRIDES);
        long[][] pairs = new long[n][];
        for (int i = 0; i < n; i++) pairs[i] = new long[]{t.get(i).id, t.get(i).exifMs};
        java.util.Arrays.sort(pairs, new java.util.Comparator<long[]>() {
            @Override public int compare(long[] a, long[] b) { return Long.compare(a[0], b[0]); }
        });
        StringBuilder sb = new StringBuilder(64 + n * 40);
        tree(sb, pairs, 0, n);
        String expr = sb.toString();
        boolean ok = false;
        Cursor q = null;
        try {
            q = app.getContentResolver().query(Uri.parse("content://media/external/images/media"),
                    new String[]{"_id", "IFNULL(" + expr + ",datetaken) AS datetaken"}, null, null,
                    "IFNULL(" + expr + ",IFNULL(datetaken,date_modified*1000)) DESC");
            if (q != null) { q.moveToFirst(); ok = true; }
        } catch (Throwable e) {
            Log.w(TAG, "Android didn't accept the date expression; using the normal order", e);
        } finally {
            if (q != null) try { q.close(); } catch (Throwable ignored) { }
        }
        lookup = expr;
        usable = ok;
        saveOverrides(t);
    }

    /** CASE tree over pairs[from, to) sorted by id: small leaves, split by id range above that. */
    static void tree(StringBuilder sb, long[][] pairs, int from, int to) {
        if (to - from <= 8) {
            sb.append("(CASE _id");
            for (int i = from; i < to; i++) sb.append(" WHEN ").append(pairs[i][0]).append(" THEN ").append(pairs[i][1]);
            sb.append(" END)");
            return;
        }
        int mid = (from + to) >>> 1;
        sb.append("(CASE WHEN _id<").append(pairs[mid][0]).append(" THEN ");
        tree(sb, pairs, from, mid);
        sb.append(" ELSE ");
        tree(sb, pairs, mid, to);
        sb.append(" END)");
    }

    // ---------------------------------------------------------------- EXIF

    private static final SimpleDateFormat LOCAL = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);

    /** EXIF DateTimeOriginal as UTC milliseconds (local time zone if the file has no offset), or -1. */
    static long readExifDate(String path, String mime) {
        try {
            if (path == null) return -1;
            String m = mime == null ? "" : mime.toLowerCase(Locale.US);
            if (!(m.equals("image/jpeg") || m.equals("image/heic") || m.equals("image/heif") || m.equals("image/png")
                    || m.equals("image/webp") || m.startsWith("image/x-") || m.equals("image/dng"))) return -1;
            File f = new File(path);
            if (!f.isFile()) return -1;
            ExifInterface x = new ExifInterface(path);
            String dt = x.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (dt == null) return -1;
            dt = dt.trim();
            if (dt.length() < 19 || dt.startsWith("0000")) return -1;
            String off = x.getAttribute("OffsetTimeOriginal");
            long ms;
            synchronized (LOCAL) {
                if (off != null && off.trim().matches("[+-]\\d\\d:\\d\\d")) {
                    SimpleDateFormat f2 = new SimpleDateFormat("yyyy:MM:dd HH:mm:ssXXX", Locale.US);
                    java.util.Date d = f2.parse(dt.substring(0, 19) + off.trim(), new ParsePosition(0));
                    if (d == null) return -1;
                    ms = d.getTime();
                } else {
                    LOCAL.setTimeZone(TimeZone.getDefault());
                    java.util.Date d = LOCAL.parse(dt.substring(0, 19), new ParsePosition(0));
                    if (d == null) return -1;
                    ms = d.getTime();
                }
            }
            // sanity: 1990 .. tomorrow
            if (ms < 631152000000L || ms > System.currentTimeMillis() + 86400000L) return -1;
            return ms;
        } catch (Throwable e) {
            return -1;
        }
    }

    // --------------------------------------------------------------- files

    private static Map<Long, Entry> loadCache() {
        Map<Long, Entry> m = new HashMap<>();
        if (app == null) return m;
        File f = new File(app.getFilesDir(), CACHE);
        if (!f.isFile()) return m;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != CACHE_VERSION) return m;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long id = in.readLong(), mod = in.readLong(), ex = in.readLong();
                m.put(id, new Entry(mod, ex));
            }
        } catch (Throwable e) {
            m.clear();
        }
        return m;
    }

    private static void saveCache(Map<Long, Entry> m) {
        if (app == null) return;
        File f = new File(app.getFilesDir(), CACHE), tmp = new File(app.getFilesDir(), CACHE + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(CACHE_VERSION);
            out.writeInt(m.size());
            for (Map.Entry<Long, Entry> e : m.entrySet()) {
                out.writeLong(e.getKey()); out.writeLong(e.getValue().modified); out.writeLong(e.getValue().exifMs);
            }
        } catch (Throwable e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static final String OVERRIDES = "fpmod_exif_overrides.bin";

    private static void saveOverrides(List<Target> t) {
        if (app == null) return;
        File f = new File(app.getFilesDir(), OVERRIDES), tmp = new File(app.getFilesDir(), OVERRIDES + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(CACHE_VERSION);
            out.writeInt(t.size());
            for (Target x : t) {
                out.writeLong(x.id); out.writeLong(x.exifMs); out.writeLong(x.recordedMs); out.writeBoolean(x.missing);
                out.writeUTF(x.path == null ? "" : x.path);
            }
        } catch (Throwable e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static List<Target> loadOverrides() {
        List<Target> t = new ArrayList<>();
        if (app == null) return t;
        File f = new File(app.getFilesDir(), OVERRIDES);
        if (!f.isFile()) return t;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != CACHE_VERSION) return t;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long id = in.readLong(), ex = in.readLong(), rec = in.readLong();
                boolean miss = in.readBoolean();
                String p = in.readUTF();
                t.add(new Target(id, p, ex, rec, miss));
            }
        } catch (Throwable e) {
            t.clear();
        }
        return t;
    }
}
