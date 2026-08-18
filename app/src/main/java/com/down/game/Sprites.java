package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Sprites {

    public static Bitmap chromaKey(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        int corner = px[0];
        int kr = (corner >> 16) & 255, kg = (corner >> 8) & 255, kb = corner & 255;
        boolean greenBg = (kg > 150 && kg - (kr > kb ? kr : kb) > 60);
        boolean magBg   = (kr > 150 && kb > 150 && (kr < kb ? kr : kb) - kg > 60);

        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int a = 255;

            if (greenBg) {
                int ex = g - (r > b ? r : b);
                boolean olive = (g > 90 && b < 90 && g * 2 > r);
                boolean darkGreen = (g > r && g > b && g > 40 && r < 90 && b < 90);
                if (ex > 120 || darkGreen) a = 0;
                else if (ex > 60) a = (120 - ex) * 255 / 60;
                else if (olive) a = 90;
            } else if (magBg) {
                int ex = (r < b ? r : b) - g;
                if (ex > 140) a = 0;
                else if (ex > 100) a = (140 - ex) * 255 / 40;
            }

            px[i] = (a << 24) | ((r * a / 255) << 16)
                         | ((g * a / 255) << 8) | (b * a / 255);
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        out.setPremultiplied(true);
        return out;
    }

    public static List<Bitmap> cutSheet(Context ctx, String assetPath,
                                        int rows, int cols, int margin) {
        List<Bitmap> frames = new ArrayList<>();
        try {
            InputStream in = ctx.getAssets().open(assetPath);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inPremultiplied = false;
            Bitmap sheet = BitmapFactory.decodeStream(in, null, opts);
            try { in.close(); } catch (Exception ignored) {}
            if (sheet == null) return frames;
            Bitmap keyed = chromaKey(sheet);
            // Free the raw decoded sheet; cut frames share keyed's pixels.
            if (!sheet.isRecycled()) sheet.recycle();
            int cw = keyed.getWidth() / cols;
            int ch = keyed.getHeight() / rows;
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++)
                    frames.add(Bitmap.createBitmap(keyed,
                            c * cw + margin, r * ch + margin,
                            cw - 2 * margin, ch - 2 * margin));
        } catch (Exception ignored) {
        }
        return frames;
    }

    public static List<Frame> buildFrames(List<Bitmap> cells,
                                           boolean vCrop, boolean cCenter) {
        ArrayList<Frame> out = new ArrayList<>();
        for (Bitmap b : cells) {
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            boolean[] rowHas = new boolean[h];
            boolean[] colHas = new boolean[w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((px[y * w + x] >>> 24) > 16) {
                        rowHas[y] = true; colHas[x] = true;
                    }
                }
            }
            int top = runStart(rowHas, h / 2, 10);
            int bottom = runEnd(rowHas, h / 2, 10);
            int left = runStart(colHas, w / 2, 10);
            int right = runEnd(colHas, w / 2, 10);
            if (top < 0) {
                top = 0; bottom = h - 1; left = 0; right = w - 1;
            }
            Frame f = new Frame();
            f.bmp = b;
            f.top = top;
            f.ch = (bottom > top) ? bottom - top + 1 : 1;
            f.left = left;
            f.cw = (right > left) ? right - left + 1 : 1;
            f.rgt = left + f.cw;
            f.vCrop = vCrop;
            f.cCenter = cCenter;
            out.add(f);
        }

        int maxW = 0;
        for (Frame f : out) if (f.cw > maxW) maxW = f.cw;
        for (Frame f : out) f.ww = maxW;
        if (!out.isEmpty()) {
            float r = out.get(0).ch;
            for (Frame f : out) f.ref = r;
        }
        return out;
    }

    public static List<Bitmap> trimBottom(List<Bitmap> src, float keep) {
        List<Bitmap> out = new ArrayList<>();
        for (Bitmap b : src) {
            out.add(Bitmap.createBitmap(b, 0, 0,
                    b.getWidth(), (int) (b.getHeight() * keep)));
        }
        return out;
    }

    // ----- Pre-tint cache (P3): avoids per-frame ColorFilter allocations -----
    private static final Map<Long, Bitmap> TINT_CACHE = new HashMap<>();
    private static final Paint TINT_PAINT = new Paint();

    public static Bitmap tinted(Bitmap src, int color) {
        long key = ((long) System.identityHashCode(src) << 32)
                 | (color & 0xFFFFFFFFL);
        Bitmap cached = TINT_CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                                         Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        TINT_PAINT.setColorFilter(
            new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        c.drawBitmap(src, 0, 0, TINT_PAINT);
        TINT_PAINT.setColorFilter(null);
        TINT_CACHE.put(key, out);
        return out;
    }

    private static int nearestTrue(boolean[] has, int c) {
        for (int d = 0; d < has.length; d++) {
            if (c - d >= 0 && has[c - d]) return c - d;
            if (c + d < has.length && has[c + d]) return c + d;
        }
        return -1;
    }

    private static int runStart(boolean[] has, int center, int gap) {
        int c = has[center] ? center : nearestTrue(has, center);
        if (c < 0) return -1;
        int s = c, g = 0;
        for (int i = c - 1; i >= 0; i--) {
            if (has[i]) { s = i; g = 0; }
            else if (++g > gap) break;
        }
        return s;
    }

    private static int runEnd(boolean[] has, int center, int gap) {
        int c = has[center] ? center : nearestTrue(has, center);
        if (c < 0) return -1;
        int e = c, g = 0;
        for (int i = c + 1; i < has.length; i++) {
            if (has[i]) { e = i; g = 0; }
            else if (++g > gap) break;
        }
        return e;
    }
}
