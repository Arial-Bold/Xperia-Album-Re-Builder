package com.fpmod.album;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;

/**
 * "App icon color" in Album's Settings: picks the launcher icon colour. The build adds one
 * launcher alias per colour (com.fpmod.album.icon.Orange, .Red, ...), only one of them enabled;
 * this switches which one, then restarts Album through the new one (see switchNow).
 *
 * If the restart can't be started, the choice is saved and applied once all of Album's screens
 * have stopped (Android closes everything started through a launcher entry when it's turned off).
 */
public final class AppIconSettings {
    static final String KEY = "fpmod_app_icon";
    static final String[] NAMES = {"Orange", "Red", "Pink", "Purple", "Blue", "Teal", "Green", "Graphite"};
    private static final int[] COLOURS = {0xFFF57C00, 0xFFE5000A, 0xFFE5186A, 0xFF8A1BD0, 0xFF0B66E0, 0xFF13A394, 0xFF1F9E3A, 0xFF5A6068};
    private static final String ALIAS = "com.fpmod.album.icon.";
    private static final String PENDING = "fpmod_icon_pending";
    private static final int MATCH_DISABLED = 0x200;          // PackageManager.MATCH_DISABLED_COMPONENTS
    private static final java.util.Set<Activity> started = new java.util.HashSet<>();
    private static android.app.Application app;

    private AppIconSettings() { }

    static void addTo(PreferenceFragment fragment, final Activity act) {
        try {
            PreferenceScreen screen = fragment.getPreferenceScreen();
            if (screen == null || screen.findPreference(KEY) != null || !available(act)) return;
            Preference general = screen.findPreference("preference_category_general");
            PreferenceGroup group = general instanceof PreferenceGroup ? (PreferenceGroup) general : screen;
            final Preference p = new Preference(act);
            p.setKey(KEY);
            p.setPersistent(false);
            p.setTitle("App icon color");
            p.setSummary(summary(act));
            p.setOrder(Integer.MAX_VALUE - 1);
            p.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override public boolean onPreferenceClick(Preference pref) { choose(act, p); return true; }
            });
            group.addPreference(p);
        } catch (Throwable e) {
            Log.w(ExifDates.TAG, "couldn't add App icon setting", e);
        }
    }

    /** True if this build has the colour aliases. */
    static boolean available(Activity act) {
        try {
            act.getPackageManager().getActivityInfo(new ComponentName(act.getPackageName(), ALIAS + NAMES[0]), MATCH_DISABLED);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    static int current(android.content.Context act) {
        PackageManager pm = act.getPackageManager();
        for (int i = 0; i < NAMES.length; i++) {
            int s = pm.getComponentEnabledSetting(new ComponentName(act.getPackageName(), ALIAS + NAMES[i]));
            if (s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i;
            if (i == 0 && s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                boolean other = false;
                for (int k = 1; k < NAMES.length; k++) {
                    if (pm.getComponentEnabledSetting(new ComponentName(act.getPackageName(), ALIAS + NAMES[k]))
                            == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) { other = true; break; }
                }
                if (!other) return 0;
            }
        }
        return 0;
    }

    private static int pending(android.content.Context c) {
        return ExifDates.prefs(c).getInt(PENDING, -1);
    }

    private static String summary(Activity act) {
        int pend = pending(act);
        return pend >= 0 && pend != current(act) ? NAMES[pend] + " (icon and colors change when you leave Album)" : NAMES[current(act)];
    }

    private static void choose(final Activity act, final Preference p) {
        int pend = pending(act);
        final int cur = pend >= 0 ? pend : current(act);
        CharSequence[] items = new CharSequence[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            SpannableString s = new SpannableString("●  " + NAMES[i] + (i == 0 ? " (Sony’s original)" : ""));
            s.setSpan(new ForegroundColorSpan(COLOURS[i]), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[i] = s;
        }
        new AlertDialog.Builder(act).setTitle("App icon color")
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which == cur) return;
                        if (which == current(act)) {
                            ExifDates.prefs(act).edit().remove(PENDING).apply();
                            p.setSummary(summary(act));
                            return;
                        }
                        new AlertDialog.Builder(act).setTitle("App icon color")
                                .setMessage("Album restarts to change its icon and colors to " + NAMES[which].toLowerCase(java.util.Locale.US)
                                        + ". If Album is on your home screen, you may need to add it again from the app list afterwards.")
                                .setPositiveButton("Restart", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d2, int w) { switchNow(act, which); }
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Restarts Album in the new colour: switches the icon entries, opens Album again through the new
     * one, then ends this process, so Album starts completely fresh in the new colour (Android
     * relaunches the screen that was just opened).
     */
    private static void switchNow(final Activity act, final int which) {
        final android.content.Context appCtx = act.getApplicationContext();
        try {
            ExifDates.prefs(act).edit().remove(PENDING).commit();
            apply(appCtx, which);
            appCtx.startActivity(new android.content.Intent()
                    .setClassName(appCtx.getPackageName(), ALIAS + NAMES[which])
                    .setAction("android.intent.action.MAIN")
                    .addCategory("android.intent.category.LAUNCHER")
                    .addFlags(0x10000000 | 0x00008000));            // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK
        } catch (Throwable e) {
            Log.w(ExifDates.TAG, "couldn't restart Album", e);
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { android.os.Process.killProcess(android.os.Process.myPid()); }
        }, 400);
    }

    /** Called when Album starts: watches for Album leaving the screen, to apply a saved choice then. */
    static void install(android.content.Context c) {
        try {
            if (app != null || !(c instanceof android.app.Application)) return;
            app = (android.app.Application) c;
            app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityStarted(Activity a) { started.add(a); }
                @Override public void onActivityStopped(Activity a) {
                    started.remove(a);
                    if (!started.isEmpty() || pending(app) < 0) return;
                    // wait a moment: a stop can be part of moving between Album's own screens or rotating
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() { if (started.isEmpty()) applyPending(); }
                    }, 1500);
                }
                @Override public void onActivityCreated(Activity a, android.os.Bundle b) { }
                @Override public void onActivityResumed(Activity a) { }
                @Override public void onActivityPaused(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) { }
                @Override public void onActivityDestroyed(Activity a) { started.remove(a); }
            });
        } catch (Throwable e) {
            Log.w(ExifDates.TAG, "couldn't watch for leaving Album", e);
        }
    }

    private static void applyPending() {
        try {
            int which = pending(app);
            if (which < 0 || which >= NAMES.length) return;
            ExifDates.prefs(app).edit().remove(PENDING).apply();
            apply(app, which);
            AccentColours.changed();
        } catch (Throwable e) {
            Log.w(ExifDates.TAG, "couldn't change the app icon", e);
        }
    }

    /** Enables the chosen colour's launcher entry first, then turns the others off. */
    static void apply(android.content.Context act, int which) {
        PackageManager pm = act.getPackageManager();
        String pkg = act.getPackageName();
        pm.setComponentEnabledSetting(new ComponentName(pkg, ALIAS + NAMES[which]),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (int i = 0; i < NAMES.length; i++) {
            if (i == which) continue;
            pm.setComponentEnabledSetting(new ComponentName(pkg, ALIAS + NAMES[i]),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
