package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

public final class SceneMap {
    public static final float HEX = 96f;
    public static final float SQUASH = 0.6f;
    public static final float BAKE = 2f;
    public static final int CHUNK_PX = 1024;
    public static final int SRC = CHUNK_PX / (int) BAKE;

    public static final int MIN_Q = -32, MAX_Q = 96;
    public static final int MIN_R = -24, MAX_R = 24;
    public static final int W_Q = MAX_Q - MIN_Q + 1;
    public static final int W_R = MAX_R - MIN_R + 1;

    private final boolean[] walkable;
    private final Bitmap[] chunkBits;
    private final int[] chunkKeys;
    private final long[] chunkUsed;
    private long frameStamp;

    private final Rect srcR = new Rect();
    private final Rect dstR = new Rect();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glowPaint = new Paint();

    private boolean quality = true;
    private Bitmap craterGlow;
    private boolean craterVisible = false;

    private final int[] noiseSeed = new int[256];
    private final short[] bakeScratch = new short[SRC * SRC];
    private final ShortBuffer bakeBuf = ByteBuffer.allocateDirect(SRC * SRC * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer();

    // Bake queue (ring buffer, allocation-free)
    private static final int PEND = 128;
    private final int[] pendQ = new int[PEND];
    private int pendHead = 0, pendTail = 0;
    private Thread baker;

    // Bake-time scratch fields (allocation-free)
    private float cR, cG, cB;
    private float vF1, vF2, vH, vPx, vPy;

    public SceneMap(Context ctx, boolean quality) {
        this.quality = quality;
        this.walkable = new boolean[W_Q * W_R];
        buildWalkability();

        int cap = 24;
        chunkBits = new Bitmap[cap];
        chunkKeys = new int[cap];
        chunkUsed = new long[cap];
        Arrays.fill(chunkKeys, Integer.MIN_VALUE);

        long s = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 256; i++) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            noiseSeed[i] = (int) (s & 0x7FFFFFFF);
        }
        bakeCraterGlow();
        startBaker();
    }

    private static short rgb565(int r, int g, int b) {
        return (short) (((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
    }

    public static void hexToWorld(int q, int r, float[] out) {
        out[0] = HEX * (float) Math.sqrt(3) * (q + r / 2f);
        out[1] = HEX * 1.5f * r * SQUASH;
    }

    public static void worldToHex(float x, float y, float[] out) {
        float hy = y / SQUASH;
        float qf = ((float) Math.sqrt(3) / 3f * x - 1f / 3f * hy) / HEX;
        float rf2 = (2f / 3f * hy) / HEX;
        float sf = -qf - rf2;
        int rq = Math.round(qf), rr = Math.round(rf2), rs = Math.round(sf);
        float dq = Math.abs(rq - qf), dr = Math.abs(rr - rf2), ds = Math.abs(rs - sf);
        if (dq > dr && dq > ds) rq = -rr - rs;
        else if (dr > ds) rr = -rq - rs;
        out[0] = rq; out[1] = rr;
    }

    private void buildWalkability() {
        for (int r = MIN_R; r <= MAX_R; r++) {
            for (int q = MIN_Q; q <= MAX_Q; q++) {
                boolean w = false;
                if (q <= 10) {
                    w = r >= -4 && r <= 12;
                } else if (q >= 11 && q <= 36) {
                    float t = (q - 11) / 25f;
                    float cr = 2 - 7f * t;
                    w = Math.abs(r - cr) <= (2.6f - 0.8f * t);
                } else if (q <= 60) {
                    boolean street = (r >= -6 && r <= 12) &&
                            ((r % 5 == 0) || (q % 6 == 0) || insidePlaza(q, r));
                    w = street && !insideRubble(q, r);
                } else if (q <= 78) {
                    float dx = (q - 66) / 10f, dy = (r - 4) / 8f;
                    w = (dx * dx + dy * dy) <= 1f || (q >= 56 && q <= 78 && r >= 2 && r <= 6);
                } else {
                    w = r >= -2 && r <= 10;
                }
                walkable[(r - MIN_R) * W_Q + (q - MIN_Q)] = w;
            }
        }
    }

    private static boolean insidePlaza(int q, int r) {
        return q >= 44 && q <= 50 && r >= 2 && r <= 8;
    }
    private static boolean insideRubble(int q, int r) {
        return (q >= 40 && q <= 41 && r >= 0 && r <= 3)
            || (q >= 46 && q <= 47 && r >= 9 && r <= 11)
            || (q >= 52 && q <= 53 && r >= -6 && r <= -1)
            || (q >= 53 && q <= 54 && r >= -2 && r <= 1);
    }

    public boolean walk(int q, int r) {
        if (q < MIN_Q || q > MAX_Q || r < MIN_R || r > MAX_R) return false;
        return walkable[(r - MIN_R) * W_Q + (q - MIN_Q)];
    }

    public boolean walkWorld(float wx, float wy) {
        float[] out = new float[2];
        worldToHex(wx, wy, out);
        return walk(Math.round(out[0]), Math.round(out[1]));
    }

    private void startBaker() {
        baker = new Thread(new Runnable() { public void run() {
            while (!Thread.interrupted()) {
                int key;
                synchronized (SceneMap.this) {
                    while (pendHead == pendTail) {
                        try { SceneMap.this.wait(200); } catch (InterruptedException e) { return; }
                    }
                    key = pendQ[pendHead];
                    pendHead = (pendHead + 1) & (PEND - 1);
                }
                bakeChunkKey(key);
            }
        }}, "map-baker");
        baker.setDaemon(true);
        baker.start();
    }

    private static int qkey(int cx, int cy) { return ((cy & 0xFFFF) << 16) | (cx & 0xFFFF); }

    private void enqueue(int cx, int cy) {
        int lru = cy * 4096 + cx;
        int k = qkey(cx, cy);
        synchronized (this) {
            for (int i = 0; i < chunkKeys.length; i++) if (chunkKeys[i] == lru) return;
            for (int i = pendHead; i != pendTail; i = (i + 1) & (PEND - 1)) if (pendQ[i] == k) return;
            if (((pendTail + 1) & (PEND - 1)) == pendHead) return;
            pendQ[pendTail] = k;
            pendTail = (pendTail + 1) & (PEND - 1);
            notify();
        }
    }

    private void bakeChunkKey(int key) {
        int cy = (short) (key >>> 16);
        int cx = (short) (key & 0xFFFF);
        Bitmap bmp = null;
        int slot = -1;
        int lru = cy * 4096 + cx;
        for (int i = 0; i < chunkKeys.length; i++) {
            if (chunkKeys[i] == Integer.MIN_VALUE) { slot = i; break; }
        }
        if (slot < 0) {
            long best = Long.MAX_VALUE;
            for (int i = 0; i < chunkUsed.length; i++)
                if (chunkUsed[i] < best) { best = chunkUsed[i]; slot = i; }
        }
        bmp = chunkBits[slot];
        if (bmp == null) {
            bmp = Bitmap.createBitmap(SRC, SRC, Bitmap.Config.RGB_565);
            chunkBits[slot] = bmp;
        }
        short[] px = bakeScratch;
        float baseWx = cx * CHUNK_PX;
        float baseWy = cy * CHUNK_PX;
        float[] hw = new float[2];
        int[] hq = new int[2];
        for (int y = 0; y < SRC; y++) {
            float wy = baseWy + (y + 0.5f) * BAKE;
            for (int x = 0; x < SRC; x++) {
                float wx = baseWx + (x + 0.5f) * BAKE;
                px[y * SRC + x] = samplePixel(wx, wy, hq, hw);
            }
        }
        bakeBuf.clear();
        bakeBuf.put(px).position(0);
        bmp.copyPixelsFromBuffer(bakeBuf);

        synchronized (this) {
            chunkKeys[slot] = lru;
            chunkUsed[slot] = ++frameStamp;
        }
    }

    // ================= BAKE-TIME PAINT HELPERS =================
    private static int h2(int x, int y, int s) {
        int h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ s * 0x9E3779B1;
        h ^= h >>> 15; h *= 0x85EBCA6B; h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }
    private static float ss(float a, float b, float x) {
        float t = (x - a) / (b - a); if (t < 0f) t = 0f; else if (t > 1f) t = 1f; return t * t * (3f - 2f * t);
    }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private float vnoise(float x, float y, int salt) {
        int xi = (int) x; if (x < xi) xi--;
        int yi = (int) y; if (y < yi) yi--;
        float fx = x - xi, fy = y - yi;
        fx = fx * fx * (3f - 2f * fx); fy = fy * fy * (3f - 2f * fy);
        float a = (h2(xi, yi, salt) >>> 16);
        float b = (h2(xi + 1, yi, salt) >>> 16);
        float c = (h2(xi, yi + 1, salt) >>> 16);
        float d = (h2(xi + 1, yi + 1, salt) >>> 16);
        float t = a + (b - a) * fx;
        float u = c + (d - c) * fx;
        return (t + (u - t) * fy) * (1f / 65535f);
    }

    private float grain(float x, float y, int salt) {
        int xi = (int) x; if (x < xi) xi--;
        int yi = (int) y; if (y < yi) yi--;
        return (h2(xi, yi, salt) >>> 16) * (1f / 65535f);
    }

    private void voronoi(float x, float y, float cell, int salt) {
        float gx = x / cell, gy = y / cell;
        int cx = (int) gx; if (gx < cx) cx--;
        int cy = (int) gy; if (gy < cy) cy--;
        float f1 = 1e9f, f2 = 1e9f;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                int hx = h2(cx + i, cy + j, salt);
                float px = cx + i + (hx >>> 16) * (1f / 65535f) * 0.85f + 0.075f;
                float py = cy + j + (hx & 0xFFFF) * (1f / 65535f) * 0.85f + 0.075f;
                float dx = px - gx, dy = py - gy;
                float d = dx * dx + dy * dy;
                if (d < f1) { f2 = f1; f1 = d; vH = (h2(cx + i, cy + j, salt ^ 0x9E37) >>> 16) * (1f / 65535f); }
                else if (d < f2) { f2 = d; }
            }
        }
        vF1 = f1; vF2 = f2;
    }

    private short pack565(float r, float g, float b, int px, int py) {
        int dIdx = ((px & 3) << 2) | (py & 3);
        int[] BAYER = {0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5};
        float d = (BAYER[dIdx] - 7.5f) / 7.5f;
        int ri = (int) (r * 0.12157f + d * 0.5f + 0.5f);
        int gi = (int) (g * 0.24706f + d * 0.5f + 0.5f);
        int bi = (int) (b * 0.12157f + d * 0.5f + 0.5f);
        if (ri < 0) ri = 0; else if (ri > 31) ri = 31;
        if (gi < 0) gi = 0; else if (gi > 63) gi = 63;
        if (bi < 0) bi = 0; else if (bi > 31) bi = 31;
        return (short) ((ri << 11) | (gi << 5) | bi);
    }

    private int fallbackColor(int cx, int cy) {
        float wx = (cx + 0.5f) * CHUNK_PX;
        float wy = (cy + 0.5f) * CHUNK_PX;
        float hy = wy * 1.6666667f;
        float qf = wx * 0.0060141f - hy * 0.0057870f;
        float rf = hy * 0.0115741f;
        int r, g, b;
        if (qf < 10) { if (rf < -4.5f) { r=12; g=11; b=14; } else { r=45; g=40; b=42; } }
        else if (qf < 36) { r=60; g=52; b=48; }
        else if (qf < 60) { r=52; g=48; b=50; }
        else if (qf < 78) { r=120; g=112; b=98; }
        else { r=28; g=26; b=30; }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ================= FRAGMENT SHADER (samplePixel) =================
    private short samplePixel(float wx, float wy, int[] hq, float[] hw) {
        worldToHex(wx, wy, hq);
        int q = hq[0], r = hq[1];

        float hy = wy * 1.6666667f;
        float qf = wx * 0.0060141f - hy * 0.0057870f;
        float rf = hy * 0.0115741f;

        int px = (int) (wx * 0.5f); if (wx * 0.5f < px) px--;
        int py = (int) (wy * 0.5f); if (wy * 0.5f < py) py--;

        if (q < MIN_Q || q > MAX_Q || r < MIN_R || r > MAX_R) {
            cR = 8; cG = 7; cB = 10;
            return pack565(cR, cG, cB, px, py);
        }

        float fq = qf - q;
        float fr = rf - r;

        float nLow = vnoise(wx * 0.0028f, wy * 0.0028f, 11);
        if (quality) nLow = nLow * 0.62f + 0.38f * vnoise(wx * 0.0067f, wy * 0.0067f, 12);
        float warp = quality ? vnoise(wx * 0.0105f, wy * 0.0105f, 23) : 0.5f;
        float nMid = vnoise((wx + (warp - 0.5f) * 52f) * 0.029f, (wy + (warp - 0.13f) * 52f) * 0.029f, 37);

        float gc = 0.5f, gx = 0f, gy = 0f;
        if (quality) {
            gc = grain(wx * 0.5f, wy * 0.5f, 61);
            gx = grain(wx * 0.5f + 0.75f, wy * 0.5f, 61) - grain(wx * 0.5f - 0.75f, wy * 0.5f, 61);
            gy = grain(wx * 0.5f, wy * 0.5f + 0.75f, 61) - grain(wx * 0.5f, wy * 0.5f - 0.75f, 61);
        }
        float bump = (gx * 0.62f + gy * 0.78f) * 12f;

        float wA = 1f - ss(7f, 13f, qf);
        float wD = ss(7f, 13f, qf) * (1f - ss(32f, 38f, qf));
        float wC = ss(32f, 38f, qf) * (1f - ss(56f, 62f, qf));
        float wY = ss(56f, 62f, qf) * (1f - ss(73f, 79f, qf));
        float wP = ss(73f, 79f, qf);

        if (wA >= wD && wA >= wC && wA >= wY && wA >= wP) {
            paintAshen(wx, wy, qf, rf, q, r, fq, fr, nLow, nMid, gc, bump);
        } else if (wD >= wC && wD >= wY && wD >= wP) {
            paintDescent(wx, wy, qf, rf, q, r, fq, fr, nLow, nMid, gc, bump);
        } else if (wC >= wY && wC >= wP) {
            paintCity(wx, wy, qf, rf, q, r, fq, fr, nLow, nMid, gc, bump);
        } else if (wY >= wP) {
            paintCourtyard(wx, wy, qf, rf, q, r, fq, fr, nLow, nMid, gc, bump);
        } else {
            paintCraterField(wx, wy, qf, rf, q, r, fq, fr, nLow, nMid, gc, bump);
        }

        cR *= 0.98f; cB *= 1.05f;
        return pack565(cR, cG, cB, px, py);
    }

    // ================= BIOME PAINTERS =================
    private void paintAshen(float wx, float wy, float qf, float rf, int q, int r, float fq, float fr, float nLow, float nMid, float gc, float bump) {
        float edge = rf + 4.5f;
        if (edge < -3.2f) {
            cR = 8 + nLow * 4; cG = 7 + nLow * 3; cB = 10 + nLow * 5;
            return;
        }
        if (edge < 0) {
            float strata = vnoise(wx * 0.05f, wy * 0.012f, 51);
            float depth = -edge / 3.2f;
            float base = 45f * (1.15f - 0.75f * depth) * (0.7f + 0.6f * strata);
            cR = base; cG = base * 0.85f; cB = base * 0.9f;
            float rim = ss(-0.05f, -0.28f, edge) * (0.5f + 0.5f * gc);
            cR += rim * 40f; cG += rim * 38f; cB += rim * 45f;
            float fog = ss(0.45f, 1f, depth);
            cR = lerp(cR, 10f, fog); cG = lerp(cG, 9f, fog); cB = lerp(cB, 12f, fog);
            cR += bump; cG += bump; cB += bump;
            return;
        }
        float dunes = vnoise(wx * 0.0055f, wy * 0.032f, 71);
        float mottle = 0.72f + 0.56f * nMid;
        float duneShade = 0.82f + 0.30f * dunes;
        float soot = ss(0.62f, 0.9f, nLow);
        cR = 47f * mottle * duneShade * (1f - 0.4f * soot);
        cG = 42f * mottle * duneShade * (1f - 0.4f * soot);
        cB = 44f * mottle * duneShade * (1f - 0.3f * soot);
        cR *= 0.92f + 0.16f * gc; cG *= 0.92f + 0.16f * gc; cB *= 0.92f + 0.16f * gc;

        if (rf > -3f && rf < 2f && qf > -5f && qf < 12f) {
            float ridge = 1f - Math.abs(vnoise(wx * 0.02f, wy * 0.02f, 41) * 2f - 1f);
            float vein = ridge * ridge * ridge * ridge;
            float intensity = ss(0.86f, 0.97f, ridge) * ss(-0.5f, -3f, rf) * (0.4f + 0.6f * nLow);
            cR += intensity * 90f + vein * 40f;
            cG += intensity * 30f + vein * 15f;
            cB += intensity * 8f + vein * 4f;
        }

        int sh = h2(q, r, 91);
        if ((sh >>> 16) < 20000 && edge > 0.5f) {
            float sx = ((sh >>> 8) & 255) / 255f - 0.5f;
            float sy = (sh & 255) / 255f - 0.5f;
            float dx = fq - sx * 0.6f, dy = fr - sy * 0.6f;
            float rad = 0.10f + ((sh >>> 4) & 15) / 15f * 0.12f;
            float d2 = (dx * dx + dy * dy * (((sh >>> 20) & 1) == 0 ? 1f : 2.6f)) / (rad * rad);
            if (d2 < 1f) {
                float m = (1f - d2) * 0.85f;
                cR = lerp(cR, 14f, m); cG = lerp(cG, 12f, m); cB = lerp(cB, 14f, m);
                float rimLight = ss(0.6f, 0.9f, d2) * ss(0.1f, -0.2f, dx + dy);
                cR += rimLight * 25f; cG += rimLight * 25f; cB += rimLight * 30f;
            }
        }
        cR += bump; cG += bump; cB += bump;
    }

    private void paintDescent(float wx, float wy, float qf, float rf, int q, int r, float fq, float fr, float nLow, float nMid, float gc, float bump) {
        float t = (qf - 11f) / 25f; if (t < 0f) t = 0f; if (t > 1f) t = 1f;
        float c = 2f - 7f * t;
        float halfW = 2.6f - 0.8f * t;
        float dr = rf - c;
        float adr = dr < 0 ? -dr : dr;
        float e = halfW - adr;

        if (e < -2.6f) {
            if (dr > 0) {
                float strata = vnoise(wx * 0.02f, wy * 0.075f, 51);
                cR = 35f * (0.6f + 0.55f * strata); cG = cR * 0.85f; cB = cR * 0.9f;
            } else {
                float depth = ss(-0.4f, -2.6f, e);
                cR = lerp(25f, 8f, depth); cG = lerp(22f, 7f, depth); cB = lerp(28f, 10f, depth);
            }
            cR += bump * 0.5f; cG += bump * 0.5f; cB += bump * 0.5f;
            return;
        }

        if (e > 0) {
            float center = 1f - adr / halfW;
            cR = lerp(64f, 92f, center); cG = lerp(56f, 78f, center); cB = lerp(50f, 64f, center);
            cR *= 0.9f + 0.2f * nMid; cG *= 0.9f + 0.2f * nMid; cB *= 0.9f + 0.2f * nMid;
            if (quality) {
                float fp = grain(wx * 0.32f, wy * 0.32f, 83);
                if (fp > 0.82f) { cR *= 0.85f; cG *= 0.85f; cB *= 0.85f; }
            }
        } else {
            if (dr > 0) {
                float wallH = -e;
                float strata = vnoise(wx * 0.02f, wy * 0.075f, 51);
                cR = 45f * (0.6f + 0.55f * strata) * (1.1f - 0.35f * (wallH < 2.5f ? wallH : 2.5f));
                cG = cR * 0.85f; cB = cR * 0.9f;
                float rim = ss(-0.2f, -0.02f, e);
                cR += rim * 30f; cG += rim * 32f; cB += rim * 40f;
            } else {
                float depth = ss(-0.4f, -2.6f, e);
                cR = lerp(35f, 8f, depth); cG = lerp(30f, 7f, depth); cB = lerp(38f, 10f, depth);
            }
        }
        cR += bump; cG += bump; cB += bump;

        float dq = qf - 21f, dqr = rf + 1f;
        float d2 = dq * dq + dqr * dqr;
        if (d2 < 9f) {
            float blood = ss(9f, 1f, d2) * (0.5f + 0.5f * nMid);
            cR = lerp(cR, 52f, blood * 0.6f); cG = lerp(cG, 18f, blood * 0.6f); cB = lerp(cB, 14f, blood * 0.6f);
        }
    }

    private void paintCity(float wx, float wy, float qf, float rf, int q, int r, float fq, float fr, float nLow, float nMid, float gc, float bump) {
        boolean street = (r >= -6 && r <= 12) && ((r % 5 == 0) || (q % 6 == 0) || insidePlaza(q, r));
        boolean rubble = insideRubble(q, r);

        if (street && !rubble) {
            if (insidePlaza(q, r)) {
                cR = 150f; cG = 144f; cB = 132f;
                float crack = ss(0.88f, 0.96f, 1f - Math.abs(vnoise(wx * 0.015f, wy * 0.015f, 71) * 2f - 1f));
                cR -= crack * 40f; cG -= crack * 40f; cB -= crack * 35f;
                cR *= 0.85f + 0.3f * nMid; cG *= 0.85f + 0.3f * nMid; cB *= 0.85f + 0.3f * nMid;
            } else {
                if (quality) {
                    voronoi(wx, wy, 11f, 81);
                    float cellTone = 0.7f + 0.6f * vH;
                    cR = 74f * cellTone; cG = 70f * cellTone; cB = 72f * cellTone;
                    float edge = (float)Math.sqrt(vF2) - (float)Math.sqrt(vF1);
                    if (edge < 0.15f) { cR *= 0.5f; cG *= 0.5f; cB *= 0.5f; }
                    if (vH < 0.18f) { cR = 36f; cG = 32f; cB = 30f; }
                } else {
                    cR = 65f + 25f * nMid; cG = 60f + 25f * nMid; cB = 62f + 25f * nMid;
                }
                float drift = ss(0.6f, 0.9f, nLow);
                cR = lerp(cR, 80f, drift * 0.3f); cG = lerp(cG, 76f, drift * 0.3f); cB = lerp(cB, 74f, drift * 0.3f);
            }
        } else if (rubble) {
            float dx = qf - (q >= 46 ? 46.5f : (q >= 52 ? 52.5f : 40.5f));
            float dy = rf - (r >= 9 ? 10f : (r >= -6 ? -3.5f : 1.5f));
            float mound = 1f - (dx * dx + dy * dy) * 0.15f;
            if (mound < 0f) mound = 0f;
            cR = 55f + 35f * mound; cG = 48f + 30f * mound; cB = 45f + 28f * mound;
            cR *= 0.8f + 0.4f * gc; cG *= 0.8f + 0.4f * gc; cB *= 0.8f + 0.4f * gc;
            float rebar = ss(0.85f, 0.95f, 1f - Math.abs(vnoise(wx * 0.04f, wy * 0.01f, 91) * 2f - 1f));
            cR -= rebar * 25f; cG -= rebar * 25f; cB -= rebar * 25f;
        } else {
            int bh = h2(q, r, 101);
            float roofN = vnoise(wx * 0.004f, wy * 0.004f, 105);
            float roofTone = 0.7f + 0.5f * roofN + 0.15f * ((bh >>> 16) / 65535f - 0.5f);
            cR = 58f * roofTone; cG = 54f * roofTone; cB = 58f * roofTone;
            float e = 0.5f - Math.max(Math.max(Math.abs(fq), Math.abs(fr)), Math.abs(fq + fr));
            float rim = ss(0.10f, 0.0f, e) * ss(0.1f, -0.25f, fq + fr);
            cR += rim * 22f; cG += rim * 24f; cB += rim * 30f;

            if (quality && (bh & 15) < 2) {
                int winX = (int)(wx * 0.125f), winY = (int)(wy * 0.125f);
                int wh = h2(winX, winY, 111);
                if ((wh >>> 16) < 4000) {
                    float wfx = wx * 0.125f - winX - 0.5f;
                    float wfy = wy * 0.125f - winY - 0.5f;
                    float wd2 = wfx * wfx + wfy * wfy;
                    if (wd2 < 0.15f) {
                        float glow = (0.15f - wd2) * 6f;
                        boolean lit = (wh & 31) < 4;
                        cR += glow * (lit ? 120f : 60f);
                        cG += glow * (lit ? 70f : 30f);
                        cB += glow * (lit ? 20f : 10f);
                    }
                }
            }
        }
        cR += bump; cG += bump; cB += bump;

        float dq = qf - 44f, dqr = rf - 6f;
        float d2 = dq * dq + dqr * dqr;
        if (d2 < 9f) {
            float blood = ss(9f, 1f, d2) * (0.5f + 0.5f * nMid);
            cR = lerp(cR, 52f, blood * 0.6f); cG = lerp(cG, 18f, blood * 0.6f); cB = lerp(cB, 14f, blood * 0.6f);
        }
    }

    private void paintCourtyard(float wx, float wy, float qf, float rf, int q, int r, float fq, float fr, float nLow, float nMid, float gc, float bump) {
        float dxh = (qf - 66f) / 10f, dyh = (rf - 4f) / 8f;
        float rho2 = dxh * dxh + dyh * dyh;
        float rho = (float)Math.sqrt(rho2);

        if (rho < 1.05f) {
            cR = 172f; cG = 162f; cB = 140f;
            cR *= 0.8f + 0.4f * nMid; cG *= 0.8f + 0.4f * nMid; cB *= 0.8f + 0.4f * nMid;
            float crack = ss(0.90f, 0.98f, 1f - Math.abs(vnoise(wx * 0.02f, wy * 0.02f, 102) * 2f - 1f));
            cR -= crack * 25f; cG -= crack * 25f; cB -= crack * 20f;

            if (quality) {
                float sp = grain(wx * 0.4f, wy * 0.4f, 103);
                if (sp > 0.985f) { cR += 25f; cG += 25f; cB += 20f; }
                else if (sp < 0.015f) { cR -= 20f; cG -= 20f; cB -= 15f; }
            }

            float dq = qf - 67f, dqr = rf - 4f;
            float d2 = dq * dq + dqr * dqr;
            float scorch = ss(9f, 1f, d2);
            cR = lerp(cR, 36f, scorch * 0.7f); cG = lerp(cG, 30f, scorch * 0.7f); cB = lerp(cB, 28f, scorch * 0.7f);

            float ring = (float)Math.cos(Math.sqrt(d2) * 7f + nMid * 1.5f);
            if (ring > 0.6f && d2 < 16f) {
                float rMask = (ring - 0.6f) * 2.5f * ss(16f, 4f, d2);
                cR -= rMask * 25f; cG -= rMask * 25f; cB -= rMask * 25f;
            }
        } else if (rho < 1.35f && quality) {
            float ang = (float)Math.atan2(dyh, dxh);
            int sector = (int)((ang + 3.14159f) * 3.5f + nMid * 2f);
            int sh = h2(sector, 0, 111);
            if ((sh >>> 16) < 45000) {
                float len = 0.06f + ((sh >>> 8) & 255) / 255f * 0.24f;
                if (rho > 1.0f - len && rho < 1.15f) {
                    cR = 205f; cG = 198f; cB = 178f;
                    float shade = 0.7f + 0.3f * ((sh & 255) / 255f);
                    cR *= shade; cG *= shade; cB *= shade;
                } else {
                    cR = 40f; cG = 36f; cB = 38f;
                }
            } else {
                cR = 40f; cG = 36f; cB = 38f;
            }
            float ao = ss(1.15f, 1.0f, rho);
            cR *= 1f - 0.4f * ao; cG *= 1f - 0.4f * ao; cB *= 1f - 0.4f * ao;
        } else {
            cR = 34f + 20f * nMid; cG = 30f + 18f * nMid; cB = 33f + 18f * nMid;
        }

        if (rf >= 1.5f && rf <= 6.5f && qf >= 56f && qf <= 78f && rho > 1.05f) {
            float causeway = ss(1.5f, 2.2f, rf) * (1f - ss(5.8f, 6.5f, rf));
            cR = lerp(cR, 140f, causeway * 0.6f); cG = lerp(cG, 134f, causeway * 0.6f); cB = lerp(cB, 120f, causeway * 0.6f);
        }

        cR += bump; cG += bump; cB += bump;

        float dq = qf - 66f, dqr = rf - 4f;
        float d2 = dq * dq + dqr * dqr;
        if (d2 < 9f) {
            float blood = ss(9f, 1f, d2) * (0.5f + 0.5f * nMid);
            cR = lerp(cR, 52f, blood * 0.6f); cG = lerp(cG, 18f, blood * 0.6f); cB = lerp(cB, 14f, blood * 0.6f);
        }
    }

    private void paintCraterField(float wx, float wy, float qf, float rf, int q, int r, float fq, float fr, float nLow, float nMid, float gc, float bump) {
        float cx = 14970.8f, cy = 345.6f;
        float dxw = wx - cx, dyw = wy - cy;
        float d2 = dxw * dxw + dyw * dyw;
        float d = (float)Math.sqrt(d2);
        float rho = d / 330f;

        if (qf >= 72.5f && qf <= 80f && rho > 1.05f && (rf < 1.5f || rf > 6.5f)) {
            int bh = h2(q, r, 121);
            float facet = 0.5f + 0.5f * ((bh >>> 16) / 65535f);
            cR = 88f * facet; cG = 84f * facet; cB = 82f * facet;
            float gap = ss(0.1f, 0.02f, Math.abs(fq) + Math.abs(fr) - 0.4f);
            cR = lerp(cR, 16f, gap); cG = lerp(cG, 14f, gap); cB = lerp(cB, 18f, gap);
            cR += bump; cG += bump; cB += bump;
            return;
        }

        if (rho > 1.35f) {
            float greenBleed = ss(1.5f, 1.05f, rho) * 0.25f;
            cR += greenBleed * 15f; cG += greenBleed * 40f; cB += greenBleed * 25f;
        } else if (rho > 1.0f) {
            cR = 88f; cG = 74f; cB = 62f;
            float ridge = 1f - Math.abs(vnoise(wx * 0.03f, wy * 0.03f, 141) * 2f - 1f);
            cR += ridge * 20f; cG += ridge * 15f; cB += ridge * 10f;
        } else {
            cR = lerp(150f, 26f, rho); cG = lerp(255f, 40f, rho); cB = lerp(170f, 34f, rho);
            float heat = ss(0.5f, 0.08f, rho);
            cR += heat * 60f; cG += heat * 120f; cB += heat * 70f;
            if (quality) {
                float ang = (float) Math.atan2(dyw, dxw);
                float c1 = Math.abs((ang * 1.43239f + nMid * 0.35f) % 1f - 0.5f);
                float c2 = Math.abs((rho * 6f + (nLow - 0.5f) * 0.8f) % 1f - 0.5f);
                float crack = (c1 < 0.026f || c2 < 0.03f) ? 1f : 0f;
                cR = lerp(cR, 10f, crack * 0.8f); cG = lerp(cG, 16f, crack * 0.8f); cB = lerp(cB, 12f, crack * 0.8f);
                if (heat > 0.2f && crack > 0.5f) { cR += 40f; cG += 120f; cB += 60f; }
            }
        }
        cR += bump * 0.5f; cG += bump * 0.5f; cB += bump * 0.5f;
    }
