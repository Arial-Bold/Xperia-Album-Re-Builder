package com.fpmod.album;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.util.Log;

/**
 * Video settings in Album's main Settings (they used to sit on a separate "Video player" screen).
 *
 * Album's video player runs in its own process and keeps its settings in VIDEO_USER_PREFERENCES.
 * Background playback is written here and read fresh by the player (UserSetting.isBackgroundPlay is
 * hooked to backgroundPlay below), so a change reaches a player process that is already running.
 * Subtitle settings opens Sony's own subtitle screen.
 */
public final class VideoSettings {
    private static final String FILE = "VIDEO_USER_PREFERENCES";
    private static final String KEY_BACKGROUND = "is_background_play_prefs";
    private static final int MODE_MULTI_PROCESS = 4;          // Context.MODE_MULTI_PROCESS: re-reads the file if another process changed it
    private static final String SUBTITLES = "com.sonyericsson.album.video.action.SHOW_SUBTITLE_SETTINGS";
    private static Context app;

    private VideoSettings() { }

    /** From ExifDates.init, in every Album process. */
    static void init(Context c) { app = c; }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, MODE_MULTI_PROCESS);
    }

    /** Hook: UserSetting.isBackgroundPlay() returns through here with its cached value. */
    public static boolean backgroundPlay(Object userSetting, boolean cached) {
        try {
            return app == null ? cached : prefs(app).getBoolean(KEY_BACKGROUND, cached);
        } catch (Throwable e) {
            return cached;
        }
    }

    static void addTo(PreferenceGroup cat, final Activity act) {
        SwitchPreference bg = new SwitchPreference(act);
        bg.setKey("fpmod_background_play");
        bg.setPersistent(false);
        bg.setOrder(0);
        bg.setTitle(text(act, "mv_settings_background_playback_txt", "Background playback"));
        bg.setSummary(text(act, "mv_settings_background_playback_sub_txt", "Keep playing videos in the background while using other apps"));
        bg.setChecked(prefs(act).getBoolean(KEY_BACKGROUND, false));
        bg.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference p, Object v) {
                try {
                    prefs(act).edit().putBoolean(KEY_BACKGROUND, Boolean.TRUE.equals(v)).commit();
                    return true;
                } catch (Throwable e) {
                    Log.w(ExifDates.TAG, "couldn't save background playback", e);
                    return false;
                }
            }
        });
        cat.addPreference(bg);

        Preference subs = new Preference(act);
        subs.setKey("fpmod_subtitle_settings");
        subs.setPersistent(false);
        subs.setOrder(1);
        subs.setTitle(text(act, "mv_settings_subtitle_settings_txt", "Subtitle settings"));
        subs.setSummary(text(act, "mv_settings_subtitle_settings_sub_txt", "Customize font settings for subtitles"));
        subs.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference p) {
                try {
                    act.startActivity(new Intent(SUBTITLES).setPackage(act.getPackageName()));
                } catch (Throwable e) {
                    Log.w(ExifDates.TAG, "couldn't open subtitle settings", e);
                }
                return true;
            }
        });
        cat.addPreference(subs);
    }

    /** Sony's own (translated) text where it exists. */
    private static String text(Context c, String name, String fallback) {
        try {
            int id = c.getResources().getIdentifier(name, "string", c.getPackageName());
            String s = id != 0 ? c.getResources().getString(id) : null;
            return s != null && s.length() > 0 ? s : fallback;
        } catch (Throwable e) {
            return fallback;
        }
    }
}
