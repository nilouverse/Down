package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.SystemClock;
import java.io.InputStream;

/**
 * Story-map compositor, rebuilt clean. Section-by-section art direction:
 * currently only ASHEN is composed; unbuilt regions render flat zone color.
 * Passes: 1 terrain (exact-fit) -> 2 flat decals -> 3 Y-sorted upright props
 * -> 4 additive glow. draw() allocates ZERO objects.
 */
public final class SceneMap {
    public static final float HEX = 96f, SQUASH = 0.6f, BAKE = 2f;
    public static final int CHUNK_PX = 1024, SRC = 512;
    public static final int MIN_Q = -32, MAX_Q = 96, MIN_R = -24, MAX_R = 24;
    public static final int W_Q = MAX_Q - MIN_Q + 1, W_R = MAX_R - MIN_R + 1;

    private static final float TS = 128f, SQ3 = 1.7320508f;
    private static final int MAXT = 8192, PMAX = 1024;

    private final boolean[] walkable = new boolean[W_Q * W_R];
    private volatile Bitmap tAsh, tRoad, tCity, tCrater, gGlow, pA, pB;
    private volatile boolean ready, disposed;
    private final boolean quality;
    private boolean craterVisible;
    private Bitmap craterGlow;

    private final Paint tp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint gp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect srcR = new Rect(), dstR = new Rect();
    private final float[] HW = new float[2];
    private final Bitmap[] sheets = new Bitmap[4];
    private final int[] tS = new int[MAXT], tI = new int[MAXT], tR = new int[MAXT],
            dI = new int[MAXT], pI = new int[MAXT], gI = new int[MAXT];
    private final float[] pBX = new float[PMAX], pBY = new float[PMAX];
    private final int[] pBI = new int[PMAX];

    // decal idx: 0..15 props_a. prop idx: 0..15 props_b, 16..20 props_a via P_A.
    private static final float[] D_S = {1.2f,1.4f,1.5f,1.4f,1.2f,1.0f,2.4f,2.4f,1.7f,1.6f,1.5f,1.6f,1.3f,1.2f,1.3f,1.4f};
    private static final int[]   D_A = {255,255,235,255,255,255,90,90,150,170,180,180,255,255,235,140};
    private static final float[] P_S = {1.6f,1.5f,2.6f,1.3f,2.2f,1.8f,2.0f,2.4f,1.6f,1.2f,1.4f,1.7f,1.2f,1.4f,1.6f,1.8f,1.6f,1.9f,1.2f,1.0f,1.4f};
    private static final int[]   P_A = {0, 1, 4, 5, 12, 13};
    private static final float[] G_S = {1.8f,2.0f,1.1f,1.1f,6.5f,2.8f,1.7f,3.2f,1.3f,2.2f,2.6f,1.2f,2.0f,1.3f,2.8f,1.0f};

    public SceneMap(Context ctx, boolean quality) {
        this.quality = quality;
        buildWalkability();
        gp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        bakeCraterGlow();
        final Context app = ctx.getApplicationContext();
        Thread loader = new Thread(new Runnable() { public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            Bitmap a = dec(app, "map/ash", true), ro = dec(app, "map/road", true),
                    ci = dec(app, "map/city", true), cr = dec(app, "map/crater", true),
                    gl = dec(app, "map/glow", false),
                    pa = key(dec(app, "map/props_a", false)),
                    pb = key(dec(app, "map/props_b", false));
            if (disposed) { recycle(a); recycle(ro); recycle(ci); recycle(cr); recycle(gl); recycle(pa); recycle(pb); return; }
            tAsh = a; tRoad = ro; tCity = ci; tCrater = cr; gGlow = gl; pA = pa; pB = pb;
            ready = true;
        } }, "map-load");
        loader.setDaemon(true);
        loader.start();
    }

    private static Bitmap dec(Context c, String base, boolean opaque) {
        String[] ext = {".webp", ".png", ".jpg"};
        for (int i = 0; i < ext.length; i++) {
            Bitmap b = null;
            try {
                InputStream in = c.getAssets().open(base + ext[i]);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = opaque ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
                o.inMutable = !opaque;
                b = BitmapFactory.decodeStream(in, null, o);
                in.close();
            } catch (Exception e) { b = null; }
            if (b != null) return b;
        }
        return null;
    }

    // Subtractive magenta key: alpha FROM magenta strength, magenta SUBTRACTED from R/B.
    private static Bitmap key(Bitmap b) {
        if (b == null) return null;
        if (!b.isMutable()) { Bitmap m = b.copy(Bitmap.Config.ARGB_8888, true); if (m == null) return b; b = m; }
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int c = px[i], r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
            int mg = (r < bl ? r : bl) - g;
            if (mg <= 20) continue;
            float k = mg / 200f; if (k > 1f) k = 1f;
            int a = (int) ((1f - k) * 255f + 0.5f);
            if (a < 10) { px[i] = 0; continue; }
            int sub = (int) (255f * k + 0.5f);
            r -= sub; if (r < 0) r = 0;
            bl -= sub; if (bl < 0) bl = 0;
            px[i] = (a << 24) | (r << 16) | (g << 8) | bl;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    public static void hexToWorld(int q, int r, float[] out) {
        out[0] = HEX * SQ3 * (q + r / 2f);
        out[1] = HEX * 1.5f * r * SQUASH;
    }
    public static void worldToHex(float x, float y, float[] out) {
        float hy = y / SQUASH;
        float qf = (0.57735027f * x - 0.33333334f * hy) / HEX;
        float rf = (0.6666667f * hy) / HEX;
        float sf = -qf - rf;
        int rq = Math.round(qf), rr = Math.round(rf), rs = Math.round(sf);
        float dq = Math.abs(rq - qf), dr = Math.abs(rr - rf), ds = Math.abs(rs - sf);
        if (dq > dr && dq > ds) rq = -rr - rs; else if (dr > ds) rr = -rq - rs;
        out[0] = rq; out[1] = rr;
    }
    public static void worldToHex(float x, float y, int[] out) {
        float hy = y / SQUASH;
        float qf = (0.57735027f * x - 0.33333334f * hy) / HEX;
        float rf = (0.6666667f * hy) / HEX;
        float sf = -qf - rf;
        int rq = Math.round(qf), rr = Math.round(rf), rs = Math.round(sf);
        float dq = Math.abs(rq - qf), dr = Math.abs(rr - rf), ds = Math.abs(rs - sf);
        if (dq > dr && dq > ds) rq = -rr - rs; else if (dr > ds) rr = -rq - rs;
        out[0] = rq; out[1] = rr;
    }

    private void buildWalkability() {
        for (int r = MIN_R; r <= MAX_R; r++) for (int q = MIN_Q; q <= MAX_Q; q++) {
            boolean w;
            if (q <= 10) w = r >= -4 && r <= 12;
            else if (q <= 36) { float t = (q - 11) / 25f; w = Math.abs(r - (2 - 7f * t)) <= 2.6f - 0.8f * t; }
            else if (q <= 60) w = r >= -6 && r <= 12 && (r % 5 == 0 || q % 6 == 0 || insidePlaza(q, r)) && !insideRubble(q, r);
            else if (q <= 78) { float dx = (q - 66) / 10f, dy = (r - 4) / 8f; w = dx * dx + dy * dy <= 1f || (q >= 56 && q <= 78 && r >= 2 && r <= 6); }
            else w = r >= -2 && r <= 10;
            walkable[(r - MIN_R) * W_Q + (q - MIN_Q)] = w;
        }
    }
    private static boolean insidePlaza(int q, int r) { return q >= 44 && q <= 50 && r >= 2 && r <= 8; }
    private static boolean insideRubble(int q, int r) {
        return (q >= 40 && q <= 41 && r >= 0 && r <= 3) || (q >= 46 && q <= 47 && r >= 9 && r <= 11)
                || (q >= 52 && q <= 53 && r >= -6 && r <= -1) || (q >= 53 && q <= 54 && r >= -2 && r <= 1);
    }
    public boolean walk(int q, int r) {
        if (q < MIN_Q || q > MAX_Q || r < MIN_R || r > MAX_R) return false;
        return walkable[(r - MIN_R) * W_Q + (q - MIN_Q)];
    }
    public boolean walkWorld(float wx, float wy) {
        worldToHex(wx, wy, HW);
        return walk((int) HW[0], (int) HW[1]);
    }

    private static int h2(int x, int y, int s) {
        int h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ s * 0x9E3779B1;
        h ^= h >>> 15; h *= 0x85EBCA6B; h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }
    private static float vn(float x, float y, int seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = x - x0, fy = y - y0;
        fx = fx * fx * (3f - 2f * fx); fy = fy * fy * (3f - 2f * fy);
        int a = h2(x0, y0, seed) & 1023, b = h2(x0 + 1, y0, seed) & 1023;
        int c = h2(x0, y0 + 1, seed) & 1023, d = h2(x0 + 1, y0 + 1, seed) & 1023;
        float t = a + (b - a) * fx, u = c + (d - c) * fx;
        return (t + (u - t) * fy) * (1f / 1023f);
    }

    // ================= TILE COMPOSER (ASHEN only, for now) =================
    private void computeTile(int i, int tx, int ty) {
        float wx = tx * TS + TS * 0.5f, wy = ty * TS + TS * 0.5f;
        float qf = wx * 0.0060141f - wy * 0.0057870f;
        float rf = wy * 0.0115741f;
        int h = h2(tx, ty, 7), hB = h2(tx, ty, 91);
        float n2 = vn(tx * 0.11f, ty * 0.11f, 57);
        int ts = -1, ti = 0, rot = 0, di = -1, pr = -1, gl = -1;

        if (qf < 10.5f) {                                   // ---- ASHEN FIELDS ----
            ts = 0;
            if (rf < -7.7f) ti = 15;                        // a16 void
            else if (rf < -4.5f) {                          // a11/a12 cliff strata band
                ti = 10 + ((h >>> 3) & 1);
                if ((h >>> 10) % 100 < 6) pr = 16 + ((h >>> 14) & 1);   // pa1/pa2 boulders
                if ((h >>> 13) % 1000 < 3) gl = 13;                     // g14 blue wisp
            } else {                                        // walkable meadow
                ti = 12;                                    // a13 scorched, one material
                rot = (h >>> 12) & 3;                       // 0/90/180/270
                if ((h >>> 4) % 1000 < 6) gl = 6;                       // g7 stray sparks
                if (qf > -5f && qf < 12f && rf > -3f && rf < 2f
                        && n2 > 0.6f && (h >>> 4) % 100 < 9)
                    gl = ((h >>> 9) & 1) == 0 ? 0 : 10;                 // g1/g11 ember veins
                if ((h >>> 5) % 100 < 6) di = 6 + ((h >>> 17) & 1);     // pa7/pa8 patches
                if (quality && (hB >>> 20) % 100 < 2) di = 8;           // pa9 hairline crack
                if ((hB >>> 16) % 1000 < 7) pr = 18 + ((hB >>> 20) & 1); // pa5/pa6 bone piles
                if ((hB >>> 22) % 1000 < 4) pr = 20;                    // pa13 dead shrub
                if ((h >>> 20) % 1000 < 5) pr = 15;                     // pb16 ash mound
            }
        }
        // other regions: ts stays -1 -> flat zone color until composed.
        tS[i] = ts; tI[i] = ti; tR[i] = rot; dI[i] = di; pI[i] = pr; gI[i] = gl;
    }

    // ================= DRAW (zero allocation) =================
    public void draw(Canvas c, float camX, float camY, float zoom, int vw, int vh) {
        float halfW = vw / (2f * zoom), halfH = vh / (2f * zoom);
        int x0 = (int) Math.floor((camX - halfW) / TS) - 1;
        int x1 = (int) Math.floor((camX + halfW) / TS) + 1;
        int y0 = (int) Math.floor((camY - halfH) / TS) - 1;
        int y1 = (int) Math.floor((camY + halfH) / TS) + 1;
        int n = (x1 - x0 + 1) * (y1 - y0 + 1);
        boolean full = ready && n <= MAXT;
        sheets[0] = tAsh; sheets[1] = tRoad; sheets[2] = tCity; sheets[3] = tCrater;
        if (full) {
            int i = 0;
            for (int ty = y0; ty <= y1; ty++)
                for (int tx = x0; tx <= x1; tx++, i++) computeTile(i, tx, ty);
        }
        // PASS 1 — terrain, exact-fit tiles, shared boundaries, ZERO overdraw
        for (int ty = y0, i = 0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++, i++) {
                float sx = (tx * TS - camX) * zoom + vw * 0.5f;
                float sy = (ty * TS - camY) * zoom + vh * 0.5f;
                int L = (int) Math.floor(sx), T = (int) Math.floor(sy);
                int R = (int) Math.floor(sx + TS * zoom), B = (int) Math.floor(sy + TS * zoom);
                Bitmap b = (full && tS[i] >= 0) ? sheets[tS[i]] : null;
                if (b == null) {
                    tp.setColor(fallback(tx, ty));
                    dstR.set(L, T, R, B);
                    c.drawRect(dstR, tp);
                    continue;
                }
                cell(b, tI[i]);
                if (tR[i] == 0) {
                    dstR.set(L, T, R, B);
                    c.drawBitmap(b, srcR, dstR, tp);
                } else {
                    int w = R - L, hgt = B - T;
                    c.save();
                    c.translate((L + R) * 0.5f, (T + B) * 0.5f);
                    c.rotate(tR[i] * 90f);
                    if ((tR[i] & 1) == 1) dstR.set(-(hgt >> 1), -(w >> 1), (hgt + 1) >> 1, (w + 1) >> 1);
                    else dstR.set(-(w >> 1), -(hgt >> 1), (w + 1) >> 1, (hgt + 1) >> 1);
                    c.drawBitmap(b, srcR, dstR, tp);
                    c.restore();
                }
            }
        }
        if (!full) { if (craterVisible) drawCrater(c, camX, camY, zoom, vw, vh); return; }

        // PASS 2 — flat decals
        if (pA != null) {
            for (int ty = y0, i = 0; ty <= y1; ty++) {
                for (int tx = x0; tx <= x1; tx++, i++) {
                    int d = dI[i];
                    if (d < 0) continue;
                    int h = h2(tx, ty, 7);
                    float s = D_S[d] * TS * zoom;
                    float cx = (tx * TS + TS * 0.5f - camX) * zoom + vw * 0.5f
                            + (((h >>> 8) & 255) / 255f - 0.5f) * TS * 0.4f * zoom;
                    float cy = (ty * TS + TS * 0.5f - camY) * zoom + vh * 0.5f
                            + (((h >>> 16) & 255) / 255f - 0.5f) * TS * 0.4f * zoom;
                    pp.setAlpha(D_A[d]);
                    cell(pA, d);
                    dstR.set((int) (cx - s * 0.5f), (int) (cy - s * 0.5f), (int) (cx + s * 0.5f), (int) (cy + s * 0.5f));
                    c.drawBitmap(pA, srcR, dstR, pp);
                }
            }
            pp.setAlpha(255);
        }

        // PASS 3 — upright props, bottom-anchored, Y-sorted
        int pc = 0;
        for (int ty = y0, i = 0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++, i++) {
                if (pI[i] < 0 || pc >= PMAX) continue;
                int h = h2(tx, ty, 7);
                float wx = tx * TS + TS * 0.5f + (((h >>> 8) & 255) / 255f - 0.5f) * TS * 0.45f;
                float wy = ty * TS + TS * (0.68f + ((h >>> 16) & 255) / 255f * 0.3f);
                pBX[pc] = (wx - camX) * zoom + vw * 0.5f;
                pBY[pc] = (wy - camY) * zoom + vh * 0.5f;
                pBI[pc] = pI[i];
                pc++;
            }
        }
        for (int a = 1; a < pc; a++) {
            float ky = pBY[a], kx = pBX[a]; int ki = pBI[a], j = a - 1;
            while (j >= 0 && pBY[j] > ky) {
                pBY[j + 1] = pBY[j]; pBX[j + 1] = pBX[j]; pBI[j + 1] = pBI[j]; j--;
            }
            pBY[j + 1] = ky; pBX[j + 1] = kx; pBI[j + 1] = ki;
        }
        for (int a = 0; a < pc; a++) {
            int p = pBI[a];
            Bitmap b = p < 16 ? pB : pA;
            if (b == null) continue;
            cell(b, p < 16 ? p : P_A[p - 16]);
            float s = P_S[p] * TS * zoom, x = pBX[a], y = pBY[a];
            dstR.set((int) (x - s * 0.5f), (int) (y - s), (int) (x + s * 0.5f), (int) y);
            c.drawBitmap(b, srcR, dstR, pp);
        }

        // PASS 4 — additive glow
        if (gGlow != null) {
            for (int ty = y0, i = 0; ty <= y1; ty++) {
                for (int tx = x0; tx <= x1; tx++, i++) {
                    int g = gI[i];
                    if (g < 0) continue;
                    float s = G_S[g] * TS * zoom;
                    float cx = (tx * TS + TS * 0.5f - camX) * zoom + vw * 0.5f;
                    float cy = (ty * TS + TS * 0.5f - camY) * zoom + vh * 0.5f;
                    cell(gGlow, g);
                    dstR.set((int) (cx - s * 0.5f), (int) (cy - s * 0.5f), (int) (cx + s * 0.5f), (int) (cy + s * 0.5f));
                    c.drawBitmap(gGlow, srcR, dstR, gp);
                }
            }
        }
        if (craterVisible) drawCrater(c, camX, camY, zoom, vw, vh);
    }

    private void cell(Bitmap b, int idx) {
        int cw = b.getWidth() >> 2, ch = b.getHeight() >> 2;
        srcR.set((idx & 3) * cw, (idx >> 2) * ch, ((idx & 3) + 1) * cw, ((idx >> 2) + 1) * ch);
    }

    private int fallback(int tx, int ty) {
        float wx = tx * TS + TS * 0.5f, wy = ty * TS + TS * 0.5f;
        float qf = wx * 0.0060141f - wy * 0.0057870f, rf = wy * 0.0115741f;
        if (qf < 10.5f) return rf < -4.5f ? 0xFF0c0b0e : 0xFF322d2b;
        if (qf < 36.5f) return 0xFF3a332e;
        if (qf < 60f) return 0xFF353136;
        if (qf < 78.5f) return 0xFF7a7264;
        return 0xFF1a2420;
    }

    public void setCraterVisible(boolean v) { craterVisible = v; }

    private void drawCrater(Canvas c, float camX, float camY, float zoom, int vw, int vh) {
        if (craterGlow == null) return;
        hexToWorld(88, 4, HW);
        float gx = (HW[0] - camX) * zoom + vw * 0.5f;
        float gy = (HW[1] - camY) * zoom + vh * 0.5f;
        float r = 500f * zoom;
        float pulse = 0.72f + 0.28f * (float) Math.sin(SystemClock.uptimeMillis() * 0.0025f);
        gp.setAlpha((int) (pulse * 255f));
        dstR.set((int) (gx - r), (int) (gy - r), (int) (gx + r), (int) (gy + r));
        c.drawBitmap(craterGlow, null, dstR, gp);
        gp.setAlpha(255);
    }

    private void bakeCraterGlow() {
        craterGlow = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        int[] px = new int[512 * 512];
        for (int y = 0; y < 512; y++) {
            float dy = (y - 255.5f) / 255.5f;
            for (int x = 0; x < 512; x++) {
                float dx = (x - 255.5f) / 255.5f;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > 1f) { px[y * 512 + x] = 0; continue; }
                float f = 1f - d; f *= f;
                float fl = 0.9f + 0.1f * ((h2(x, y, 99) & 1023) / 1023f);
                int a = (int) (f * 255f * fl);
                px[y * 512 + x] = (a << 24) | ((int) (30f * f * fl) << 16)
                        | ((int) (235f * f * fl) << 8) | (int) (120f * f * fl);
            }
        }
        craterGlow.setPixels(px, 0, 512, 0, 0, 512, 512);
    }

    public void dispose() {
        ready = false;
        disposed = true;
        recycle(tAsh); recycle(tRoad); recycle(tCity); recycle(tCrater);
        recycle(gGlow); recycle(pA); recycle(pB); recycle(craterGlow);
        tAsh = tRoad = tCity = tCrater = gGlow = pA = pB = craterGlow = null;
    }
    private static void recycle(Bitmap b) { if (b != null && !b.isRecycled()) b.recycle(); }
}
