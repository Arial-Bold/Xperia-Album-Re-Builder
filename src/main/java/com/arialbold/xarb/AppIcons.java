package com.arialbold.xarb;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * App icon colours. For each colour other than Sony's orange it adds recoloured copies of Album's
 * adaptive icon layers and an adaptive icon that uses them; in the manifest it adds one launcher
 * entry (activity alias) per colour, only one of them enabled at a time; and it writes the list of
 * Album's orange colour values with their recoloured versions, which the added code uses to
 * recolour Album's screens.
 */
public final class AppIcons {
    public static final String ALIAS = "com.fpmod.album.icon.";
    static final String MAIN = "com.sonyericsson.album.MainActivity";

    /** Colour name, then hue (0-1), saturation factor and lightness shift. Orange is Sony's own. */
    static final String[] NAMES = {"Orange", "Red", "Pink", "Purple", "Blue", "Teal", "Green", "Graphite"};
    static final double[][] SPECS = {null, {0.00, 1.00, -0.02}, {0.93, 0.90, 0.00}, {0.77, 0.80, -0.04},
            {0.60, 0.95, -0.03}, {0.48, 0.85, -0.15}, {0.37, 0.70, -0.12}, {0.60, 0.08, -0.12}};

    private static final String XXXHDPI = "res/drawable-xxxhdpi-v4/";
    private static final String ANYDPI = "res/drawable-anydpi-v26/";

    public static final class Result {
        public byte[] resources, manifest;
        public final Map<String, byte[]> add = new LinkedHashMap<>();
        public int oranges;
    }

    private AppIcons() { }

    /**
     * @param files reads an entry of Sony's APK
     * @param manifest the manifest to add the launcher entries to
     */
    public static Result build(java.util.function.Function<String, byte[]> files, byte[] manifest) throws IOException {
        Result r = new Result();
        byte[] arsc = need(files, "resources.arsc");
        BufferedImage bg0 = png(need(files, XXXHDPI + "launcher_bg.png"));
        BufferedImage fg0 = png(need(files, XXXHDPI + "launcher_fg.png"));
        byte[] baseIcon = need(files, ANYDPI + "app_icon.xml");

        // 1. icon layer images and their resource entries
        List<Arsc.Drawable> entries = new ArrayList<>();
        for (int i = 1; i < NAMES.length; i++) {
            String key = NAMES[i].toLowerCase(java.util.Locale.ROOT);
            for (String layer : new String[] {"bg", "fg"}) {
                String name = "fp_icon_" + key + "_" + layer;
                String path = XXXHDPI + name + ".png";
                r.add.put(path, pngBytes(Recolor.image(layer.equals("bg") ? bg0 : fg0, SPECS[i])));
                entries.add(new Arsc.Drawable(name).file(640, 4, path));
            }
            entries.add(new Arsc.Drawable("fp_icon_" + key).file(0xfffe, 26, ANYDPI + "fp_icon_" + key + ".xml"));
        }
        Arsc.Added added = Arsc.addDrawables(arsc, entries);
        r.resources = added.table;

        // 2. an adaptive icon per colour: Sony's app_icon.xml with its two layers swapped
        for (int i = 1; i < NAMES.length; i++) {
            String key = NAMES[i].toLowerCase(java.util.Locale.ROOT);
            Axml x = new Axml(baseIcon);
            int swapped = 0;
            for (Axml.Node n : x.nodes) {
                if (n.type != Axml.EL_START) continue;
                String el = x.s(n.name);
                if (!"background".equals(el) && !"foreground".equals(el)) continue;
                String layer = el.equals("background") ? "bg" : "fg";
                for (Axml.Attr a : n.attrs) {
                    if (a.type == Axml.T_REF) { a.data = added.ids.get("fp_icon_" + key + "_" + layer); swapped++; }
                }
            }
            if (swapped != 2) throw new IOException("Sony's adaptive icon has changed");
            r.add.put(ANYDPI + "fp_icon_" + key + ".xml", x.toBytes());
        }

        // 3. manifest: one launcher entry per colour; Album's main screen stops being the launcher entry
        Axml m = new Axml(manifest);
        m.attrName("icon", 0x01010002);
        m.attrName("name", 0x01010003);
        m.attrName("enabled", 0x0101000e);
        m.attrName("exported", 0x01010010);
        m.attrName("targetActivity", 0x01010202);
        int main = m.findElement("activity", "name", MAIN, 0);
        if (main < 0) throw new IOException("Album's main screen isn't in the manifest");
        int mainEnd = m.endOf(main);
        int app = m.findElement("application", null, null, 0);
        Integer appIcon = null;
        for (Axml.Attr a : m.nodes.get(app).attrs) if ("icon".equals(m.s(a.name))) appIcon = a.data;
        if (appIcon == null) throw new IOException("Album's icon isn't in the manifest");
        int moved = m.addString("com.fpmod.album.category.LAUNCHER_MOVED_TO_ICON_ALIASES");
        int launcherCats = 0;
        Axml.Node[] shortcuts = null;
        for (int j = main; j < mainEnd; j++) {
            Axml.Node n = m.nodes.get(j);
            if (n.type != Axml.EL_START) continue;
            if ("category".equals(m.s(n.name))) {
                for (Axml.Attr a : n.attrs) {
                    if ("name".equals(m.s(a.name)) && "android.intent.category.LAUNCHER".equals(m.s(a.raw))) {
                        a.raw = moved; a.data = moved; launcherCats++;
                    }
                }
            }
            if ("meta-data".equals(m.s(n.name))) {
                for (Axml.Attr a : n.attrs) {
                    if ("android.app.shortcuts".equals(m.s(a.raw))) shortcuts = new Axml.Node[] {n, m.nodes.get(m.endOf(j))};
                }
            }
        }
        if (launcherCats != 1) throw new IOException("Album's launcher entry wasn't found");
        List<Axml.Node> block = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            int icon = i == 0 ? appIcon : added.ids.get("fp_icon_" + NAMES[i].toLowerCase(java.util.Locale.ROOT));
            Axml.Node[] alias = m.element("activity-alias",
                    new Axml.A("icon", 0x01010002, "ref", icon),
                    new Axml.A("name", 0x01010003, "string", ALIAS + NAMES[i]),
                    new Axml.A("enabled", 0x0101000e, "bool", i == 0),
                    new Axml.A("exported", 0x01010010, "bool", true),
                    new Axml.A("targetActivity", 0x01010202, "string", MAIN));
            Axml.Node[] filter = m.element("intent-filter");
            Axml.Node[] action = m.element("action", new Axml.A("name", 0x01010003, "string", "android.intent.action.MAIN"));
            Axml.Node[] cat = m.element("category", new Axml.A("name", 0x01010003, "string", "android.intent.category.LAUNCHER"));
            block.add(alias[0]);
            if (shortcuts != null) { block.add(Axml.copy(shortcuts[0])); block.add(Axml.copy(shortcuts[1])); }
            block.add(filter[0]); block.add(action[0]); block.add(action[1]); block.add(cat[0]); block.add(cat[1]); block.add(filter[1]);
            block.add(alias[1]);
        }
        m.nodes.addAll(mainEnd + 1, block);
        r.manifest = m.toBytes();

        // 4. accent colours: Album's oranges and their recoloured versions
        Map<String, Integer> oranges = new TreeMap<>();
        for (Map.Entry<String, Integer> e : Arsc.colourValues(arsc).entrySet()) if (isOrange(e.getValue())) oranges.put(e.getKey(), e.getValue());
        r.oranges = oranges.size();
        for (int i = 1; i < NAMES.length; i++) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : oranges.entrySet()) {
                int v = e.getValue();
                int c = (v & 0xFF000000) | (Recolor.pixel(v | 0xFF000000, SPECS[i]) & 0xFFFFFF);
                sb.append(e.getKey()).append(' ').append(String.format("%08x", v)).append(' ').append(String.format("%08x", c)).append('\n');
            }
            r.add.put("assets/fpmod/accent_" + NAMES[i].toLowerCase(java.util.Locale.ROOT) + ".map", sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    /** Album's orange: hue 0.04-0.12, saturated, mid lightness. */
    static boolean isOrange(int v) {
        double[] hls = Recolor.rgbToHls(((v >> 16) & 255) / 255.0, ((v >> 8) & 255) / 255.0, (v & 255) / 255.0);
        return hls[0] >= 0.04 && hls[0] <= 0.12 && hls[2] > 0.6 && hls[1] > 0.3 && hls[1] < 0.85;
    }

    private static byte[] need(java.util.function.Function<String, byte[]> files, String name) throws IOException {
        byte[] b = files.apply(name);
        if (b == null) throw new IOException(name + " isn't in the app");
        return b;
    }

    private static BufferedImage png(byte[] b) throws IOException {
        BufferedImage im = ImageIO.read(new ByteArrayInputStream(b));
        if (im == null) throw new IOException("unreadable icon image");
        return im;
    }

    private static byte[] pngBytes(BufferedImage im) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(im, "png", o);
        return o.toByteArray();
    }
}
