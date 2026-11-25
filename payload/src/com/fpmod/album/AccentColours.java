package com.fpmod.album;

import android.app.Activity;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Album's orange accents (Settings headings, list titles, switches, the drawer header…) follow the
 * app icon colour.
 *
 * For each icon colour the builder writes assets/fpmod/accent_<colour>.map, listing Album's orange
 * colour values and their recoloured versions. Whenever a screen is laid out, views still showing
 * one of those oranges are recoloured: text, plain colour backgrounds, and the "on" colour of
 * switches and check boxes.
 */
final class AccentColours {
    private static android.app.Application app;
    private static int colour = -1;                          // icon colour in use; -1 = not looked up yet
    private static final List<Activity> live = new ArrayList<>();

    private AccentColours() { }

    static void install(android.app.Application a) {
        if (app != null) return;
        app = a;
        try {
            app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity act, android.os.Bundle b) { live.add(act); }
                @Override public void onActivityStarted(Activity act) { }
                @Override public void onActivityResumed(Activity act) { watch(act); }
                @Override public void onActivityPaused(Activity act) { }
                @Override public void onActivityStopped(Activity act) { }
                @Override public void onActivitySaveInstanceState(Activity act, android.os.Bundle b) { }
                @Override public void onActivityDestroyed(Activity act) { live.remove(act); }
            });
        } catch (Throwable e) {
            Log.w(ExifDates.TAG, "couldn't set up accent colours", e);
        }
    }

    /** The icon colour changed while Album was in the background: redraw open screens in the new colour. */
    static void changed() {
        if (app == null) return;
        preset(-1);
        try {
            for (Activity act : new ArrayList<>(live)) {
                if (act.isFinishing() || act.isDestroyed()) continue;
                act.recreate();   // re-created with the new colours when it's next shown
            }
        } catch (Throwable e) {
            Log.w(ExifDates.TAG, "couldn't update accent colours", e);
        }
    }

    private static java.util.Map<Integer, Integer> map;       // original colour -> new colour
    private static int mapFor = -1;
    private static final java.util.Map<android.view.View, Boolean> watched = new java.util.WeakHashMap<>();

    private static java.util.Map<Integer, Integer> map() {
        try {
            if (colour < 0) colour = AppIconSettings.current(app);
            if (colour <= 0 || colour >= AppIconSettings.NAMES.length) return null;
            if (mapFor == colour) return map;
            java.util.Map<Integer, Integer> m = new java.util.HashMap<>();
            String key = AppIconSettings.NAMES[colour].toLowerCase(java.util.Locale.ROOT);
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    app.getAssets().open("fpmod/accent_" + key + ".map"), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.trim().split(" ");
                    if (f.length == 3) m.put((int) Long.parseLong(f[1], 16), (int) Long.parseLong(f[2], 16));
                }
            } finally { r.close(); }
            map = m;
            mapFor = colour;
            return m;
        } catch (Throwable e) {
            Log.w(ExifDates.TAG, "couldn't read accent colours", e);
            return null;
        }
    }

    private static void watch(Activity act) {
        try {
            if (map() == null || act.getWindow() == null) return;
            final android.view.View decor = act.getWindow().getDecorView();
            if (decor == null || watched.containsKey(decor)) return;
            watched.put(decor, Boolean.TRUE);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() { recolour(decor); }
            });
            recolour(decor);
        } catch (Throwable e) {
            Log.w(ExifDates.TAG, "couldn't watch for accent colours", e);
        }
    }

    private static void recolour(android.view.View v) {
        java.util.Map<Integer, Integer> m = map();
        if (m == null || v == null) return;
        try {
            if (v instanceof android.widget.TextView) {
                android.widget.TextView t = (android.widget.TextView) v;
                android.content.res.ColorStateList c = t.getTextColors();
                Integer to = c != null ? m.get(c.getDefaultColor()) : null;
                if (to != null) t.setTextColor(to);
            }
            if (v instanceof android.widget.CompoundButton && !tinted.containsKey(v)) {
                tinted.put(v, Boolean.TRUE);
                tint((android.widget.CompoundButton) v, m);
            }
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg instanceof android.graphics.drawable.ColorDrawable) {
                Integer to = m.get(((android.graphics.drawable.ColorDrawable) bg).getColor());
                if (to != null) v.setBackgroundColor(to);
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) recolour(g.getChildAt(i));
            }
        } catch (Throwable e) {
            // leave this view as it is
        }
    }

    private static final java.util.Map<android.view.View, Boolean> tinted = new java.util.WeakHashMap<>();
    private static final int ATTR_FOREGROUND = 0x01010030, ATTR_CONTROL_NORMAL = 0x01010429, ATTR_CONTROL_ACTIVATED = 0x0101042a;
    private static final int STATE_ENABLED = 0x0101009e, STATE_CHECKED = 0x010100a0;

    /** Switches and check boxes take their "on" colour from the theme, not a colour resource: tint them. */
    private static void tint(android.widget.CompoundButton b, java.util.Map<Integer, Integer> m) {
        android.content.res.TypedArray a = b.getContext().obtainStyledAttributes(new int[] {ATTR_FOREGROUND, ATTR_CONTROL_NORMAL, ATTR_CONTROL_ACTIVATED});
        int fg, normal, on;
        try {
            fg = a.getColor(0, 0xFF000000);
            normal = a.getColor(1, 0x8A000000);
            on = a.getColor(2, 0);
        } finally { a.recycle(); }
        Integer to = m.get(on);
        if (to == null) return;                                  // not one of Album's orange colours
        int[][] states = {{-STATE_ENABLED}, {STATE_CHECKED}, {}};
        if (b instanceof android.widget.Switch) {
            boolean dark = (((fg >> 16) & 255) + ((fg >> 8) & 255) + (fg & 255)) > 384;   // light text = dark theme
            int thumbOff = dark ? 0xFFBDBDBD : 0xFFF1F1F1;          // Material switch thumb, off
            int thumbOffDisabled = dark ? 0xFF616161 : 0xFFBDBDBD;
            ((android.widget.Switch) b).setThumbTintList(new android.content.res.ColorStateList(states,
                    new int[] {thumbOffDisabled, to, thumbOff}));
            ((android.widget.Switch) b).setTrackTintList(new android.content.res.ColorStateList(states,
                    new int[] {(fg & 0xFFFFFF) | 0x1A000000, (to & 0xFFFFFF) | 0x4D000000, (fg & 0xFFFFFF) | 0x4D000000}));
        } else {
            b.setButtonTintList(new android.content.res.ColorStateList(states,
                    new int[] {(normal & 0xFFFFFF) | 0x42000000, to, normal}));
        }
    }

    /** Use this icon colour from now on (-1: look it up again). */
    static void preset(int which) {
        colour = which;
        watched.clear();
        tinted.clear();
    }
}
