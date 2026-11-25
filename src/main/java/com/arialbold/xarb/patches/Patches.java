package com.arialbold.xarb.patches;

import com.arialbold.xarb.Smali;
import com.arialbold.xarb.Smali.ClassFile;
import com.arialbold.xarb.Smali.Method;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The code changes that connect the added features to Sony's Album app. Each patch finds its
 * place by what the code does (method names, calls, strings) and reports clearly if a newer
 * release no longer matches, so the other features can still be built.
 */
public final class Patches {

    /** Result of one patch. */
    public static final class Result {
        public final String feature;
        public final boolean applied;
        public final String detail;
        public Result(String feature, boolean applied, String detail) { this.feature = feature; this.applied = applied; this.detail = detail; }
        @Override public String toString() { return (applied ? "✓ " : "✗ ") + feature + (detail.isEmpty() ? "" : " — " + detail); }
    }

    public interface Patch {
        String feature();
        void apply(Smali s) throws Exception;   // throws Skip to report a non-match
    }

    /** Thrown when a patch can't find what it needs. */
    public static final class Skip extends Exception {
        public Skip(String why) { super(why); }
    }

    private static final String P = "Lcom/fpmod/album/";
    private static final String COLUMN = P + "ExifDates;->column(Ljava/lang/String;)Ljava/lang/String;";
    private static final String SQL = P + "ExifDates;->sql(Ljava/lang/String;)Ljava/lang/String;";

    public static List<Patch> all() {
        List<Patch> l = new ArrayList<>();
        l.add(new Startup());
        l.add(new FastScroll());
        l.add(new SortByDateTaken());
        l.add(new SettingsScreen());
        l.add(new SdCard());
        l.add(new BackgroundPlayback());
        return l;
    }

    public static List<Result> run(Smali s, java.util.function.Consumer<String> log) {
        List<Result> out = new ArrayList<>();
        for (Patch p : all()) {
            Result r;
            try {
                p.apply(s);
                r = new Result(p.feature(), true, "");
            } catch (Skip e) {
                r = new Result(p.feature(), false, e.getMessage());
            } catch (Exception e) {
                r = new Result(p.feature(), false, "error: " + e);
            }
            out.add(r);
            if (log != null) log.accept(r.toString());
        }
        return out;
    }

    static void need(boolean ok, String why) throws Skip { if (!ok) throw new Skip(why); }

    /** "move-result-object reg" after a static call that takes and returns reg. */
    static String[] wrap(String reg, String target) {
        return new String[] {Smali.invokeOne(reg, target), "    move-result-object " + reg};
    }

    // ------------------------------------------------------------------
    // Album starts: load the photo dates cache, SD card helpers, icon colours

    static final class Startup implements Patch {
        public String feature() { return "Added features load when Album starts"; }
        public void apply(Smali s) throws Exception {
            ClassFile c = s.byDescriptor("Lcom/sonyericsson/album/AlbumApplication;");
            need(c != null, "Album's application class wasn't found");
            Method m = c.method(" onCreate()V");
            need(m != null, "AlbumApplication.onCreate wasn't found");
            m.insert(m.firstInstruction(), Smali.invokeOne("p0", P + "ExifDates;->init(Landroid/content/Context;)V"));
            c.save();
        }
    }

    // ------------------------------------------------------------------
    // Fast scroll: on phones where hiding the navigation bar resizes the screen, Album's scroll
    // engine lets go of the scroll handle. Keep the bars visible while fast-scrolling.

    static final class FastScroll implements Patch {
        public String feature() { return "Smooth fast-scroll bar"; }
        public void apply(Smali s) throws Exception {
            ClassFile c = s.byDescriptor("Lcom/sonyericsson/album/MainActivity;");
            need(c != null, "Album's main screen wasn't found");
            Method m = c.method(" startFastScroll()V");
            need(m != null, "MainActivity.startFastScroll wasn't found");
            // replace the body with return-void
            List<String> body = c.lines.subList(m.start + 1, m.end);
            body.clear();
            body.add("    .locals 0");
            body.add("");
            body.add("    return-void");
            c.save();
        }
    }

    // ------------------------------------------------------------------
    // Sort by date taken: every place Album reads MediaStore's date taken, sorts, or picks folder
    // covers goes through ExifDates, which adds the EXIF dates Android didn't record.

    static final class SortByDateTaken implements Patch {
        public String feature() { return "Sort by date taken (EXIF)"; }
        public void apply(Smali s) throws Exception {
            List<String> missing = new ArrayList<>();

            // 1. Sony's own date column hook
            ClassFile w = s.byDescriptor("Lcom/sonyericsson/album/common/mediaprovider/SomcMediaStoreWrapper;");
            Method m = w == null ? null : w.method("getDateTakenColumnName()Ljava/lang/String;");
            int n = 0;
            if (m != null) {
                Pattern ret = Pattern.compile("^\\s*return-object (\\w+)\\s*$");
                for (int i = m.end; i > m.start; i--) {
                    Matcher mm = ret.matcher(w.lines.get(i));
                    if (mm.matches()) { m.insert(i, wrap(mm.group(1), COLUMN)); n++; }
                }
                if (n > 0) w.save();
            }
            if (n == 0) missing.add("date column");

            // 2. the main grid's date column
            ClassFile g = s.byDescriptor("Lcom/sonyericsson/album/aggregator/properties/MediaStoreConfig$Medias;");
            m = g == null ? null : g.method("getProjection(");
            n = 0;
            if (m != null) {
                Pattern dt = Pattern.compile("^\\s*const-string(?:/jumbo)? (\\w+), \"datetaken\"\\s*$");
                for (int i = m.end; i > m.start; i--) {
                    Matcher mm = dt.matcher(g.lines.get(i));
                    if (mm.matches()) { m.insert(i + 1, wrap(mm.group(1), COLUMN)); n++; }
                }
                if (n > 0) g.save();
            }
            if (n == 0) missing.add("main grid date");

            // 3. sort orders and folder-cover queries (outside class initialisers)
            Pattern sort = Pattern.compile("^\\s*sget-object (\\w+), Lcom/sonyericsson/album/aggregator/properties/MediaStoreConfig;->DEFAULT_SORT_ORDER:Ljava/lang/String;\\s*$");
            Pattern sel = Pattern.compile("^\\s*const-string(?:/jumbo)? (\\w+), \".*IFNULL \\(datetaken,date_modified\\*1000\\).*\"\\s*$");
            int nsort = 0, nsel = 0;
            for (ClassFile c : s.withText("/com/sonyericsson/album/", "DEFAULT_SORT_ORDER:Ljava/lang/String;", "IFNULL (datetaken,date_modified*1000)")) {
                boolean clinit = false, changed = false;
                for (int i = 0; i < c.lines.size(); i++) {
                    String l = c.lines.get(i);
                    if (l.startsWith(".method")) clinit = l.contains("<clinit>");
                    Matcher a = sort.matcher(l), b = sel.matcher(l);
                    String reg = a.matches() ? a.group(1) : (b.matches() && !clinit ? b.group(1) : null);
                    if (reg == null) continue;
                    if (a.matches()) nsort++; else nsel++;
                    c.insert(i + 1, wrap(reg, SQL));
                    i += 4;
                    changed = true;
                }
                if (changed) c.save();
            }
            if (nsort == 0) missing.add("photo sort order");
            if (nsel == 0) missing.add("folder covers");
            need(missing.size() < 4, "none of Album's date queries were found");
            if (!missing.isEmpty()) throw new Skip("partly: " + String.join(", ", missing) + " not found");
        }
    }

    // ------------------------------------------------------------------
    // Settings: General / Photo / Video / About, App icon color, Fix photo dates, credit

    static final class SettingsScreen implements Patch {
        public String feature() { return "Reorganized Settings, app icon colors, Fix photo dates"; }
        public void apply(Smali s) throws Exception {
            ClassFile c = s.byDescriptor("Lcom/sonyericsson/album/settings/PrefsFragment;");
            need(c != null, "Album's Settings screen wasn't found");
            Method m = c.method(" onCreateView(");
            need(m != null, "PrefsFragment.onCreateView wasn't found");
            int i = m.find("PrefsFragment;->setupGeneralCategory(");
            need(i >= 0, "Sony's Settings setup call wasn't found");
            m.insert(i + 1, Smali.invokeOne("p0", P + "DateSettings;->addTo(Landroid/preference/PreferenceFragment;)V"));
            c.save();
        }
    }

    // ------------------------------------------------------------------
    // SD card: Sony's storage helpers call Android methods that are blocked on Android 11 and later.
    // The added code carries public-API replacements with the same names; remove Sony's copies,
    // and let Album's "is this storage method available?" checks pass for them.

    static final String[] PROXIES = {"StorageManagerProxy", "StorageManagerProxy$StorageType", "StorageVolumeProxy",
            "VolumeInfoProxy", "DiskInfoProxy", "UserHandleProxy"};

    static final class SdCard implements Patch {
        public String feature() { return "Delete and favorite photos on the SD card"; }
        public void apply(Smali s) throws Exception {
            ClassFile dm = s.byDescriptor("Lcom/sonyericsson/album/common/util/dependency/DependencyManager;");
            need(dm != null, "Album's feature checks weren't found");
            String[][] overloads = {
                {"isAvailable(Landroid/content/Context;Lcom/sonyericsson/album/common/util/dependency/Dependency;)Z", "p1"},
                {"isAvailable(Lcom/sonyericsson/album/common/util/dependency/Dependency;)Z", "p0"}};
            int n = 0;
            for (String[] o : overloads) {
                Method m = dm.method(o[0]);
                if (m == null) continue;
                String v = m.newLocal();
                m.insert(m.firstInstruction(),
                        Smali.invokeOne(o[1], P + "SdAccess;->storageReady(Ljava/lang/Object;)Z"),
                        "    move-result " + v,
                        "    if-eqz " + v + ", :fpmod_dep_check",
                        "    return " + v,
                        "    :fpmod_dep_check");
                n++;
            }
            need(n == 2, "Album's feature checks have changed");
            int removed = 0;
            for (String p : PROXIES) if (s.deleteClass("Landroid/os/storage/" + p + ";")) removed++;
            need(removed == PROXIES.length, "Sony's storage helpers have changed (" + removed + " of " + PROXIES.length + " found)");
            dm.save();
        }
    }

    // ------------------------------------------------------------------
    // Video: Background playback is now set from Album's main Settings (another process), so the
    // player reads it fresh: UserSetting.isBackgroundPlay returns through VideoSettings.

    static final class BackgroundPlayback implements Patch {
        public String feature() { return "Video settings in Album's Settings"; }
        public void apply(Smali s) throws Exception {
            ClassFile c = s.byDescriptor("Lcom/sonyericsson/album/video/common/UserSetting;");
            need(c != null, "the video player's settings weren't found");
            Method m = c.method("isBackgroundPlay()Z");
            need(m != null, "UserSetting.isBackgroundPlay wasn't found");
            Pattern ret = Pattern.compile("^\\s*return ([vp]\\d+)\\s*$");
            int n = 0;
            for (int i = m.end; i > m.start; i--) {
                Matcher mm = ret.matcher(c.lines.get(i));
                if (!mm.matches()) continue;
                String v = mm.group(1);
                need(Smali.fitsInvoke(m, "p0", v), "unexpected register layout");
                m.insert(i, "    invoke-static {p0, " + v + "}, " + P + "VideoSettings;->backgroundPlay(Ljava/lang/Object;Z)Z",
                        "    move-result " + v);
                n++;
            }
            need(n > 0, "UserSetting.isBackgroundPlay has changed");
            c.save();
        }
    }
}
