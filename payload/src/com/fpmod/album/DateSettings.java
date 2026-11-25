package com.fpmod.album;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Photo settings (see SettingsLayout for the whole screen):
 *  - Sort by date taken: Album sorts photos by their EXIF date (see ExifDates).
 *  - Fix photo dates: corrects misplaced photos for every app, by setting each file's date to its
 *    EXIF date taken (as `exiftool "-FileModifyDate<DateTimeOriginal"` does) and asking Android to
 *    read the photo again. If Android won't let Album change a file's date, it asks permission and
 *    adds the time-zone tag to the photo's EXIF instead, which tells Android the exact time taken.
 */
public final class DateSettings {
    private static final String TAG = ExifDates.TAG;
    private static final int REQ_WRITE = 0x5ad7;
    private static final String CATEGORY_KEY = "fpmod_photo_dates";

    private DateSettings() { }

    /** Called from PrefsFragment.onCreateView after Sony's own setup: lays out the whole screen. */
    public static void addTo(final PreferenceFragment fragment) {
        try {
            SettingsLayout.apply(fragment);
        } catch (Throwable e) {
            Log.w(TAG, "couldn't lay out Settings", e);
        }
    }

    /** Sort by date taken and Fix photo dates, in the Photo section. */
    static void addTo(PreferenceGroup cat, final Activity act) {
        SwitchPreference sort = new SwitchPreference(act);
        sort.setKey(ExifDates.KEY_SORT);
        sort.setPersistent(false);
        sort.setOrder(1);
        sort.setTitle("Sort by date taken");
        sort.setSummary("Uses each photo’s own date taken (EXIF), so copied or transferred photos appear in the right place.");
        sort.setChecked(ExifDates.prefs(act).getBoolean(ExifDates.KEY_SORT, true));
        sort.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference p, Object v) {
                boolean on = Boolean.TRUE.equals(v);
                ExifDates.prefs(act).edit().putBoolean(ExifDates.KEY_SORT, on).apply();
                new AlertDialog.Builder(act).setTitle("Sort by date taken")
                        .setMessage("The new order shows the next time Album opens.")
                        .setPositiveButton("OK", null).show();
                return true;
            }
        });
        cat.addPreference(sort);

        Preference fix = new Preference(act);
        fix.setOrder(2);
        fix.setTitle("Fix photo dates");
        fix.setSummary("Corrects the saved date of photos whose date taken Android doesn’t know, so every app sorts them correctly.");
        fix.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference p) { check(act); return true; }
        });
        cat.addPreference(fix);
    }

    // --------------------------------------------------------------- check

    private static void check(final Activity act) {
        final ProgressDialog pd = ProgressDialog.show(act, "Fix photo dates", "Checking your photos’ dates…", true, false);
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                final List<ExifDates.Target> t = ExifDates.scan(act.getApplicationContext());
                ui.post(new Runnable() {
                    @Override public void run() {
                        dismiss(pd);
                        if (act.isFinishing()) return;
                        if (t.isEmpty()) {
                            message(act, "All your photos already have the right date taken.");
                            return;
                        }
                        int missing = 0;
                        for (ExifDates.Target x : t) if (x.missing) missing++;
                        String what = t.size() == 1 ? "1 photo has" : t.size() + " photos have";
                        new AlertDialog.Builder(act).setTitle("Fix photo dates")
                                .setMessage(what + " a date taken in its EXIF data that Android isn’t using"
                                        + (missing > 0 ? " (often after copying or transferring)" : "")
                                        + ", so " + (t.size() == 1 ? "it sorts" : "they sort") + " by the wrong date.\n\n"
                                        + "Fix sets each file’s date to its date taken. The pictures themselves aren’t changed.")
                                .setPositiveButton("Fix", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) { apply(act, t); }
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                });
            }
        }, "FpAlbumDatesCheck").start();
    }

    // --------------------------------------------------------------- apply

    private static void apply(final Activity act, final List<ExifDates.Target> t) {
        final ProgressDialog pd = ProgressDialog.show(act, "Fix photo dates", "Fixing " + t.size() + (t.size() == 1 ? " photo…" : " photos…"), true, false);
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                final List<ExifDates.Target> refused = new ArrayList<>();
                List<String> changed = new ArrayList<>();
                for (ExifDates.Target x : t) {
                    boolean ok = false;
                    try {
                        File f = new File(x.path);
                        ok = f.isFile() && f.setLastModified(x.exifMs) && Math.abs(f.lastModified() - x.exifMs) < 2000;
                    } catch (Throwable ignored) { }
                    if (ok) changed.add(x.path); else refused.add(x);
                }
                rescan(act, changed);
                final List<ExifDates.Target> left = ExifDates.scan(act.getApplicationContext());
                final int fixed = t.size() - countStill(t, left);
                ui.post(new Runnable() {
                    @Override public void run() {
                        dismiss(pd);
                        if (act.isFinishing()) return;
                        if (refused.isEmpty() || !exifWritable(refused)) {
                            finish(act, t.size(), fixed, refused.size());
                        } else {
                            askToTag(act, t.size(), fixed, refused);
                        }
                    }
                });
            }
        }, "FpAlbumDatesFix").start();
    }

    /** Android didn't let Album change some files' dates: offer the EXIF time-zone tag instead. */
    private static void askToTag(final Activity act, final int total, final int fixedSoFar, final List<ExifDates.Target> refused) {
        final List<ExifDates.Target> taggable = new ArrayList<>();
        for (ExifDates.Target x : refused) if (exifWritable(x.path)) taggable.add(x);
        String n = taggable.size() == 1 ? "1 photo" : taggable.size() + " photos";
        new AlertDialog.Builder(act).setTitle("Fix photo dates")
                .setMessage("Android didn’t let Album change the date of " + n + ".\n\n"
                        + "Album can fix " + (taggable.size() == 1 ? "it" : "them") + " another way instead: by adding the time zone to "
                        + (taggable.size() == 1 ? "its" : "their") + " date taken in the EXIF data, which tells Android exactly when "
                        + (taggable.size() == 1 ? "it was" : "they were") + " taken. The picture itself isn’t changed. "
                        + "Android will ask you to allow this.")
                .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { requestWrite(act, total, fixedSoFar, refused, taggable); }
                })
                .setNegativeButton("Skip", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { finish(act, total, fixedSoFar, refused.size()); }
                })
                .show();
    }

    private static void requestWrite(final Activity act, final int total, final int fixedSoFar,
                                     final List<ExifDates.Target> refused, final List<ExifDates.Target> taggable) {
        try {
            final List<Uri> uris = new ArrayList<>();
            for (ExifDates.Target x : taggable) uris.add(Uri.parse("content://media/external/images/media/" + x.id));
            PendingIntent pi = MediaStore.createWriteRequest(act.getContentResolver(), uris);
            final Application app = act.getApplication();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity a) {
                    if (a != act) return;
                    app.unregisterActivityLifecycleCallbacks(this);
                    int granted = act.checkUriPermission(uris.get(0), android.os.Process.myPid(), android.os.Process.myUid(),
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if (granted != 0) { finish(act, total, fixedSoFar, refused.size()); return; }
                    tag(act, total, fixedSoFar, refused, taggable, uris);
                }
                @Override public void onActivityCreated(Activity a, Bundle b) { }
                @Override public void onActivityStarted(Activity a) { }
                @Override public void onActivityPaused(Activity a) { }
                @Override public void onActivityStopped(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                @Override public void onActivityDestroyed(Activity a) { }
            });
            act.startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
        } catch (Throwable e) {
            Log.w(TAG, "write request failed", e);
            finish(act, total, fixedSoFar, refused.size());
        }
    }

    private static void tag(final Activity act, final int total, final int fixedSoFar, final List<ExifDates.Target> refused,
                            final List<ExifDates.Target> taggable, final List<Uri> uris) {
        final ProgressDialog pd = ProgressDialog.show(act, "Fix photo dates", "Adding the time zone to " + taggable.size()
                + (taggable.size() == 1 ? " photo…" : " photos…"), true, false);
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                ContentResolver cr = act.getContentResolver();
                for (int i = 0; i < taggable.size(); i++) {
                    ExifDates.Target x = taggable.get(i);
                    ParcelFileDescriptor pfd = null;
                    try {
                        pfd = cr.openFileDescriptor(uris.get(i), "rw");
                        if (pfd == null) continue;
                        ExifInterface e = new ExifInterface(pfd.getFileDescriptor());
                        if (e.getAttribute("OffsetTimeOriginal") == null) {
                            String off = offset(x.exifMs);
                            e.setAttribute("OffsetTimeOriginal", off);
                            if (e.getAttribute("OffsetTime") == null) e.setAttribute("OffsetTime", off);
                            e.saveAttributes();
                        }
                    } catch (Throwable err) {
                        Log.w(TAG, "couldn't tag " + x.path, err);
                    } finally {
                        if (pfd != null) try { pfd.close(); } catch (Throwable ignored) { }
                    }
                }
                List<String> paths = new ArrayList<>();
                for (ExifDates.Target x : taggable) paths.add(x.path);
                rescan(act, paths);
                final List<ExifDates.Target> left = ExifDates.scan(act.getApplicationContext());
                final int fixedNow = countFixed(refused, left);
                ui.post(new Runnable() {
                    @Override public void run() {
                        dismiss(pd);
                        if (!act.isFinishing()) finish(act, total, fixedSoFar + fixedNow, refused.size() - fixedNow);
                    }
                });
            }
        }, "FpAlbumDatesTag").start();
    }

    // ------------------------------------------------------------- helpers

    private static void finish(Activity act, int total, int fixed, int notChanged) {
        StringBuilder m = new StringBuilder();
        if (fixed > 0) m.append("Fixed ").append(fixed).append(fixed == 1 ? " photo." : " photos.");
        else m.append("No photos could be fixed.");
        int still = total - fixed;
        if (still > 0) {
            m.append("\n\n").append(still).append(still == 1 ? " photo still has" : " photos still have").append(" the wrong date");
            if (notChanged > 0) m.append(" (Android didn’t allow changing ").append(notChanged == 1 ? "it" : "them").append(")");
            m.append(".");
            if (ExifDates.sortEnabled()) m.append(" Album still sorts ").append(still == 1 ? "it" : "them").append(" by date taken.");
        }
        message(act, m.toString());
    }

    /** Asks Android to read the photos again, and waits for it (up to a minute per 500). */
    private static void rescan(Context c, List<String> paths) {
        if (paths.isEmpty()) return;
        for (int i = 0; i < paths.size(); i += 500) {
            List<String> part = paths.subList(i, Math.min(paths.size(), i + 500));
            final CountDownLatch done = new CountDownLatch(part.size());
            try {
                MediaScannerConnection.scanFile(c.getApplicationContext(), part.toArray(new String[0]), null,
                        new MediaScannerConnection.OnScanCompletedListener() {
                            @Override public void onScanCompleted(String p, Uri u) { done.countDown(); }
                        });
                done.await(60, TimeUnit.SECONDS);
            } catch (Throwable e) {
                Log.w(TAG, "rescan failed", e);
            }
        }
    }

    private static int countStill(List<ExifDates.Target> wanted, List<ExifDates.Target> left) {
        Map<Long, Boolean> l = new HashMap<>();
        for (ExifDates.Target x : left) l.put(x.id, true);
        int n = 0;
        for (ExifDates.Target x : wanted) if (l.containsKey(x.id)) n++;
        return n;
    }

    private static int countFixed(List<ExifDates.Target> wanted, List<ExifDates.Target> left) {
        return wanted.size() - countStill(wanted, left);
    }

    private static boolean exifWritable(List<ExifDates.Target> l) {
        for (ExifDates.Target x : l) if (exifWritable(x.path)) return true;
        return false;
    }

    private static boolean exifWritable(String path) {
        if (path == null) return false;
        String p = path.toLowerCase(java.util.Locale.US);
        return p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp");
    }

    /** "+hh:mm" of the device's time zone at that moment (DST-aware). */
    static String offset(long utcMs) {
        int mins = TimeZone.getDefault().getOffset(utcMs) / 60000;
        char sign = mins < 0 ? '-' : '+';
        mins = Math.abs(mins);
        return String.format(java.util.Locale.US, "%c%02d:%02d", sign, mins / 60, mins % 60);
    }

    private static void message(Activity act, String text) {
        new AlertDialog.Builder(act).setTitle("Fix photo dates").setMessage(text).setPositiveButton("OK", null).show();
    }

    private static void dismiss(ProgressDialog pd) {
        try { pd.dismiss(); } catch (Throwable ignored) { }
    }
}
