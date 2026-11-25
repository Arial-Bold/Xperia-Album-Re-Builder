package com.arialbold.xarb;

import java.awt.image.BufferedImage;

/**
 * Recolours Album's orange to another hue, keeping lightness and design: coloured pixels take the
 * new hue with adjusted saturation and lightness; near-grey pixels only take the new hue.
 */
final class Recolor {
    private Recolor() { }

    /** spec: hue (0-1), saturation factor, lightness shift; null keeps the image as it is. */
    static BufferedImage image(BufferedImage im, double[] spec) {
        BufferedImage out = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                out.setRGB(x, y, spec == null ? im.getRGB(x, y) : pixel(im.getRGB(x, y), spec));
            }
        }
        return out;
    }

    /** One ARGB pixel (alpha kept). */
    static int pixel(int argb, double[] spec) {
        if (spec == null) return argb;
        double[] hls = rgbToHls(((argb >> 16) & 255) / 255.0, ((argb >> 8) & 255) / 255.0, (argb & 255) / 255.0);
        double h = spec[0], l = hls[1], s = hls[2];
        if (s > 0.08) {
            s = Math.min(1, s * spec[1]);
            l = Math.max(0, Math.min(1, l + spec[2]));
        } else {
            s = s * spec[1];
        }
        double[] rgb = hlsToRgb(h, l, s);
        return (argb & 0xFF000000) | (to255(rgb[0]) << 16) | (to255(rgb[1]) << 8) | to255(rgb[2]);
    }

    private static int to255(double v) { return (int) Math.rint(v * 255); }

    // Python's colorsys, exactly

    static double[] rgbToHls(double r, double g, double b) {
        double maxc = Math.max(r, Math.max(g, b)), minc = Math.min(r, Math.min(g, b));
        double sumc = maxc + minc, rangec = maxc - minc, l = sumc / 2.0;
        if (minc == maxc) return new double[] {0.0, l, 0.0};
        double s = l <= 0.5 ? rangec / sumc : rangec / (2.0 - maxc - minc);
        double rc = (maxc - r) / rangec, gc = (maxc - g) / rangec, bc = (maxc - b) / rangec;
        double h;
        if (r == maxc) h = bc - gc;
        else if (g == maxc) h = 2.0 + rc - bc;
        else h = 4.0 + gc - rc;
        return new double[] {mod1(h / 6.0), l, s};
    }

    static double[] hlsToRgb(double h, double l, double s) {
        if (s == 0.0) return new double[] {l, l, l};
        double m2 = l <= 0.5 ? l * (1.0 + s) : l + s - (l * s);
        double m1 = 2.0 * l - m2;
        return new double[] {v(m1, m2, h + 1.0 / 3.0), v(m1, m2, h), v(m1, m2, h - 1.0 / 3.0)};
    }

    private static double v(double m1, double m2, double hue) {
        hue = mod1(hue);
        if (hue < 1.0 / 6.0) return m1 + (m2 - m1) * hue * 6.0;
        if (hue < 0.5) return m2;
        if (hue < 2.0 / 3.0) return m1 + (m2 - m1) * (2.0 / 3.0 - hue) * 6.0;
        return m1;
    }

    private static double mod1(double x) { return x - Math.floor(x); }
}
