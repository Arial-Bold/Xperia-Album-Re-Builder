package com.arialbold.xarb;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds drawable resources to a compiled resources.arsc without re-encoding anything else, and
 * reads colour values from it.
 *
 * Only appends: new strings go at the end of the global and key string pools (so existing string
 * indices don't change), and new entries go at the end of the drawable type (so existing resource
 * IDs don't change). Every other byte of the table is copied as is.
 */
public final class Arsc {
    static final int STRING_POOL = 0x0001, PACKAGE = 0x0200, TYPE = 0x0201, TYPE_SPEC = 0x0202;
    static final int NO_ENTRY = 0xFFFFFFFF, UTF8 = 0x100;

    private Arsc() { }

    /** One new drawable: its name and the file for each (density, sdk) configuration. */
    public static final class Drawable {
        final String name;
        final Map<Long, String> files = new LinkedHashMap<>();
        public Drawable(String name) { this.name = name; }
        public Drawable file(int density, int sdk, String path) { files.put(key(density, sdk), path); return this; }
        static long key(int density, int sdk) { return ((long) density << 16) | sdk; }
    }

    public static final class Added {
        public final byte[] table;
        public final Map<String, Integer> ids;
        Added(byte[] table, Map<String, Integer> ids) { this.table = table; this.ids = ids; }
    }

    public static Added addDrawables(byte[] d, List<Drawable> drawables) {
        ByteBuffer b = le(d);
        int hs = b.getShort(2) & 0xffff;
        if (b.getInt(8) != 1) throw new IllegalArgumentException("expected one package in resources.arsc");
        int gp = hs, gsize = b.getInt(gp + 4);
        List<String> paths = new ArrayList<>();
        for (Drawable dr : drawables) for (String p : dr.files.values()) if (!paths.contains(p)) paths.add(p);
        Pool gpool = poolAppend(slice(d, gp, gsize), paths);
        Map<String, Integer> pathIndex = new LinkedHashMap<>();
        for (int i = 0; i < paths.size(); i++) pathIndex.put(paths.get(i), gpool.first + i);

        int pk = gp + gsize;
        if ((b.getShort(pk) & 0xffff) != PACKAGE) throw new IllegalArgumentException("package chunk not found");
        int phs = b.getShort(pk + 2) & 0xffff, psize = b.getInt(pk + 4);
        int typeStrings = b.getInt(pk + 268), lastType = b.getInt(pk + 272), keyStrings = b.getInt(pk + 276), lastKey = b.getInt(pk + 280);
        byte[] tpoolBytes = slice(d, pk + typeStrings, keyStrings - typeStrings);
        int typeId = poolStrings(tpoolBytes).indexOf("drawable") + 1;
        if (typeId == 0) throw new IllegalArgumentException("no drawable type");
        int ksize = b.getInt(pk + keyStrings + 4);
        List<String> names = new ArrayList<>();
        for (Drawable dr : drawables) names.add(dr.name);
        Pool kpool = poolAppend(slice(d, pk + keyStrings, ksize), names);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Map<String, Integer> ids = new LinkedHashMap<>();
        int q = pk + keyStrings + ksize;
        boolean specFound = false;
        int added = drawables.size();
        while (q < pk + psize) {
            int ct = b.getShort(q) & 0xffff, chs = b.getShort(q + 2) & 0xffff, csz = b.getInt(q + 4);
            byte[] c = slice(d, q, csz);
            if (ct == TYPE_SPEC && (c[8] & 0xff) == typeId) {
                specFound = true;
                int ec = le(c).getInt(12);
                ByteBuffer o = le(new byte[csz + added * 4]);
                o.put(c, 0, chs + ec * 4);
                for (Drawable dr : drawables) {
                    int f = 0x0100;                                                   // varies by density
                    for (long k : dr.files.keySet()) if ((k & 0xffff) >= 26) f |= 0x0400;  // and by Android version
                    o.putInt(f);
                }
                o.put(c, chs + ec * 4, csz - chs - ec * 4);
                o.putInt(4, o.capacity());
                o.putInt(12, ec + added);
                c = o.array();
                for (int i = 0; i < added; i++) ids.put(drawables.get(i).name, 0x7f000000 | (typeId << 16) | (ec + i));
            } else if (ct == TYPE && (c[8] & 0xff) == typeId) {
                if (c[9] != 0) throw new IllegalArgumentException("sparse resource tables aren't supported");
                ByteBuffer cb = le(c);
                int ec = cb.getInt(12), estart = cb.getInt(16);
                int dens = cb.getShort(20 + 14) & 0xffff, sdk = cb.getShort(20 + 24) & 0xffff;
                byte[] entries = slice(c, estart, csz - estart);
                ByteArrayOutputStream ne = new ByteArrayOutputStream();
                int[] offs = new int[ec + added];
                for (int i = 0; i < ec; i++) offs[i] = cb.getInt(chs + i * 4);
                for (int i = 0; i < added; i++) {
                    Drawable dr = drawables.get(i);
                    String path = dr.files.get(Drawable.key(dens, sdk));
                    if (path == null) { offs[ec + i] = NO_ENTRY; continue; }
                    offs[ec + i] = entries.length + ne.size();
                    ByteBuffer e = le(new byte[16]);
                    e.putShort((short) 8).putShort((short) 0).putInt(kpool.first + i);              // ResTable_entry
                    e.putShort((short) 8).put((byte) 0).put((byte) 0x03).putInt(pathIndex.get(path)); // Res_value: string
                    ne.write(e.array(), 0, 16);
                }
                int estart2 = chs + offs.length * 4;
                ByteBuffer o = le(new byte[estart2 + entries.length + ne.size()]);
                o.put(c, 0, chs);
                for (int v : offs) o.putInt(v);
                o.put(entries).put(ne.toByteArray());
                o.putInt(4, o.capacity());
                o.putInt(12, offs.length);
                o.putInt(16, estart2);
                c = o.array();
            }
            body.write(c, 0, c.length);
            q += csz;
        }
        if (!specFound) throw new IllegalArgumentException("drawable type spec not found");

        ByteArrayOutputStream pkg = new ByteArrayOutputStream();
        pkg.write(d, pk, phs);
        pkg.write(tpoolBytes, 0, tpoolBytes.length);
        pkg.write(kpool.bytes, 0, kpool.bytes.length);
        byte[] bodyBytes = body.toByteArray();
        pkg.write(bodyBytes, 0, bodyBytes.length);
        ByteBuffer pb = le(pkg.toByteArray());
        pb.putInt(4, pb.capacity());
        pb.putInt(268, typeStrings).putInt(272, lastType).putInt(276, typeStrings + tpoolBytes.length).putInt(280, lastKey);

        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write(d, 0, hs);
        t.write(gpool.bytes, 0, gpool.bytes.length);
        t.write(pb.array(), 0, pb.capacity());
        t.write(d, pk + psize, d.length - pk - psize);
        ByteBuffer tb = le(t.toByteArray());
        tb.putInt(4, tb.capacity());
        return new Added(tb.array(), ids);
    }

    /** {name: 0xAARRGGBB} for colour resources that are plain values in the default configuration. */
    public static Map<String, Integer> colourValues(byte[] d) {
        ByteBuffer b = le(d);
        int hs = b.getShort(2) & 0xffff;
        int pk = hs + b.getInt(hs + 4);
        int psize = b.getInt(pk + 4);
        int typeStrings = b.getInt(pk + 268), keyStrings = b.getInt(pk + 276);
        int ksize = b.getInt(pk + keyStrings + 4);
        List<String> keys = poolStrings(slice(d, pk + keyStrings, ksize));
        int typeId = poolStrings(slice(d, pk + typeStrings, keyStrings - typeStrings)).indexOf("color") + 1;
        Map<String, Integer> out = new LinkedHashMap<>();
        int q = pk + keyStrings + ksize;
        while (q < pk + psize) {
            int ct = b.getShort(q) & 0xffff, chs = b.getShort(q + 2) & 0xffff, csz = b.getInt(q + 4);
            if (ct == TYPE && (d[q + 8] & 0xff) == typeId && (d[q + 9] & 0xff) == 0 && allZero(d, q + 24, q + chs)) {
                int ec = b.getInt(q + 12), estart = b.getInt(q + 16);
                for (int i = 0; i < ec; i++) {
                    int off = b.getInt(q + chs + i * 4);
                    if (off == NO_ENTRY) continue;
                    int e = q + estart + off;
                    int esize = b.getShort(e) & 0xffff, eflags = b.getShort(e + 2) & 0xffff, key = b.getInt(e + 4);
                    if ((eflags & 1) != 0) continue;                       // complex (bag) entry
                    int dt = d[e + esize + 3] & 0xff, data = b.getInt(e + esize + 4);
                    if (dt >= 0x1c && dt <= 0x1f) out.put(keys.get(key), data);
                }
            }
            q += csz;
        }
        return out;
    }

    // ------------------------------------------------------------ pools

    static final class Pool {
        final byte[] bytes; final int first;
        Pool(byte[] bytes, int first) { this.bytes = bytes; this.first = first; }
    }

    static Pool poolAppend(byte[] chunk, List<String> add) {
        ByteBuffer b = le(chunk);
        int hs = b.getShort(2) & 0xffff, size = b.getInt(4), count = b.getInt(8), styles = b.getInt(12), flags = b.getInt(16);
        int sstart = b.getInt(20), ststart = b.getInt(24);
        boolean utf8 = (flags & UTF8) != 0;
        int dataEnd = styles > 0 ? ststart : size;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(chunk, sstart, dataEnd - sstart);
        byte[] styleData = styles > 0 ? slice(chunk, ststart, size - ststart) : new byte[0];
        int[] offs = new int[count + add.size()];
        for (int i = 0; i < count; i++) offs[i] = b.getInt(hs + i * 4);
        for (int i = 0; i < add.size(); i++) {
            offs[count + i] = data.size();
            encode(data, add.get(i), utf8);
        }
        while (data.size() % 4 != 0) data.write(0);
        int count2 = offs.length;
        int sstart2 = hs + count2 * 4 + styles * 4;
        int ststart2 = styles > 0 ? sstart2 + data.size() : 0;
        int size2 = sstart2 + data.size() + styleData.length;
        ByteBuffer o = le(new byte[size2]);
        o.put(chunk, 0, hs);
        o.putInt(4, size2).putInt(8, count2).putInt(20, sstart2).putInt(24, ststart2);
        o.position(hs);
        for (int v : offs) o.putInt(v);
        for (int i = 0; i < styles; i++) o.putInt(b.getInt(hs + count * 4 + i * 4));
        o.put(data.toByteArray()).put(styleData);
        return new Pool(o.array(), count);
    }

    private static void encode(ByteArrayOutputStream o, String s, boolean utf8) {
        if (utf8) {
            byte[] u = s.getBytes(StandardCharsets.UTF_8);
            len8(o, s.length()); len8(o, u.length);
            o.write(u, 0, u.length); o.write(0);
        } else {
            byte[] u = s.getBytes(StandardCharsets.UTF_16LE);
            int n = u.length / 2;
            o.write(n & 0xff); o.write(n >> 8);
            o.write(u, 0, u.length); o.write(0); o.write(0);
        }
    }

    private static void len8(ByteArrayOutputStream o, int n) {
        if (n < 0x80) o.write(n); else { o.write(0x80 | (n >> 8)); o.write(n & 0xff); }
    }

    static List<String> poolStrings(byte[] c) {
        ByteBuffer b = le(c);
        int hs = b.getShort(2) & 0xffff, count = b.getInt(8), flags = b.getInt(16), sstart = b.getInt(20);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int o = sstart + b.getInt(hs + i * 4);
            if ((flags & UTF8) != 0) {
                o += (c[o] & 0x80) != 0 ? 2 : 1;
                int n = c[o] & 0xff; o++;
                if ((n & 0x80) != 0) { n = ((n & 0x7f) << 8) | (c[o] & 0xff); o++; }
                out.add(new String(c, o, n, StandardCharsets.UTF_8));
            } else {
                int n = b.getShort(o) & 0xffff;
                out.add(new String(c, o + 2, n * 2, StandardCharsets.UTF_16LE));
            }
        }
        return out;
    }

    private static boolean allZero(byte[] d, int from, int to) {
        for (int i = from; i < to; i++) if (d[i] != 0) return false;
        return true;
    }

    private static byte[] slice(byte[] d, int from, int len) { return java.util.Arrays.copyOfRange(d, from, from + len); }

    private static ByteBuffer le(byte[] d) { return ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN); }
}
