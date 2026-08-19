package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    private static final short C_ASH  = rgb565(38, 33, 36);
    private static final short C_ASH2 = rgb565(52, 44, 48);
    private static final short C_ROCK = rgb565(26, 22, 26);
    private static final short C_PATH = rgb565(58, 48, 44);
    private static final short C_STRT = rgb565(46, 42, 46);
    private static final short C_WALL = rgb565(20, 18, 22);
    private static final short C_BONE = rgb565(196, 188, 168);
    private static final short C_BONE2= rgb565(158, 148, 130);
    private static final short C_VOID = rgb565(8, 7, 10);
    private static final short C_GLASS= rgb565(30, 46, 38);

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

    private volatile int bakeReqCX = Integer.MIN_VALUE, bakeReqCY = Integer.MIN_VALUE;
    private Thread baker;

    public SceneMap(Context ctx, boolean quality) {
        this.quality = quality;
        this.walkable = new boolean[W_Q * W_R];
        buildWalkability();

        int cap = 12;
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

    // Matches GameView's exact Flat-Top squashed hex math
    public static void hexToWorld(int q, int r, float[] out) {
        out[0] = HEX * (float) Math.sqrt(3) * (q + r / 2f);
        out[1] = HEX * 1.5f * r * SQUASH;
    }

    public static void worldToHex(float x, float y, int[] out) {
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
                } else if (q <= 36) {
                    float t = (q - 10) / 26f;
                    float cr = 2 + t * -16f;
                    w = Math.abs(r - cr) <= (2.6f - t * 0.8f);
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
            || (q >= 53 && q <= 54 && r >= -2 && r <= 1);
    }

    public boolean walk(int q, int r) {
        if (q < MIN_Q || q > MAX_Q || r < MIN_R || r > MAX_R) return false;
        return walkable[(r - MIN_R) * W_Q + (q - MIN_Q)];
    }

    public boolean walkWorld(float wx, float wy) {
        int[] out = new int[2];
        worldToHex(wx, wy, out);
        return walk(out[0], out[1]);
    }

    private void startBaker() {
        baker = new Thread(new Runnable() { public void run() {
            while (!Thread.interrupted()) {
                int cx, cy;
                synchronized (SceneMap.this) {
                    cx = bakeReqCX; cy = bakeReqCY;
                    if (cx == Integer.MIN_VALUE) {
                        try { SceneMap.this.wait(200); } catch (InterruptedException e) { return; }
                        continue;
                    }
                    bakeReqCX = Integer.MIN_VALUE;
                }
                bakeChunk(cx, cy);
            }
        }}, "map-baker");
        baker.setDaemon(true);
        baker.start();
    }

    private void bakeChunk(int cx, int cy) {
        Bitmap bmp = null;
        int slot = -1;
        for (int i = 0; i < chunkBits.length; i++) {
            if (chunkKeys[i] == Integer.MIN_VALUE) { slot = i; break; }
        }
        if (slot < 0) {
            long best = Long.MAX_VALUE;
            for (int i = 0; i < chunkUsed.length; i++)
                if (chunkUsed[i] < best) { best = chunkUsed[i]; slot = i; }
            bmp = chunkBits[slot];
        }
        if (bmp == null) {
            bmp = Bitmap.createBitmap(SRC, SRC, Bitmap.Config.RGB_565);
            chunkBits[slot] = bmp;
        }
        short[] px = new short[SRC * SRC];
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
        ShortBuffer sb = ByteBuffer.allocateDirect(px.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(px).position(0);
        bmp.copyPixelsFromBuffer(sb);
        chunkKeys[slot] = cy * 4096 + cx; 
        chunkUsed[slot] = ++frameStamp;
    }

    private short samplePixel(float wx, float wy, int[] hq, float[] hw) {
        worldToHex(wx, wy, hq);
        int q = hq[0], r = hq[1];
        if (q < MIN_Q || q > MAX_Q || r < MIN_R || r > MAX_R) return C_VOID;

        if (q <= 10) {
            if (r < -4) return C_VOID;
            if (quality && valueNoise(wx, wy, 97) > 0.82f) return C_ROCK;
            return ((q + r) & 3) == 0 ? C_ASH2 : C_ASH;
        } else if (q <= 36) {
            float t = (q - 10) / 26f;
            float c = 2 + t * -16f;
            if (Math.abs(r - c) <= (2.6f - t * 0.8f)) {
                if (quality && valueNoise(wx, wy, 31) > 0.7f) return C_ROCK;
                return ((q + r) & 1) == 0 ? C_PATH : C_ASH2;
            }
            return r > c ? C_ROCK : C_VOID;
        } else if (q <= 60) {
            boolean street = (r >= -6 && r <= 12) && ((r % 5 == 0) || (q % 6 == 0) || insidePlaza(q, r));
            if (street && !insideRubble(q, r)) {
                return (((int)(wx / 16) + (int)(wy / 16)) & 1) == 0 ? C_STRT : C_ASH2;
            } else if (insideRubble(q, r)) {
                return C_ROCK;
            }
            return ((q ^ r) & 7) < 5 ? C_WALL : C_ROCK;
        } else if (q <= 78) {
            float dx = (q - 66) / 10f, dy = (r - 4) / 8f;
            boolean in = (dx * dx + dy * dy) <= 1f;
            if (in) {
                if (quality && valueNoise(wx, wy, 77) > 0.86f) return C_BONE2;
                return ((q + r) & 3) == 0 ? C_BONE2 : C_BONE;
            }
            return (r >= 2 && r <= 6) ? C_STRT : C_ROCK;
        } else {
            if (q > 82 && quality && valueNoise(wx, wy, 13) > 0.88f) return C_GLASS;
            return ((q + r) & 7) == 0 ? C_GLASS : C_VOID;
        }
    }

    private float valueNoise(float x, float y, int salt) {
        int xi = (int) Math.floor(x / 16f), yi = (int) Math.floor(y / 16f);
        int n = noiseSeed[((xi * 7 + yi * 13 + salt) & 255)];
        return ((n >>> 8) & 1023) / 1023f;
    }

    public void draw(Canvas c, float camX, float camY, float zoom, int vw, int vh) {
        int x0 = (int) Math.floor((camX - vw / (2f * zoom)) / CHUNK_PX) - 1;
        int x1 = (int) Math.floor((camX + vw / (2f * zoom)) / CHUNK_PX) + 1;
        int y0 = (int) Math.floor((camY - vh / (2f * zoom)) / CHUNK_PX) - 1;
        int y1 = (int) Math.floor((camY + vh / (2f * zoom)) / CHUNK_PX) + 1;

        for (int cy = y0; cy <= y1; cy++) {
            for (int cx = x0; cx <= x1; cx++) {
                Bitmap b = acquire(cx, cy);
                if (b == null) continue;
                srcR.set(0, 0, SRC, SRC);
                dstR.set(
                    (int) ((cx * CHUNK_PX - camX) * zoom + vw / 2f),
                    (int) ((cy * CHUNK_PX - camY) * zoom + vh / 2f),
                    (int) ((cx * CHUNK_PX + CHUNK_PX - camX) * zoom + vw / 2f),
                    (int) ((cy * CHUNK_PX + CHUNK_PX - camY) * zoom + vh / 2f));
                c.drawBitmap(b, srcR, dstR, bmpPaint);
            }
        }
        if (craterVisible) {
            float[] hw = new float[2];
            hexToWorld(88, 4, hw);
            float gx = hw[0], gy = hw[1];
            dstR.set((int) ((gx - 500 - camX) * zoom + vw / 2f),
                     (int) ((gy - 500 - camY) * zoom + vh / 2f),
                     (int) ((gx + 500 - camX) * zoom + vw / 2f),
                     (int) ((gy + 500 - camY) * zoom + vh / 2f));
            c.drawBitmap(craterGlow, null, dstR, glowPaint);
        }
    }

    private Bitmap acquire(int cx, int cy) {
        int key = cy * 4096 + cx;
        for (int i = 0; i < chunkKeys.length; i++) {
            if (chunkKeys[i] == key) { chunkUsed[i] = ++frameStamp; return chunkBits[i]; }
        }
        requestBake(cx, cy);
        return null;
    }

    private void requestBake(int cx, int cy) {
        synchronized (this) {
            if (bakeReqCX == Integer.MIN_VALUE) {
                bakeReqCX = cx; bakeReqCY = cy;
                this.notify();
            }
        }
    }

    public void setCraterVisible(boolean v) { craterVisible = v; }

    private void bakeCraterGlow() {
        int r = 128;
        craterGlow = Bitmap.createBitmap(r * 2, r * 2, Bitmap.Config.RGB_565);
        short[] px = new short[r * r * 4];
        for (int y = 0; y < r * 2; y++)
            for (int x = 0; x < r * 2; x++) {
                float dx = (x - r) / (float) r, dy = (y - r) / (float) r;
                float d = dx * dx + dy * dy;
                int a = d < 1f ? (int) ((1f - d) * 140f) : 0;
                px[y * r * 2 + x] = rgb565(a / 3, a, a / 5);
            }
        ShortBuffer sb = ByteBuffer.allocateDirect(px.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(px).position(0);
        craterGlow.copyPixelsFromBuffer(sb);
        glowPaint.setAlpha(160);
        glowPaint.setFilterBitmap(true);
    }

    public void dispose() {
        if (baker != null) baker.interrupt();
        for (int i = 0; i < chunkBits.length; i++) {
            if (chunkBits[i] != null) { chunkBits[i].recycle(); chunkBits[i] = null; chunkKeys[i] = Integer.MIN_VALUE; }
        }
        if (craterGlow != null) { craterGlow.recycle(); craterGlow = null; }
    }
}
