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
    public static final int CHUNK_PX = 1024, SRC = 512;
    public static final int MIN_Q = -32, MAX_Q = 96, MIN_R = -24, MAX_R = 24;
    public static final int W_Q = MAX_Q - MIN_Q + 1, W_R = MAX_R - MIN_R + 1;

    private static final float TS = 128f;
    private static final int CELL = 256, MAXT = 8192;

    private final boolean[] walkable = new boolean[W_Q * W_R];
    private volatile Bitmap tAsh, tRoad, tCity, tCrater, gGlow, pA, pB;
    private volatile boolean ready;

    private final Paint tp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint gp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect srcR = new Rect(), dstR = new Rect();
    private final float[] HW = new float[2], DW = new float[2];

    private final int[] tS = new int[MAXT], tI = new int[MAXT], tR = new int[MAXT];
    private final int[] dI = new int[MAXT], pI = new int[MAXT], gI = new int[MAXT];
    private final int[] sortKey = new int[MAXT], sortIdx = new int[MAXT];
    private int sortCount = 0;

    private boolean quality;
    private Bitmap craterGlow; private boolean craterVisible;

    private static final float[] D_S_A = {1.2f,1.3f,1.4f,1.2f,1.1f,0.8f,2.6f,2.6f,1.6f,1.5f,1.4f,1.6f,1.2f,1.0f,1.2f,1.4f};
    private static final int[]   D_A_A = {255,255,255,255,255,255,90,90,150,170,170,170,255,255,255,130};
    private static final float[] D_S_B = {1.5f,1.2f,0f,0f,0f,0f,0f,0f,0f,1.3f,0f,0f,0f,1.4f,1.8f,1.5f};
    private static final float[] P_S_A = {1.2f,1.4f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,1.0f,1.2f,0f,0f};
    private static final float[] P_S_B = {0f,0f,2.8f,1.1f,2.3f,1.5f,1.8f,1.4f,1.2f,0f,1.0f,2.4f,1.1f,0f,0f,0f};
    private static final float[] G_S   = {1.1f,1.3f,1.0f,1.0f,6.0f,2.2f,1.3f,2.6f,1.2f,2.0f,1.4f,1.0f,1.6f,1.2f,2.2f,1.0f};

    public SceneMap(Context ctx, boolean quality) {
        this.quality = quality;
        buildWalkability();
        gp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        bakeCraterGlow();
        final Context app = ctx.getApplicationContext();
        Thread loader = new Thread(new Runnable() { public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            tAsh = decAny(app, "map/ash", true); tRoad = decAny(app, "map/road", true);
            tCity = decAny(app, "map/city", true); tCrater = decAny(app, "map/crater", true);
            gGlow = decAny(app, "map/glow", true); // pure black bg -> RGB_565 is fine for SCREEN
            pA = key(decAny(app, "map/props_a", false));
            pB = key(decAny(app, "map/props_b", false));
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
            o.inMutable = !opaque;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (b != null && !opaque && !b.isMutable()) {
                Bitmap mb = b.copy(Bitmap.Config.ARGB_8888, true); b.recycle(); b = mb;
            }
            return b;
        } catch (Exception e) { return null; }
    }

    // Subtractive keying: NO division by alpha to prevent green fringes
    private static Bitmap key(Bitmap bmp) {
        if (bmp == null) return null;
        int w = bmp.getWidth(), h = bmp.getHeight(), n = w * h;
        int[] px = new int[n]; bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < n; i++) {
            int c = px[i], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            int m = Math.min(r, b) - g;
            if (m > 24) {
                if (m >= 110) { px[i] = 0; continue; }
                int a = 255 - (m * 255 / 110);
                int sr = r - m; if (sr < 0) sr = 0;
                int sb = b - m; if (sb < 0) sb = 0;
                px[i] = (a << 24) | (sr << 16) | (g << 8) | sb;
            }
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
        float hy = y / SQUASH;
        float qf = ((float) Math.sqrt(3) / 3f * x - 1f / 3f * hy) / HEX;
        float rf = (2f / 3f * hy) / HEX;
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
    private static float vn(float x, float y, int s) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float fx = x - xi, fy = y - yi;
        float sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
        float a = (h2(xi, yi, s) & 0xFFFF) / 65536f, b = (h2(xi + 1, yi, s) & 0xFFFF) / 65536f;
        float c = (h2(xi, yi + 1, s) & 0xFFFF) / 65536f, d = (h2(xi + 1, yi + 1, s) & 0xFFFF) / 65536f;
        float ab = a + (b - a) * sx, cd = c + (d - c) * sx;
        return ab + (cd - ab) * sy;
    }

    private void computeTile(int i, int tx, int ty) {
        float wx = tx * TS + TS / 2, wy = ty * TS + TS / 2;
        float hy = wy * 1.6666667f;
        float qf = wx * 0.0060141f - hy * 0.0057870f, rf = hy * 0.0115741f;
        int h = h2(tx, ty, 7);
        int ts = 0, ti = 15, rot = 0, di = -1, pi = -1, gi = -1;
        int qi = Math.round(qf), ri = Math.round(rf);
        boolean inMap = qf >= MIN_Q && qf <= MAX_Q && rf >= MIN_R && rf <= MAX_R;

        if (!inMap) { ts = 0; ti = 15; }
        else if (qf < 10.5f) {
            ts = 0;
            if (rf < -7.7f) ti = 15;
            else if (rf < -4.5f) {
                ti = 10 + (((int)((rf + 7.7f) * 1.4f + vn(tx*0.5f, ty*0.5f, 11)*0.8f)) & 1);
                if (rf > -5.5f && (h % 100) < 6) pi = (0 << 4) | ((h >>> 8) & 1);
            } else {
                float n1 = vn(tx / 7f, ty / 7f, 13), n2 = vn(tx / 4f, ty / 4f, 17);
                if (n2 > 0.55f) ti = 4 + (int)(n1 * 4f) % 4; else ti = (int)(n1 * 4f) % 4;
                if (vn(tx / 9f + 40f, ty / 9f, 19) > 0.78f) ti = 13;
                if (rf > -3f && rf < 2f && qf > -5f && qf < 12f) {
                    if ((h % 100) < 9) gi = ((h >>> 8) % 4 == 0) ? 10 : 0;
                    if ((h >>> 4) % 100 < 5) ti = 12;
                }
                if ((h % 100) < 2) di = (0 << 4) | 4;
                else if ((h >>> 4) % 100 < 1) di = (0 << 4) | 5;
                if ((h >>> 8) % 100 < 7) di = (0 << 4) | (6 + ((h >>> 12) & 1));
            }
        } else if (qf < 36.5f) {
            ts = 1;
            float t = (qf - 11f) / 25f; if (t < 0) t = 0; if (t > 1) t = 1;
            float c = 2f - 7f * t, hw = 2.6f - 0.8f * t, dr = rf - c;
            float adr = dr < 0 ? -dr : dr;
            if (adr < hw) {
                float n = vn(tx / 5f, ty / 5f, 23); ti = (int)(n * 3f) % 3;
                if ((h % 100) < 6) ti = 3;
            } else if (adr < hw + 2.6f) {
                ti = 4 + (((int)(adr * 1.3f + vn(tx*0.3f, ty*0.3f, 29))) & 1);
                if (adr < hw + 1.2f && (h % 100) < 5) pi = (0 << 4) | ((h >>> 8) & 1);
            } else {
                ts = 0; float n1 = vn(tx / 7f, ty / 7f, 31);
                ti = (dr < 0) ? ((int)(n1 * 4f) % 4) : 12;
            }
            float bq = qf - 21f, br = rf + 1f;
            if (bq * bq + br * br < 9f) {
                if (bq * bq + br * br < 2.5f) ti = 15;
                if ((h % 100) < 35) di = (0 << 4) | (10 + ((h >>> 8) & 1));
                if (bq * bq + br * br < 0.5f) di = (0 << 4) | 14;
            }
        } else if (qf < 60f) {
            boolean plaza = qf > 43.5f && qf < 50.5f && rf > 1.5f && rf < 8.5f;
            boolean stR = (ri % 5 == 0) && Math.abs(rf - ri) < 0.55f && ri >= -6 && ri <= 12;
            boolean stQ = (qi % 6 == 0) && Math.abs(qf - qi) < 0.55f;
            boolean rubble = insideRubble(qi, ri);
            if (rubble) {
                ts = 2; float n = vn(tx / 3f, ty / 3f, 37);
                ti = 8 + (int)(n * 2f) % 2; if (n > 0.7f) ti = 10 + ((h >>> 8) & 1);
                if ((h % 100) < 20) di = (0 << 4) | 2;
                if ((h >>> 4) % 100 < 10) di = (0 << 4) | 3;
                if ((h >>> 8) % 100 < 6) pi = (1 << 4) | 5;
                if ((h >>> 12) % 100 < 4) pi = (1 << 4) | 6;
                if ((h >>> 16) % 100 < 8) pi = (1 << 4) | 7;
            } else if (plaza) {
                ts = 1; float dq = qf - 47f, dr2 = rf - 5f; float d2 = dq * dq + dr2 * dr2;
                if (d2 < 2f) { ts = 3; ti = (h & 1) == 0 ? 0 : 3; }
                else { float n = vn(tx / 4f, ty / 4f, 41); ti = 10 + (int)(n * 2f) % 2; if (n > 0.72f) ti = 12; }
                if (d2 < 0.5f) pi = (1 << 4) | 2;
                else if (Math.abs(dq) > 2.6f && Math.abs(dr2) > 2.6f && (h & 3) == 0) di = (1 << 4) | 1;
                else if ((h >>> 4) % 100 < 6) pi = (1 << 4) | 3;
                float bq = qf - 44f, br = rf - 6f;
                if (bq * bq + br * br < 9f) {
                    if (bq * bq + br * br < 1.5f) ti = 14;
                    if ((h % 100) < 30) di = (0 << 4) | (10 + ((h >>> 8) & 1));
                }
            } else if (stR || stQ) {
                ts = 1;
                if (stQ && !stR) { ti = 8 + (h & 1); rot = 1; }
                else if (stR && stQ) ti = 6 + (h & 1);
                else ti = 6 + (h & 1);
                if (stQ && !stR && (h >>> 4) % 6 == 0) { pi = (1 << 4) | 4; gi = 7; }
                if ((h >>> 8) % 100 < 4) pi = (1 << 4) | 12;
            } else {
                ts = 2;
                float dR = Math.abs(rf - Math.round(rf / 5f) * 5f);
                float dQ = Math.abs(qf - Math.round(qf / 6f) * 6f);
                if (dR < 1.1f || dQ < 1.1f) {
                    ti = 14 + (h & 1); if ((h % 100) < 5) pi = (0 << 4) | 13;
                } else {
                    float n = vn(tx / 3.2f, ty / 3.2f, 43);
                    if (n < 0.3f) ti = 0; else if (n < 0.55f) ti = 1; else if (n < 0.8f) ti = 2; else ti = 3;
                    if (vn(tx / 2f, ty / 2f, 47) > 0.75f) ti = 4 + ((h >>> 8) & 1);
                    if (vn(tx / 4f, ty / 4f, 53) > 0.8f) ti = 6 + ((h >>> 12) & 1);
                    if ((h % 100) < 2) ti = 12; if ((h >>> 4) % 100 < 1) ti = 13;
                    if ((h >>> 8) % 100 < 7) { int wv = (h >>> 12) % 3; gi = wv == 0 ? 2 : (wv == 1 ? 3 : 11); }
                }
            }
        } else if (qf < 78.5f) {
            ts = 3; float dx = (qf - 66f) / 10f, dy = (rf - 4f) / 8f;
            float rho = (float) Math.sqrt(dx * dx + dy * dy);
            if (rho < 1f) {
                float sq = qf - 67f, sr = rf - 4f; float d2 = sq * sq + sr * sr;
                if (d2 < 1f) ti = 4; else if (d2 < 4f) ti = 5;
                else if (rho < 0.35f) ti = (h & 1) == 0 ? 0 : 3;
                else { float n = vn(tx / 4f, ty / 4f, 59); ti = 1 + (int)(n * 2f) % 2; }
                if ((h >>> 4) % 100 < 6) gi = 8;
                if (d2 < 1f) gi = 5;
            } else if (rho < 1.35f) {
                ti = 15; rot = ((int) (Math.atan2(dy, dx) * 2f / (float) Math.PI + 4)) & 3;
                if ((h % 100) < 10) pi = (1 << 4) | 11;
            } else if (rf >= 1.5f && rf <= 6.5f && qf >= 56f && qf <= 78f) ti = 13;
            else { float n = vn(tx / 5f, ty / 5f, 61); ti = 6 + (int)(n * 2f) % 2; if (n > 0.8f) ti = 8 + ((h >>> 8) & 1); }
        } else {
            ts = 3; float cxw = 14970.8f, cyw = 345.6f;
            float dxw = wx - cxw, dyw = wy - cyw; float rho = (float) Math.sqrt(dxw * dxw + dyw * dyw) / 330f;
            if (rho < 1f) {
                if (rho < 0.4f) ti = 12; else ti = 10 + (h & 1);
                if (rho < 0.3f) gi = 4; else if ((h % 100) < 8) gi = (h >>> 8) & 1;
            } else if (rho < 1.35f) {
                ti = 8 + (h & 1); if ((h % 100) < 8) di = (1 << 4) | 9;
            } else {
                ts = 0; float n = vn(tx / 6f, ty / 6f, 67); ti = 4 + (int)(n * 4f) % 4;
                if ((h % 100) < 6) pi = (1 << 4) | 8; if ((h >>> 4) % 100 < 6) gi = 7;
            }
        }
        if (quality && di < 0 && (h >>> 20) % 100 < 2) di = (0 << 4) | 8;
        tS[i] = ts; tI[i] = ti; tR[i] = rot; dI[i] = di; pI[i] = pi; gI[i] = gi;
    }

    public void draw(Canvas c, float camX, float camY, float zoom, int vw, int vh) {
        float halfW = vw / (2f * zoom), halfH = vh / (2f * zoom);
        int x0 = (int) Math.floor((camX - halfW) / TS) - 1, x1 = (int) Math.floor((camX + halfW) / TS) + 1;
        int y0 = (int) Math.floor((camY - halfH) / TS) - 1, y1 = (int) Math.floor((camY + halfH) / TS) + 1;
        int n = (x1 - x0 + 1) * (y1 - y0 + 1);
        boolean full = ready && n <= MAXT;
        sortCount = 0;

        for (int ty = y0, i = 0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++, i++) {
                if (full) computeTile(i, tx, ty);
                else { tS[i] = 0; tI[i] = 15; tR[i] = 0; dI[i] = -1; pI[i] = -1; gI[i] = -1; }
                float sx0 = (tx * TS - camX) * zoom + vw / 2f, sy0 = (ty * TS - camY) * zoom + vh / 2f;
                float sz = TS * zoom + 1.5f;
                Bitmap b = full ? getSheet(tS[i]) : null;
                if (b == null) { tp.setColor(fallback(tx, ty)); dstR.set((int) sx0, (int) sy0, (int) (sx0 + sz), (int) (sy0 + sz)); c.drawRect(dstR, tp); continue; }
                cell(tI[i]);
                if (tR[i] == 0) { dstR.set((int) (sx0 - 0.75f), (int) (sy0 - 0.75f), (int) (sx0 + sz), (int) (sy0 + sz)); c.drawBitmap(b, srcR, dstR, tp); }
                else { c.save(); c.translate(sx0 + sz / 2, sy0 + sz / 2); c.rotate(tR[i] * 90f); dstR.set(-(int) (sz / 2), -(int) (sz / 2), (int) (sz / 2), (int) (sz / 2)); c.drawBitmap(b, srcR, dstR, tp); c.restore(); }
            }
        }
        if (!full) { if (craterVisible) drawCrater(c, camX, camY, zoom, vw, vh); return; }

        for (int ty = y0, i = 0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++, i++) {
                if (dI[i] < 0) continue;
                int sheet = dI[i] >> 4, idx = dI[i] & 15;
                Bitmap b = (sheet == 0) ? pA : pB; if (b == null) continue;
                float s = (sheet == 0 ? D_S_A[idx] : D_S_B[idx]) * TS * zoom;
                float sx0 = (tx * TS + TS / 2 - camX) * zoom + vw / 2f, sy0 = (ty * TS + TS / 2 - camY) * zoom + vh / 2f;
                pp.setAlpha(sheet == 0 ? D_A_A[idx] : 255); cell(idx);
                dstR.set((int) (sx0 - s / 2), (int) (sy0 - s / 2), (int) (sx0 + s / 2), (int) (sy0 + s / 2));
                c.drawBitmap(b, srcR, dstR, pp);
            }
        }

        for (int ty = y0, i = 0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++, i++) {
                if (pI[i] < 0 || sortCount >= MAXT) continue;
                sortKey[sortCount] = ty; sortIdx[sortCount] = i; sortCount++;
            }
        }
        for (int j = 1; j < sortCount; j++) {
            int key = sortKey[j], idx = sortIdx[j], k = j - 1;
            while (k >= 0 && sortKey[k] > key) { sortKey[k + 1] = sortKey[k]; sortIdx[k + 1] = sortIdx[k]; k--; }
            sortKey[k + 1] = key; sortIdx[k + 1] = idx;
        }
        for (int j = 0; j < sortCount; j++) {
            int i = sortIdx[j], tx = x0 + (i % (x1 - x0 + 1)), ty = y0 + (i / (x1 - x0 + 1));
            int sheet = pI[i] >> 4, idx = pI[i] & 15;
            Bitmap b = (sheet == 0) ? pA : pB; if (b == null) continue;
            float s = (sheet == 0 ? P_S_A[idx] : P_S_B[idx]) * TS * zoom;
            float sx0 = (tx * TS + TS / 2 - camX) * zoom + vw / 2f, sy1 = (ty * TS + TS - camY) * zoom + vh / 2f;
            pp.setAlpha(255); cell(idx);
            dstR.set((int) (sx0 - s / 2), (int) (sy1 - s), (int) (sx0 + s / 2), (int) sy1);
            c.drawBitmap(b, srcR, dstR, pp);
        }

        if (gGlow != null && quality) {
            for (int ty = y0, i = 0; ty <= y1; ty++) {
                for (int tx = x0; tx <= x1; tx++, i++) {
                    if (gI[i] < 0) continue;
                    float s = G_S[gI[i]] * TS * zoom;
                    float sx0 = (tx * TS + TS / 2 - camX) * zoom + vw / 2f, sy0 = (ty * TS + TS / 2 - camY) * zoom + vh / 2f;
                    if (pI[i] >= 0 && (pI[i] & 15) == 4) sy0 += TS * zoom * 0.4f;
                    cell(gI[i]);
                    dstR.set((int) (sx0 - s / 2), (int) (sy0 - s / 2), (int) (sx0 + s / 2), (int) (sy0 + s / 2));
                    c.drawBitmap(gGlow, srcR, dstR, gp);
                }
            }
        }
        if (craterVisible) drawCrater(c, camX, camY, zoom, vw, vh);
    }

    private Bitmap getSheet(int ts) { return ts == 0 ? tAsh : (ts == 1 ? tRoad : (ts == 2 ? tCity : tCrater)); }
    private void cell(int idx) { srcR.set((idx & 3) * CELL + 1, (idx >> 2) * CELL + 1, ((idx & 3) + 1) * CELL - 1, ((idx >> 2) + 1) * CELL - 1); }

    private int fallback(int tx, int ty) {
        float wy = ty * TS + TS / 2, wx = tx * TS + TS / 2;
        float hy = wy * 1.6666667f, qf = wx * 0.0060141f - hy * 0.0057870f, rf = hy * 0.0115741f;
        if (qf < 10) return rf < -4.5f ? 0xFF0c0b0e : 0xFF2d282a;
        if (qf < 36) return 0xFF3c3430; if (qf < 60) return 0xFF343032;
        if (qf < 78) return 0xFF787062; return 0xFF1c1a1e;
    }

    public void setCraterVisible(boolean v) { craterVisible = v; }

    private void drawCrater(Canvas c, float camX, float camY, float zoom, int vw, int vh) {
        hexToWorld(88, 4, DW);
        float gx = (DW[0] - camX) * zoom + vw / 2f, gy = (DW[1] - camY) * zoom + vh / 2f;
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
            float core = d > 1f ? 0f : d < 0.1f ? 1f : 1f - (d - 0.1f) / 0.9f; core *= core;
            float fl = 0.85f + 0.15f * ((h2(x, y, 99) & 0xFFFF) / 65535f);
            int a = (int) (core * 255 * fl);
            px[y * r * 2 + x] = (a << 24) | ((int) ((40 + 140 * core) * fl) << 16)
                    | ((int) ((120 + 135 * core) * fl) << 8) | (int) ((60 + 80 * core) * fl);
        }
        IntBuffer ib = ByteBuffer.allocateDirect(px.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(px).position(0); craterGlow.copyPixelsFromBuffer(ib);
    }

    public void dispose() {
        ready = false;
        Bitmap[] all = { tAsh, tRoad, tCity, tCrater, gGlow, pA, pB, craterGlow };
        for (Bitmap b : all) if (b != null && !b.isRecycled()) b.recycle();
        tAsh = tRoad = tCity = tCrater = gGlow = pA = pB = craterGlow = null;
    }
}
