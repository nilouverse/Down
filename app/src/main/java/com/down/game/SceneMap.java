package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

public final class SceneMap {
    public static final float HEX = 96f, SQUASH = 0.6f, BAKE = 2f;
    public static final int CHUNK_PX = 1024, SRC = CHUNK_PX / (int) BAKE;
    public static final int MIN_Q = -32, MAX_Q = 96, MIN_R = -24, MAX_R = 24;
    public static final int W_Q = MAX_Q - MIN_Q + 1, W_R = MAX_R - MIN_R + 1;

    private static final float TS = 128f;              // world size of one placed tile
    private static final int CELL = 256;               // art cell px inside each sheet
    private static final int MAXT = 8192;              // scratch capacity (tiles/frame)

    private final boolean[] walkable = new boolean[W_Q * W_R];
    private volatile Bitmap tAsh, tRoad, tCity, tCrater, gGlow, pA, pB;
    private volatile boolean ready;

    private final Paint tp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint gp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect srcR = new Rect(), dstR = new Rect();
    private final float[] HW = new float[2];

    // per-tile scratch (allocation-free)
    private final int[] tS = new int[MAXT], tI = new int[MAXT], tR = new int[MAXT];
    private final int[] dS = new int[MAXT], dI = new int[MAXT];
    private final int[] pI = new int[MAXT], gI = new int[MAXT], gR = new int[MAXT];

    private boolean quality;
    private Bitmap craterGlow; private boolean craterVisible;

    // decal/prop/glow draw scales (in tiles) + decal alpha
    private static final float[] D_S = {1.2f,1.3f,1.4f,1.2f,1.1f,0.8f,2.6f,2.6f,1.6f,1.5f,1.4f,1.6f,1.2f,1.0f,1.2f,1.4f};
    private static final int[]   D_A = {255,255,255,255,255,255,90,90,150,170,170,170,255,255,255,130};
    private static final float[] P_S = {1.5f,1.7f,2.4f,1.4f,1.9f,1.8f,2.2f,2.0f,1.7f,1.3f,1.5f,1.6f,1.2f,1.5f,1.8f,1.5f};
    private static final float[] G_S = {1.6f,1.8f,1.0f,1.0f,6.0f,2.6f,1.6f,3.0f,1.2f,2.0f,2.4f,1.0f,1.8f,1.2f,2.6f,1.0f};

    public SceneMap(Context ctx, boolean quality) {
        this.quality = quality;
        buildWalkability();
        gp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        bakeCraterGlow();
        final Context app = ctx.getApplicationContext();
        Thread loader = new Thread(new Runnable() { public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            tAsh    = decAny(app, "map/ash",    true);
            tRoad   = decAny(app, "map/road",   true);
            tCity   = decAny(app, "map/city",   true);
            tCrater = decAny(app, "map/crater", true);
            gGlow   = decAny(app, "map/glow",   false);
            pA      = key(decAny(app, "map/props_a", false));
            pB      = key(decAny(app, "map/props_b", false));
            ready = true;
        } }, "map-load");
        loader.setDaemon(true); loader.start();
    }

    private static Bitmap decAny(Context c, String base, boolean opaque) {
        String[] ext = { ".webp", ".png", ".jpg" };
        for (String e : ext) { Bitmap b = dec(c, base + e, opaque); if (b != null) return b; }
        return null;
    }

    private static Bitmap dec(Context c, String path, boolean opaque) {
        try {
            InputStream in = c.getAssets().open(path);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = opaque ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            in.close(); return b;
        } catch (Exception e) { return null; }
    }

    // chroma-key magenta -> alpha, luminance-independent (eats drop shadows too)
    private static Bitmap key(Bitmap bmp) {
        if (bmp == null) return null;
        int w = bmp.getWidth(), h = bmp.getHeight(), n = w * h;
        int[] px = new int[n]; bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < n; i++) {
            int c = px[i], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            int mg = Math.min(r, b) - g;
            if (mg <= 24) continue;
            float k = Math.min(1f, mg / 140f), a = 1f - k;
            if (a <= 0.03f) { px[i] = 0; continue; }
            int sr = (int) ((r - k * 255) / a), sb = (int) ((b - k * 255) / a), sg = (int) (g / a);
            sr = sr < 0 ? 0 : sr > 255 ? 255 : sr; sg = sg < 0 ? 0 : sg > 255 ? 255 : sg; sb = sb < 0 ? 0 : sb > 255 ? 255 : sb;
            px[i] = ((int) (a * 255) << 24) | (sr << 16) | (sg << 8) | sb;
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        return bmp;
    }

    public static void hexToWorld(int q, int r, float[] out) {
        out[0] = HEX * (float) Math.sqrt(3) * (q + r / 2f);
        out[1] = HEX * 1.5f * r * SQUASH;
    }
    public static void worldToHex(float x, float y, float[] out) {
        float hy = y / SQUASH;
        float qf = ((float) Math.sqrt(3) / 3f * x - 1f / 3f * hy) / HEX;
        float rf = (2f / 3f * hy) / HEX;
        float sf = -qf - rf;
        int rq = Math.round(qf), rr = Math.round(rf), rs = Math.round(sf);
        float dq = Math.abs(rq - qf), dr = Math.abs(rr - rf), ds = Math.abs(rs - sf);
        if (dq > dr && dq > ds) rq = -rr - rs; else if (dr > ds) rr = -rq - rs;
        out[0] = rq; out[1] = rr;
    }
    public static void worldToHex(float x, float y, int[] out) {
        float[] f = new float[2]; worldToHex(x, y, f); out[0] = (int) f[0]; out[1] = (int) f[1];
    }

    private void buildWalkability() {
        for (int r = MIN_R; r <= MAX_R; r++) for (int q = MIN_Q; q <= MAX_Q; q++) {
            boolean w;
            if (q <= 10) w = r >= -4 && r <= 12;
            else if (q <= 36) { float t = (q - 11) / 25f; w = Math.abs(r - (2 - 7f * t)) <= (2.6f - 0.8f * t); }
            else if (q <= 60) w = (r >= -6 && r <= 12) && ((r % 5 == 0) || (q % 6 == 0) || insidePlaza(q, r)) && !insideRubble(q, r);
            else if (q <= 78) { float dx = (q - 66) / 10f, dy = (r - 4) / 8f; w = dx * dx + dy * dy <= 1f || (q >= 56 && r >= 2 && r <= 6); }
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
        worldToHex(wx, wy, HW); return walk(Math.round(HW[0]), Math.round(HW[1]));
    }

    private static int h2(int x, int y, int s) {
        int h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ s * 0x9E3779B1;
        h ^= h >>> 15; h *= 0x85EBCA6B; h ^= h >>> 13; return h & 0x7FFFFFFF;
    }

    // ================= TILE COMPOSER =================
    private void computeTile(int i, int tx, int ty) {
        float wx = tx * TS + TS / 2, wy = ty * TS + TS / 2;
        float hy = wy * 1.6666667f;
        float qf = wx * 0.0060141f - hy * 0.0057870f, rf = hy * 0.0115741f;
        int h = h2(tx, ty, 7), hB = h2(tx, ty, 91);
        int ts = 0, ti = 15, rot = 0, ds = 5, di = -1, pr = -1, gl = -1, gr = 0;

        int qi = Math.round(qf), ri = Math.round(rf);
        boolean inMap = qf >= MIN_Q && qf <= MAX_Q && rf >= MIN_R && rf <= MAX_R;
        float edge = rf + 4.5f;

        if (!inMap) { ts = 0; ti = 15; }
        else if (qf < 10.5f) {                                  // ASHEN FIELDS
            ts = 0;
            if (edge < -3.2f) ti = 15;
            else if (edge < 0f) ti = 10 + (h & 1);
            else {
                int v = h & 7;
                ti = v < 4 ? v : v < 6 ? v : (hB >>> 16) < 9000 ? 8 + ((hB >>> 8) & 1) : v - 4;
                if ((hB & 255) < 14) ti = 13;                    // pale dust drifts
                if (rf > -3f && rf < 2f && qf > -5f && qf < 12f) {
                    if ((h >>> 4) % 100 < 34) gl = (h >>> 12) % 2 == 0 ? 0 : 10;   // ember veins
                    if ((h >>> 6) % 100 < 8) ti = 12;
                }
                if ((h >>> 8) % 100 < 6) { ds = 5; di = (h >>> 14) & 1; di += 4; } // bones
                if ((h >>> 10) % 100 < 5) di = di < 0 ? 12 : di;
            }
            if ((h >>> 12) % 100 < 7) { ds = 5; di = 6 + ((h >>> 18) & 1); }       // dirt patches
        } else if (qf < 36.5f) {                                // THE DESCENT
            ts = 1;
            float t = (qf - 11f) / 25f; t = t < 0 ? 0 : t > 1 ? 1 : t;
            float c = 2f - 7f * t, hw2 = 2.6f - 0.8f * t, dr = rf - c;
            float adr = dr < 0 ? -dr : dr, e = hw2 - adr;
            if (e > 0) ti = (h & 3);                             // worn path
            else if (e > -2.6f) ti = 4 + (h & 1);                // canyon strata walls
            else if (dr > 0) ti = 4 + ((h >>> 2) & 1);
            else { ts = 0; ti = 12; }
            if ((h >>> 5) % 100 < 5) { ds = 5; di = 15; }        // footprints
            if ((h >>> 9) % 100 < 4) { ds = 5; di = (h >>> 15) & 1; }              // boulders
            float bq = qf - 21f, br = rf + 1f;
            if (bq * bq + br * br < 9f) { ti = 15; if ((h >>> 3) % 100 < 40) { di = 10; ds = 5; } }
        } else if (qf < 60f) {                                  // THE CITY
            ts = 2;
            boolean plaza = qf > 43.5f && qf < 50.5f && rf > 1.5f && rf < 8.5f;
            boolean stR = (ri % 5 == 0) && Math.abs(rf - ri) < 0.55f && ri >= -6 && ri <= 12;
            boolean stQ = (qi % 6 == 0) && Math.abs(qf - qi) < 55f / 100f;
            boolean rubble = insideRubble(qi, ri);
            if (rubble) {
                ti = 8 + (h & 3);
                if ((h >>> 4) % 100 < 30) { ds = 5; di = 2 + ((h >>> 12) & 1); }   // rubble/rebar
                if ((h >>> 6) % 100 < 12) pr = (h >>> 14) & 1;                     // barricade/cart
            } else if (plaza) {
                float dq = qf - 47f, dr2 = rf - 5f; float d2 = dq * dq + dr2 * dr2;
                ti = d2 < 2.2f ? ((h & 1) == 0 ? 0 : 3) : 10 + (h & 1);
                if (d2 < 0.6f) pr = 2;                                             // the statue
                else if (Math.abs(dq) > 2.6f && Math.abs(dr2) > 2.6f && (h & 3) == 0) pr = 1;
                else if ((h >>> 5) % 100 < 7) pr = 3;                              // benches
            } else if (stR || stQ) {
                if (stQ && !stR) { ti = 8 + (h & 1); rot = 1; } else ti = 6 + (h & 1);
                if (stR && stQ) ti = 13;
                if (stQ && (h >>> 7) % 6 == 0) { pr = 4; gl = 7; }                  // lamp posts
                if ((h >>> 8) % 100 < 5) pr = 12;                                  // barrels
            } else if ((qi % 6 == 3) && (ri % 5 == 2)) { ti = 14 + (h & 1); if ((h >>> 9) % 100 < 8) pr = 6; }
            else {
                int v = (h >>> 3) & 3; ti = v;
                if ((h >>> 6) & 1) ti = 4 + ((h >>> 12) & 1);
                else if ((h >>> 8) % 8 == 0) ti = 6 + ((h >>> 14) & 1);
                if ((h >>> 10) % 100 < 3) ti = 12; else if ((h >>> 10) % 100 < 5) ti = 13;
                if ((h >>> 5) % 100 < 9) gl = 2 + (((h >>> 13) & 1) * 9);          // lit windows
                if ((h >>> 11) % 100 < 4) pr = 7;                                  // fences
            }
            float bq = qf - 44f, br = rf - 6f;
            if (bq * bq + br * br < 9f) { ti = 14; if ((h >>> 3) % 100 < 40) { ds = 5; di = 10; } }
        } else if (qf < 78.5f) {                                // COURTYARD
            ts = 3;
            float dx = (qf - 66f) / 10f, dy = (rf - 4f) / 8f;
            float rho = (float) Math.sqrt(dx * dx + dy * dy);
            if (rho < 1f) {
                float sq = qf - 67f, sr = rf - 4f; float d2 = sq * sq + sr * sr;
                if (d2 < 4f) ti = 4; else if (d2 < 9f) ti = 5;
                else ti = rho < 0.35f ? ((h & 1) == 0 ? 0 : 3) : 1 + (h & 1);
                if ((h >>> 4) % 100 < 6) { pr = 12; gl = 8; }                       // braziers
                if (d2 < 1f) gl = 5;
            } else if (rho < 1.35f) {
                ti = 15; rot = ((int) (Math.atan2(dy, dx) * 2f / (float) Math.PI + 4)) & 3;
                if ((h >>> 6) % 100 < 10) pr = 11;                                 // banners
            } else if (rf >= 1.5f && rf <= 6.5f && qf >= 56f && qf <= 78f) ti = 14;
            else { ti = 6 + (h & 1); if ((h >>> 5) % 100 < 20) ti = 8 + ((h >>> 9) & 1); }
            if ((h >>> 12) % 100 < 7) { ds = 5; di = 6 + ((h >>> 18) & 1); }
        } else {                                                // CRATER FIELD
            ts = 3;
            float cxw = 14970.8f, cyw = 345.6f;
            float dxw = wx - cxw, dyw = wy - cyw;
            float rho = (float) Math.sqrt(dxw * dxw + dyw * dyw) / 330f;
            if (rho < 1f) {
                ti = rho < 0.4f ? 12 : 10 + (h & 1);
                if (rho < 0.3f) gl = 4;
                else if ((h >>> 4) % 100 < 30) gl = (h >>> 12) & 1;
                else if (rho > 0.6f && rho < 0.85f && (h >>> 6) % 100 < 18) gl = 14;
            } else if (rho < 1.35f) { ti = 8 + (h & 1); if ((h >>> 5) % 100 < 14) { ds = 6; di = 9; } }
            else { ts = 0; ti = 4 + (h & 3);
                if ((h >>> 7) % 100 < 12) { ds = 6; di = 8; }                       // ejecta
                if ((h >>> 9) % 100 < 6) gl = 7;
            }
        }
        if (quality && di < 0 && (hB >>> 20) % 100 < 3) { ds = 5; di = 8; }         // hairline cracks
        tS[i] = ts; tI[i] = ti; tR[i] = rot; dS[i] = ds; dI[i] = di; pI[i] = pr; gI[i] = gl; gR[i] = gr;
    }

    // ================= DRAW =================
    public void draw(Canvas c, float camX, float camY, float zoom, int vw, int vh) {
        float halfW = vw / (2f * zoom), halfH = vh / (2f * zoom);
        int x0 = (int) Math.floor((camX - halfW) / TS) - 1, x1 = (int) Math.floor((camX + halfW) / TS) + 1;
        int y0 = (int) Math.floor((camY - halfH) / TS) - 1, y1 = (int) Math.floor((camY + halfH) / TS) + 1;
        int n = (x1 - x0 + 1) * (y1 - y0 + 1);
        boolean full = ready && n <= MAXT;
        if (full) for (int ty = y0, i = 0; ty <= y1; ty++)
            for (int tx = x0; tx <= x1; tx++, i++) computeTile(i, tx, ty);

        Bitmap[] SH = { tAsh, tRoad, tCity, tCrater };
        for (int ty = y0, i = 0; ty <= y1; ty++) for (int tx = x0; tx <= x1; tx++, i++) {
            float sx0 = (tx * TS - camX) * zoom + vw / 2f, sy0 = (ty * TS - camY) * zoom + vh / 2f;
            float sz = TS * zoom + 1.5f;
            Bitmap b = full ? SH[tS[i]] : null;
            if (b == null) { tp.setColor(fallback(tx, ty)); dstR.set((int) sx0, (int) sy0, (int) (sx0 + sz), (int) (sy0 + sz)); c.drawRect(dstR, tp); continue; }
            cell(tI[i]);
            if (tR[i] == 0) { dstR.set((int) (sx0 - 0.75f), (int) (sy0 - 0.75f), (int) (sx0 + sz), (int) (sy0 + sz)); c.drawBitmap(b, srcR, dstR, tp); }
            else { c.save(); c.translate(sx0 + sz / 2, sy0 + sz / 2); c.rotate(tR[i] * 90f);
                   dstR.set(-(int) (sz / 2), -(int) (sz / 2), (int) (sz / 2), (int) (sz / 2)); c.drawBitmap(b, srcR, dstR, tp); c.restore(); }
        }
        if (!full || !quality) { if (craterVisible) drawCrater(c, camX, camY, zoom, vw, vh); return; }

        for (int ty = y0, i = 0; ty <= y1; ty++) for (int tx = x0; tx <= x1; tx++, i++) {   // decals
            if (dI[i] < 0 || pA == null) continue;
            float s = D_S[dI[i]] * TS * zoom;
            float sx0 = (tx * TS + TS / 2 - camX) * zoom + vw / 2f, sy0 = (ty * TS + TS / 2 - camY) * zoom + vh / 2f;
            pp.setAlpha(D_A[dI[i]]); cell(dI[i]);
            dstR.set((int) (sx0 - s / 2), (int) (sy0 - s / 2), (int) (sx0 + s / 2), (int) (sy0 + s / 2));
            c.drawBitmap(pA, srcR, dstR, pp); pp.setAlpha(255);
        }
        for (int ty = y0, i = 0; ty <= y1; ty++) for (int tx = x0; tx <= x1; tx++, i++) {   // props
            if (pI[i] < 0 || pB == null) continue;
            float s = P_S[pI[i]] * TS * zoom;
            float sx0 = (tx * TS + TS / 2 - camX) * zoom + vw / 2f, sy1 = (ty * TS + TS - camY) * zoom + vh / 2f;
            cell(pI[i]);
            dstR.set((int) (sx0 - s / 2), (int) (sy1 - s), (int) (sx0 + s / 2), (int) sy1);
            c.drawBitmap(pB, srcR, dstR, pp);
        }
        if (gGlow != null) for (int ty = y0, i = 0; ty <= y1; ty++) for (int tx = x0; tx <= x1; tx++, i++) {  // glow
            if (gI[i] < 0) continue;
            float s = G_S[gI[i]] * TS * zoom;
            float sx0 = (tx * TS + TS / 2 - camX) * zoom + vw / 2f, sy0 = (ty * TS + TS / 2 - camY) * zoom + vh / 2f;
            cell(gI[i]);
            dstR.set((int) (sx0 - s / 2), (int) (sy0 - s / 2), (int) (sx0 + s / 2), (int) (sy0 + s / 2));
            c.drawBitmap(gGlow, srcR, dstR, gp);
        }
        if (craterVisible) drawCrater(c, camX, camY, zoom, vw, vh);
    }

    private void cell(int idx) { srcR.set((idx & 3) * CELL, (idx >> 2) * CELL, ((idx & 3) + 1) * CELL, ((idx >> 2) + 1) * CELL); }

    private int fallback(int tx, int ty) {
        float wy = ty * TS + TS / 2, wx = tx * TS + TS / 2;
        float hy = wy * 1.6666667f, qf = wx * 0.0060141f - hy * 0.0057870f, rf = hy * 0.0115741f;
        if (qf < 10) return rf < -4.5f ? 0xFF0c0b0e : 0xFF2d282a;
        if (qf < 36) return 0xFF3c3430;
        if (qf < 60) return 0xFF343032;
        if (qf < 78) return 0xFF787062;
        return 0xFF1c1a1e;
    }

    public void setCraterVisible(boolean v) { craterVisible = v; }

    private void drawCrater(Canvas c, float camX, float camY, float zoom, int vw, int vh) {
        hexToWorld(88, 4, HW);
        float gx = (HW[0] - camX) * zoom + vw / 2f, gy = (HW[1] - camY) * zoom + vh / 2f;
        dstR.set((int) (gx - 500 * zoom), (int) (gy - 500 * zoom), (int) (gx + 500 * zoom), (int) (gy + 500 * zoom));
        c.drawBitmap(craterGlow, null, dstR, gp);
    }

    private void bakeCraterGlow() {
        int r = 256;
        craterGlow = Bitmap.createBitmap(r * 2, r * 2, Bitmap.Config.ARGB_8888);
        int[] px = new int[r * r * 4];
        for (int y = 0; y < r * 2; y++) for (int x = 0; x < r * 2; x++) {
            float dx = (x - r) / (float) r, dy = (y - r) / (float) r;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float core = d > 1f ? 0f : d < 0.1f ? 1f : 1f - (d - 0.1f) / 0.9f;
            core *= core;
            float fl = 0.85f + 0.15f * ((h2(x, y, 99) & 0xFFFF) / 65535f);
            int a = (int) (core * 255 * fl);
            px[y * r * 2 + x] = (a << 24) | ((int) ((40 + 140 * core) * fl) << 16)
                    | ((int) ((120 + 135 * core) * fl) << 8) | (int) ((60 + 80 * core) * fl);
        }
        IntBuffer ib = ByteBuffer.allocateDirect(px.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(px).position(0);
        craterGlow.copyPixelsFromBuffer(ib);
    }

    public void dispose() {
        ready = false;
        Bitmap[] all = { tAsh, tRoad, tCity, tCrater, gGlow, pA, pB, craterGlow };
        for (Bitmap b : all) if (b != null && !b.isRecycled()) b.recycle();
        tAsh = tRoad = tCity = tCrater = gGlow = pA = pB = craterGlow = null;
    }
}
