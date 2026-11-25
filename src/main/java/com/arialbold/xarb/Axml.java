package com.arialbold.xarb;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads and writes Android binary XML (AndroidManifest.xml and compiled res/*.xml) as a list of
 * nodes, so elements can be added and attributes changed. Attribute names that need a resource ID
 * live in the resource-mapped start of the string pool; adding one there renumbers every string
 * reference, which {@link #attrName} takes care of.
 */
public final class Axml {
    public static final int NS_START = 0x0100, NS_END = 0x0101, EL_START = 0x0102, EL_END = 0x0103, CDATA = 0x0104;
    public static final int T_REF = 0x01, T_STRING = 0x03, T_BOOL = 0x12;
    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    public static final class Attr {
        public int ns, name, raw, size, res0, type, data;
        Attr(int ns, int name, int raw, int size, int res0, int type, int data) {
            this.ns = ns; this.name = name; this.raw = raw; this.size = size; this.res0 = res0; this.type = type; this.data = data;
        }
        Attr copy() { return new Attr(ns, name, raw, size, res0, type, data); }
    }

    public static final class Node {
        public int type, line, comment;
        public int prefix, uri;                         // namespace nodes
        public int ns, name, idi, cli, sti;             // elements
        public List<Attr> attrs = new ArrayList<>();
        public int sdata, vsz, r0, dt, cdata;           // text
        Node(int type, int line, int comment) { this.type = type; this.line = line; this.comment = comment; }
        Node copy() {
            Node n = new Node(type, line, comment);
            n.prefix = prefix; n.uri = uri; n.ns = ns; n.name = name; n.idi = idi; n.cli = cli; n.sti = sti;
            for (Attr a : attrs) n.attrs.add(a.copy());
            n.sdata = sdata; n.vsz = vsz; n.r0 = r0; n.dt = dt; n.cdata = cdata;
            return n;
        }
    }

    private final boolean utf8;
    public final List<String> strings = new ArrayList<>();
    public final List<Integer> resmap = new ArrayList<>();
    public final List<Node> nodes = new ArrayList<>();

    public Axml(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xffff) != 0x0003) throw new IllegalArgumentException("not binary XML");
        int size = b.getInt(4);
        int p = b.getShort(2) & 0xffff;
        int shs = b.getShort(p + 2) & 0xffff, ssz = b.getInt(p + 4), n = b.getInt(p + 8), nst = b.getInt(p + 12);
        int fl = b.getInt(p + 16), sstart = b.getInt(p + 20);
        if ((b.getShort(p) & 0xffff) != 0x0001 || nst != 0) throw new IllegalArgumentException("unsupported string pool");
        utf8 = (fl & 0x100) != 0;
        for (int i = 0; i < n; i++) {
            int o = p + sstart + b.getInt(p + shs + i * 4);
            if (utf8) {
                o += (d[o] & 0x80) != 0 ? 2 : 1;
                int l = d[o] & 0xff; o++;
                if ((l & 0x80) != 0) { l = ((l & 0x7f) << 8) | (d[o] & 0xff); o++; }
                strings.add(new String(d, o, l, StandardCharsets.UTF_8));
            } else {
                int l = b.getShort(o) & 0xffff;
                if ((l & 0x8000) != 0) { l = ((l & 0x7fff) << 16) | (b.getShort(o + 2) & 0xffff); o += 2; }
                strings.add(new String(d, o + 2, l * 2, StandardCharsets.UTF_16LE));
            }
        }
        p += ssz;
        if ((b.getShort(p) & 0xffff) == 0x0180) {
            int rsz = b.getInt(p + 4);
            for (int i = 0; i < (rsz - 8) / 4; i++) resmap.add(b.getInt(p + 8 + i * 4));
            p += rsz;
        }
        while (p < size) {
            int ct = b.getShort(p) & 0xffff, chs = b.getShort(p + 2) & 0xffff, csz = b.getInt(p + 4);
            Node nd = new Node(ct, b.getInt(p + 8), b.getInt(p + 12));
            int q = p + chs;
            switch (ct) {
                case NS_START: case NS_END: nd.prefix = b.getInt(q); nd.uri = b.getInt(q + 4); break;
                case EL_START: {
                    nd.ns = b.getInt(q); nd.name = b.getInt(q + 4);
                    int astart = b.getShort(q + 8) & 0xffff, asize = b.getShort(q + 10) & 0xffff, acount = b.getShort(q + 12) & 0xffff;
                    nd.idi = b.getShort(q + 14) & 0xffff; nd.cli = b.getShort(q + 16) & 0xffff; nd.sti = b.getShort(q + 18) & 0xffff;
                    for (int i = 0; i < acount; i++) {
                        int a = q + astart + i * asize;
                        nd.attrs.add(new Attr(b.getInt(a), b.getInt(a + 4), b.getInt(a + 8), b.getShort(a + 12) & 0xffff,
                                d[a + 14] & 0xff, d[a + 15] & 0xff, b.getInt(a + 16)));
                    }
                    break;
                }
                case EL_END: nd.ns = b.getInt(q); nd.name = b.getInt(q + 4); break;
                case CDATA:
                    nd.sdata = b.getInt(q); nd.vsz = b.getShort(q + 4) & 0xffff; nd.r0 = d[q + 6] & 0xff; nd.dt = d[q + 7] & 0xff;
                    nd.cdata = b.getInt(q + 8);
                    break;
                default: throw new IllegalArgumentException("unknown XML chunk " + Integer.toHexString(ct));
            }
            nodes.add(nd);
            p += csz;
        }
    }

    // ------------------------------------------------------------ strings

    public String s(int i) { return i < 0 || i >= strings.size() ? null : strings.get(i); }

    /** Index of a plain (non-attribute-name) string, adding it if needed. */
    public int addString(String v) {
        for (int i = resmap.size(); i < strings.size(); i++) if (strings.get(i).equals(v)) return i;
        strings.add(v);
        return strings.size() - 1;
    }

    /** Index of an attribute-name string with this resource ID, inserting it into the mapped part if needed. */
    public int attrName(String name, int resid) {
        for (int i = 0; i < resmap.size(); i++) if (resmap.get(i) == resid && strings.get(i).equals(name)) return i;
        int k = resmap.size();
        shiftFrom(k);
        strings.add(k, name);
        resmap.add(resid);
        return k;
    }

    private void shiftFrom(int k) {
        for (Node n : nodes) {
            n.comment = f(n.comment, k);
            switch (n.type) {
                case NS_START: case NS_END: n.prefix = f(n.prefix, k); n.uri = f(n.uri, k); break;
                case EL_START:
                    n.ns = f(n.ns, k); n.name = f(n.name, k);
                    for (Attr a : n.attrs) {
                        a.ns = f(a.ns, k); a.name = f(a.name, k); a.raw = f(a.raw, k);
                        if (a.type == T_STRING) a.data = f(a.data, k);
                    }
                    break;
                case EL_END: n.ns = f(n.ns, k); n.name = f(n.name, k); break;
                case CDATA: n.sdata = f(n.sdata, k); if (n.dt == T_STRING) n.cdata = f(n.cdata, k); break;
                default: break;
            }
        }
    }

    private static int f(int i, int k) { return i >= k ? i + 1 : i; }

    // ------------------------------------------------------------- write

    public byte[] toBytes() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offs = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offs[i] = data.size();
            String v = strings.get(i);
            if (utf8) {
                byte[] u = v.getBytes(StandardCharsets.UTF_8);
                len8(data, v.length());
                len8(data, u.length);
                data.write(u, 0, u.length);
                data.write(0);
            } else {
                byte[] u = v.getBytes(StandardCharsets.UTF_16LE);
                int chars = u.length / 2;
                data.write(chars & 0xff); data.write(chars >> 8);
                data.write(u, 0, u.length);
                data.write(0); data.write(0);
            }
        }
        while (data.size() % 4 != 0) data.write(0);
        int n = strings.size();
        ByteBuffer sp = le(28 + n * 4 + data.size());
        sp.putShort((short) 0x0001).putShort((short) 28).putInt(sp.capacity()).putInt(n).putInt(0)
                .putInt(utf8 ? 0x100 : 0).putInt(28 + n * 4).putInt(0);
        for (int o : offs) sp.putInt(o);
        sp.put(data.toByteArray());
        ByteBuffer rm = le(8 + resmap.size() * 4);
        rm.putShort((short) 0x0180).putShort((short) 8).putInt(rm.capacity());
        for (int r : resmap) rm.putInt(r);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Node nd : nodes) {
            ByteBuffer c;
            switch (nd.type) {
                case NS_START: case NS_END:
                    c = le(24); head(c, nd, 24); c.putInt(nd.prefix).putInt(nd.uri); break;
                case EL_START:
                    c = le(36 + nd.attrs.size() * 20); head(c, nd, c.capacity());
                    c.putInt(nd.ns).putInt(nd.name).putShort((short) 20).putShort((short) 20).putShort((short) nd.attrs.size())
                            .putShort((short) nd.idi).putShort((short) nd.cli).putShort((short) nd.sti);
                    for (Attr a : nd.attrs) {
                        c.putInt(a.ns).putInt(a.name).putInt(a.raw).putShort((short) a.size).put((byte) a.res0).put((byte) a.type).putInt(a.data);
                    }
                    break;
                case EL_END:
                    c = le(24); head(c, nd, 24); c.putInt(nd.ns).putInt(nd.name); break;
                default:
                    c = le(28); head(c, nd, 28);
                    c.putInt(nd.sdata).putShort((short) nd.vsz).put((byte) nd.r0).put((byte) nd.dt).putInt(nd.cdata);
            }
            body.write(c.array(), 0, c.capacity());
        }
        ByteBuffer out = le(8 + sp.capacity() + rm.capacity() + body.size());
        out.putShort((short) 0x0003).putShort((short) 8).putInt(out.capacity());
        out.put(sp.array()).put(rm.array()).put(body.toByteArray());
        return out.array();
    }

    private static void head(ByteBuffer c, Node nd, int size) {
        c.putShort((short) nd.type).putShort((short) 16).putInt(size).putInt(nd.line).putInt(nd.comment);
    }

    private static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }

    private static void len8(ByteArrayOutputStream o, int n) {
        if (n < 0x80) o.write(n); else { o.write(0x80 | (n >> 8)); o.write(n & 0xff); }
    }

    // ------------------------------------------------------------ helpers

    /** First start element with this name (and attribute value, if given), from index start; -1 if none. */
    public int findElement(String name, String attr, String value, int start) {
        for (int i = start; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (n.type != EL_START || !name.equals(s(n.name))) continue;
            if (attr == null) return i;
            for (Attr a : n.attrs) if (attr.equals(s(a.name)) && value.equals(s(a.raw))) return i;
        }
        return -1;
    }

    /** Index of the end node matching the start element at i. */
    public int endOf(int i) {
        int depth = 0;
        for (int j = i; j < nodes.size(); j++) {
            int t = nodes.get(j).type;
            if (t == EL_START) depth++;
            else if (t == EL_END && --depth == 0) return j;
        }
        return -1;
    }

    /** One attribute for {@link #element}. Call {@link #attrName} for every name first. */
    public static final class A {
        final String name; final int resid; final String kind; final Object value;
        public A(String name, int resid, String kind, Object value) { this.name = name; this.resid = resid; this.kind = kind; this.value = value; }
    }

    /** Start and end nodes for an element with android: attributes (kinds "string", "ref", "bool"). */
    public Node[] element(String name, A... attrs) {
        int android = strings.indexOf(ANDROID_NS);
        if (android < 0) throw new IllegalStateException("no android namespace");
        List<A> sorted = new ArrayList<>(List.of(attrs));
        sorted.sort(Comparator.comparingInt(a -> a.resid));
        Node st = new Node(EL_START, 1, -1);
        for (A a : sorted) {
            int ni = -1;
            for (int i = 0; i < resmap.size(); i++) if (resmap.get(i) == a.resid && strings.get(i).equals(a.name)) ni = i;
            if (ni < 0) throw new IllegalStateException("attribute name " + a.name + " not mapped");
            switch (a.kind) {
                case "string": { int si = addString((String) a.value); st.attrs.add(new Attr(android, ni, si, 8, 0, T_STRING, si)); break; }
                case "ref": st.attrs.add(new Attr(android, ni, -1, 8, 0, T_REF, (Integer) a.value)); break;
                default: st.attrs.add(new Attr(android, ni, -1, 8, 0, T_BOOL, (Boolean) a.value ? -1 : 0));
            }
        }
        int nm = addString(name);
        st.ns = -1; st.name = nm;
        Node en = new Node(EL_END, 1, -1);
        en.ns = -1; en.name = nm;
        return new Node[] {st, en};
    }

    public static Node copy(Node n) { return n.copy(); }
}
