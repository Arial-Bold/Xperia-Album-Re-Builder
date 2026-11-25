package com.fpmod.album;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.util.Log;

/**
 * Album's Settings, laid out as:
 *   General – Theme, App icon colour, Navigation menu
 *   Photo   – Header slideshow, Sort by date taken, Fix photo dates
 *   Video   – Background playback, Subtitle settings
 *   About   – Version, credit
 *
 * Sony's Settings screen is rebuilt from its XML whenever the view is re-created, while categories
 * added here survive, so ours are removed and added again each time.
 */
final class SettingsLayout {
    static final String PHOTO = "fpmod_photo";
    static final String VIDEO = "fpmod_video";
    private static final String CREDIT = "fpmod_credit";
    private static final String CREDIT_URL = "https://github.com/Arial-Bold";

    private SettingsLayout() { }

    static void apply(PreferenceFragment fragment) {
        Activity act = fragment.getActivity();
        PreferenceScreen screen = fragment.getPreferenceScreen();
        if (act == null || screen == null) return;

        // our categories from an earlier build of this screen, and the old "Photo dates" one
        for (String k : new String[] {PHOTO, VIDEO, "fpmod_photo_dates"}) remove(screen, k);
        // Sony-only or retired: Troubleshooting (Find and fix problems), media server, China permission
        // screen, the separate Video player screen (its settings are below now), casting quality,
        // Amazon backup
        remove(screen, "preference_category_assistance");
        remove(screen, "preference_category_cast");
        for (String k : new String[] {"prefs_option_media_server", "preference_category_app_permission",
                "preference_category_video_settings", "cast_image_quality", "prefs_option_amazon_backup_settings"}) {
            remove(screen, k);
        }

        PreferenceGroup general = group(screen, "preference_category_general");
        PreferenceGroup about = group(screen, "preference_category_about");
        if (general != null) general.setOrder(0);
        if (about != null) about.setOrder(30);

        // General
        Preference banner = screen.findPreference("banner_contents_type");
        if (banner != null) remove(screen, "banner_contents_type");
        if (general != null) {
            order(general, "album_theme", 0);
            AppIconSettings.addTo(fragment, act);
            order(general, AppIconSettings.KEY, 1);
            order(general, "preference_category_drawer", 2);
        }

        // Photo
        PreferenceCategory photo = category(screen, act, PHOTO, "Photo", 10);
        if (banner != null) { banner.setOrder(0); photo.addPreference(banner); }
        DateSettings.addTo(photo, act);

        // Video
        PreferenceCategory video = category(screen, act, VIDEO, "Video", 20);
        VideoSettings.addTo(video, act);

        // About
        if (about != null && about.findPreference(CREDIT) == null) {
            order(about, "prefs_app_version", 0);
            Preference credit = new Preference(act);
            credit.setKey(CREDIT);
            credit.setPersistent(false);
            credit.setOrder(1);
            credit.setTitle("Rebuild by Arial-Bold on GitHub");
            credit.setSummary("github.com/Arial-Bold");
            final Activity a = act;
            credit.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override public boolean onPreferenceClick(Preference p) {
                    try {
                        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CREDIT_URL)));
                    } catch (Throwable e) {
                        Log.w(ExifDates.TAG, "couldn't open " + CREDIT_URL, e);
                    }
                    return true;
                }
            });
            about.addPreference(credit);
        }
    }

    static PreferenceCategory category(PreferenceScreen screen, Activity act, String key, String title, int order) {
        PreferenceCategory c = new PreferenceCategory(act);
        c.setKey(key);
        c.setTitle(title);
        c.setOrder(order);
        screen.addPreference(c);
        return c;
    }

    private static PreferenceGroup group(PreferenceScreen screen, String key) {
        Preference p = screen.findPreference(key);
        return p instanceof PreferenceGroup ? (PreferenceGroup) p : null;
    }

    private static void order(PreferenceGroup g, String key, int order) {
        Preference p = g.findPreference(key);
        if (p != null) p.setOrder(order);
    }

    /** Removes the preference with this key from whichever group holds it. */
    private static void remove(PreferenceGroup g, String key) {
        for (int i = 0; i < g.getPreferenceCount(); i++) {
            Preference p = g.getPreference(i);
            if (key.equals(p.getKey())) { g.removePreference(p); return; }
            if (p instanceof PreferenceGroup) remove((PreferenceGroup) p, key);
        }
    }
}
