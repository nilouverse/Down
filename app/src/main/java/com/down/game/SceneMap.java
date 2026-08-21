package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import java.io.InputStream;

/**
 * Story-map compositor, hex-cut edition. Ground = squashed-hexagon cuts sampled
 * from square atlas cells via BitmapShader local matrices (AA edges, 1 call/hex).
 * Passes: 1 hex terrain -> 1b macro hex overlay -> 2 flat decals -> 3 Y-sorted
 * upright props -> 4 additive glow. draw() allocates ZERO objects.
 */
public final class SceneMap {
    public static final float HEX = 96f, SQUASH = 0.6f, BAKE = 2f;
    public static final int CHUNK_PX = 1024, SRC = 512;
    public static final int MIN_Q = -32, MAX_Q = 96, MIN_R = -24, MAX_R = 24;
    public static final int W_Q = MAX_Q - MIN_Q + 1, W_R = MAX_R - MIN_R + 1;

    private static final float TS = 128f, SQ3 = 1.7320508f, ROWY = 1.5f * HEX * SQUASH;
    private static final int MAXT = 8192, PMAX = 1024;
    private static final float[] UH = { 0, -0.6f, 0.866f, -0.3f, 0.866f, 0.3f, 0, 0.6f, -0.866f, 0.3f, -0.866f, -0.3f };

    private final boolean[] walkable = new boolean[W_Q * W_R];
    private volatile Bitmap tAsh, tRoad, tCity, tCrater, tCliff, gGlow, pA, pB;
    private volatile Shader[] shaders = new Shader[5];
    private final int[] cellW = new int[5], cellH = new int[5];
    private volatile boolean ready, disposed;
    private final boolean quality;
    private boolean craterVisible;
    private Bitmap craterGlow;

    private final Paint tp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint gp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect srcR = new Rect(), dstR = new Rect();
    private final float[] HW = new float[2];
    private final Bitmap[] sheets = new Bitmap[5];
    private final Path hexP = new Path();
    private final Matrix mS = new Matrix();
    private final int[] tS = new int[MAXT], tI = new int[MAXT], tR = new int[MAXT],
            dI = new int[MAXT], pI = new int[MAXT], gI = new int[MAXT];
    private final float[] pBX = new float[PMAX], pBY = new float[PMAX];
    private final int[] pBI = new int[PMAX];

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
            Bitmap a = soften(flatten(dec(app, "map/ash", true))),
                    ro = soften(flatten(dec(app, "map/road", true))),
                    ci = soften(flatten(dec(app, "map/city", true))),
                    cr = soften(flatten(dec(app, "map/crater", true))),
                    cl = soften(flatten(dec(app, "map/cliff", true))),
                    gl = dec(app, "map/glow", false),
                    pa = key(dec(app, "map/props_a", false)),
                    pb = key(dec(app, "map/props_b", false));
            if (disposed) { recycle(a); recycle(ro); recycle(ci); recycle(cr); recycle(cl); recycle(gl); recycle(pa); recycle(pb); return; }
            tAsh = a; tRoad = ro; tCity = ci; tCrater = cr; tCliff = cl; gGlow = gl; pA = pa; pB = pb;
            Bitmap[] all = { a, ro, ci, cr, cl };
            Shader[] sh = new Shader[5];
            for (int i = 0; i < 5; i++) if (all[i] != null) {
                sh[i] = new BitmapShader(all[i], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                cellW[i] = all[i].getWidth() >> 2; cellH[i] = all[i].getHeight() >> 2;
            }
            shaders = sh;
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
                o.inMutable = true;
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

    // Flatten per-cell baked lighting so cells meet at equal tone.
    private static Bitmap flatten(Bitmap b) {
        if (b == null) return null;
        if (!b.isMutable()) { Bitmap m = b.copy(b.getConfig(), true); if (m == null) return b; b = m; }
        int w = b.getWidth(), h = b.getHeight(), cw = w >> 2, ch = h >> 2;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int strip = Math.max(4, ch >> 5);
        for (int cy = 0; cy < 4; cy++) for (int cx = 0; cx < 4; cx++) {
            int x0 = cx * cw, y0 = cy * ch;
            long sr = 0, sg = 0, sb = 0, n = 0, tr = 0, tg = 0, tb = 0, tN = 0, br = 0, bg = 0, bb = 0, bN = 0;
            long lr = 0, lg = 0, lb = 0, lN = 0, rr = 0, rg = 0, rb = 0, rN = 0;
            for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) {
                int c = px[(y0 + y) * w + x0 + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
                sr += r; sg += g; sb += bl; n++;
                if (y < strip) { tr += r; tg += g; tb += bl; tN++; }
                if (y >= ch - strip) { br += r; bg += g; bb += bl; bN++; }
                if (x < strip) { lr += r; lg += g; lb += bl; lN++; }
                if (x >= cw - strip) { rr += r; rg += g; rb += bl; rN++; }
            }
            float ar = sr / (float) n, ag = sg / (float) n, ab = sb / (float) n;
            float tR = tr / tN, tG = tg / tN, tB = tb / tN, bR = br / bN, bG = bg / bN, bB = bb / bN;
            float lR = lr / lN, lG = lg / lN, lB = lb / lN, rR = rr / rN, rG = rg / rN, rB = rb / rN;
            for (int y = 0; y < ch; y++) {
                float fy = y / (float) (ch - 1);
                float vr = tR + (bR - tR) * fy, vg = tG + (bG - tG) * fy, vb = tB + (bB - tB) * fy;
                for (int x = 0; x < cw; x++) {
                    float fx = x / (float) (cw - 1);
                    float biasr = (vr + lR + (rR - lR) * fx) * 0.5f;
                    float biasg = (vg + lG + (rG - lG) * fx) * 0.5f;
                    float biasb = (vb + lB + (rB - lB) * fx) * 0.5f;
                    if (biasr < 6) biasr = 6; if (biasg < 6) biasg = 6; if (biasb < 6) biasb = 6;
                    int i = (y0 + y) * w + x0 + x;
                    int c = px[i], r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
                    r = (int) (r * ar / biasr); g = (int) (g * ag / biasg); bl = (int) (bl * ab / biasb);
                    px[i] = 0xFF000000 | ((r > 255 ? 255 : r) << 16) | ((g > 255 ? 255 : g) << 8) | (bl > 255 ? 255 : bl);
                }
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    // Soften each cell's edge band so neighbors melt together.
    private static Bitmap soften(Bitmap b) {
        if (b == null) return null;
        if (!b.isMutable()) { Bitmap m = b.copy(b.getConfig(), true); if (m == null) return b; b = m; }
        int w = b.getWidth(), h = b.getHeight(), cw = w >> 2, ch = h >> 2;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] cell = new int[cw * ch], bl = new int[cw * ch], org = new int[cw * ch];
        int strip = Math.max(3, cw >> 6);
        for (int cy = 0; cy < 4; cy++) for (int cx = 0; cx < 4; cx++) {
            int x0 = cx * cw, y0 = cy * ch;
            for (int y = 0; y < ch; y++) System.arraycopy(px, (y0 + y) * w + x0, cell, y * cw, cw);
            System.arraycopy(cell, 0, org, 0, cell.length);
            for (int pass = 0; pass < 2; pass++) {
                for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) {
                    int r = 0, g = 0, bb = 0, n = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy; if (yy < 0) yy = 0; if (yy >= ch) yy = ch - 1;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx; if (xx < 0) xx = 0; if (xx >= cw) xx = cw - 1;
                            int c = cell[yy * cw + xx];
                            r += (c >> 16) & 255; g += (c >> 8) & 255; bb += c & 255; n++;
                        }
                    }
                    bl[y * cw + x] = 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (bb / n);
                }
                int[] t = cell; cell = bl; bl = t;
            }
            for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) {
                int d = x; if (y < d) d = y;
                int d2 = cw - 1 - x; if (d2 < d) d = d2;
                d2 = ch - 1 - y; if (d2 < d) d = d2;
                if (d >= strip) continue;
                float t = (1f - d / (float) strip) * 0.75f, it = 1f - t;
                int o = org[y * cw + x], s = cell[y * cw + x];
                int r = (int) ((o >> 16 & 255) * it + (s >> 16 & 255) * t);
                int g = (int) ((o >> 8 & 255) * it + (s >> 8 & 255) * t);
                int bb = (int) ((o & 255) * it + (s & 255) * t);
                px[(y0 + y) * w + x0 + x] = 0xFF000000 | (r << 16) | (g << 8) | bb;
            }
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

    // ================= HEX COMPOSER (ASHEN for now) =================
    private void computeHex(int i, int q, int r) {
        int h = h2(q, r, 7), hB = h2(q, r, 91);
        int ts = -1, ti = 0, rot = 0, di = -1, pr = -1, gl = -1;
        if (q < 10.5f) {
            if (q < MIN_Q || r < MIN_R || r > MAX_R || r < -7.7f || r > 15.2f) ts = -1;      // void
            else if (r < -4.5f || r > 12.5f) {                                  // cliff rims
                ts = 4; ti = (h >>> 4) & 15;
                rot = (((h >>> 12) & 1) * 3) | (((h >>> 14) & 1) << 3);         // 0/180 + mirror
            } else {                                                            // meadow
                ts = 0; ti = (h >>> 4) & 15;
                rot = (int) ((h >>> 12) % 6) | (((h >>> 14) & 1) << 3);         // 6 hex spins + mirror
                if ((h >>> 4) % 1000 < 6) gl = 6;
                if (q > -5 && q < 12 && r > -3 && r < 2 && vn(q * 0.3f, r * 0.3f, 57) > 0.6f
                        && (h >>> 4) % 100 < 9) gl = ((h >>> 9) & 1) == 0 ? 0 : 10;
                if ((h >>> 5) % 100 < 6) di = 6 + ((h >>> 17) & 1);
                if (quality && (hB >>> 20) % 100 < 2) di = 8;
                if ((hB >>> 16) % 1000 < 7) pr = 18 + ((hB >>> 20) & 1);
                if ((hB >>> 22) % 1000 < 4) pr = 20;
                if ((h >>> 20) % 1000 < 5) pr = 15;
            }
        }
        tS[i] = ts; tI[i] = ti; tR[i] = rot; dI[i] = di; pI[i] = pr; gI[i] = gl;
    }

    private void hexPath(float cx, float cy, float s) {
        hexP.reset();
        for (int k = 0; k < 6; k++) {
            float x = cx + UH[k * 2] * s, y = cy + UH[k * 2 + 1] * s;
            if (k == 0) hexP.moveTo(x, y); else hexP.lineTo(x, y);
        }
        hexP.close();
    }

    private void shaderHex(Canvas c, int sheet, int ti, int rot, float cx, float cy, float s) {
        Shader sh = shaders[sheet];
        if (sh == null) return;
        int cw = cellW[sheet], ch = cellH[sheet];
        int ox = (ti & 3) * cw, oy = (ti >> 2) * ch;
        mS.reset();
        mS.postTranslate(cx, cy);
        mS.postScale(s, s);
        if ((rot & 8) != 0) mS.postScale(-1f, 1f);
        if ((rot & 7) != 0) mS.postRotate(-((rot & 7) * 60f));
        mS.postScale(2f / cw, 2f / ch);
        mS.postTranslate(-(ox + cw * 0.5f), -(oy + ch * 0.5f));
        sh.setLocalMatrix(mS);
        tp.setShader(sh);
        hexPath(cx, cy, s);
        c.drawPath(hexP, tp);
        tp.setShader(null);
    }

    // ================= DRAW (zero allocation) =================
    public void draw(Canvas c, float camX, float camY, float zoom, int vw, int vh) {
        float halfW = vw / (2f * zoom), halfH = vh / (2f * zoom);
        int r0 = (int) Math.floor((camY - halfH) / ROWY) - 1, r1 = (int) Math.floor((camY + halfH) / ROWY) + 1;
        int q0 = (int) Math.floor((camX - halfW) / (SQ3 * HEX) - r1 / 2f) - 1;
        int q1 = (int) Math.floor((camX + halfW) / (SQ3 * HEX) - r0 / 2f) + 1;
        int n = (r1 - r0 + 1) * (q1 - q0 + 1);
        boolean full = ready && n <= MAXT;
        sheets[0] = tAsh; sheets[1] = tRoad; sheets[2] = tCity; sheets[3] = tCrater; sheets[4] = tCliff;
        if (full) {
            int i = 0;
            for (int r = r0; r <= r1; r++) for (int q = q0; q <= q1; q++, i++) computeHex(i, q, r);
        }
        float s = HEX * zoom * 1.07f;
        // PASS 1 — hex-cut terrain
        for (int r = r0, i = 0; r <= r1; r++) for (int q = q0; q <= q1; q++, i++) {
            hexToWorld(q, r, HW);
            float cx = (HW[0] - camX) * zoom + vw * 0.5f, cy = (HW[1] - camY) * zoom + vh * 0.5f;
            int ts = full ? tS[i] : -1;
            if (ts < 0 || shaders[ts] == null) {
                tp.setColor(fallback(HW[0], HW[1]));
                hexPath(cx, cy, s);
                c.drawPath(hexP, tp);
                continue;
            }
            shaderHex(c, ts, tI[i], tR[i], cx, cy, s);
        }
        if (!full) { if (craterVisible) drawCrater(c, camX, camY, zoom, vw, vh); return; }

        // PASS 1b — macro overlay: big semi-transparent hexes on even parity, crossing seams
        if (shaders[0] != null) {
            pp.setAlpha(76);
            for (int r = r0; r <= r1; r++) for (int q = q0; q <= q1; q++) {
                if (((q + r) & 1) != 0 || q < MIN_Q || q > 10 || r < -4 || r > 12) continue;
                int hh = h2(q, r, 131);
                hexToWorld(q, r, HW);
                float cx = (HW[0] - camX) * zoom + vw * 0.5f, cy = (HW[1] - camY) * zoom + vh * 0.5f;
                int cw = cellW[0], ch = cellH[0];
                int ox = ((hh >>> 4) & 15);
                int oxx = (ox & 3) * cw, oyy = (ox >> 2) * ch;
                int rot = (int) ((hh >>> 10) % 6) | ((hh >>> 13) & 1) << 3;
                mS.reset();
                mS.postTranslate(cx, cy);
                mS.postScale(s * 2f, s * 2f);
                if ((rot & 8) != 0) mS.postScale(-1f, 1f);
                if ((rot & 7) != 0) mS.postRotate(-((rot & 7) * 60f));
                mS.postScale(2f / cw, 2f / ch);
                mS.postTranslate(-(oxx + cw * 0.5f), -(oyy + ch * 0.5f));
                shaders[0].setLocalMatrix(mS);
                pp.setShader(shaders[0]);
                hexPath(cx, cy, s * 2f);
                c.drawPath(hexP, pp);
                pp.setShader(null);
            }
            pp.setAlpha(255);
        }

        // PASS 2 — flat decals
        if (pA != null) {
            for (int r = r0, i = 0; r <= r1; r++) for (int q = q0; q <= q1; q++, i++) {
                int d = dI[i];
                if (d < 0) continue;
                Bitmap b = pA; int ci = d;
                if (d >= 16) { if (pB == null) continue; b = pB; ci = d == 16 ? 9 : d == 17 ? 13 : 14; }
                int h = h2(q, r, 7);
                hexToWorld(q, r, HW);
                float cx = (HW[0] - camX) * zoom + vw * 0.5f + (((h >>> 8) & 255) / 255f - 0.5f) * TS * 0.4f * zoom;
                float cy = (HW[1] - camY) * zoom + vh * 0.5f + (((h >>> 16) & 255) / 255f - 0.5f) * TS * 0.4f * zoom;
                float ds = D_S[d] * TS * zoom;
                pp.setAlpha(D_A[d]);
                int cw = b.getWidth() >> 2, ch = b.getHeight() >> 2;
                srcR.set((ci & 3) * cw, (ci >> 2) * ch, ((ci & 3) + 1) * cw, ((ci >> 2) + 1) * ch);
                dstR.set((int) (cx - ds * 0.5f), (int) (cy - ds * 0.5f), (int) (cx + ds * 0.5f), (int) (cy + ds * 0.5f));
                c.drawBitmap(b, srcR, dstR, pp);
            }
            pp.setAlpha(255);
        }

        // PASS 3 — upright props, bottom-anchored, Y-sorted
        int pc = 0;
        for (int r = r0, i = 0; r <= r1; r++) for (int q = q0; q <= q1; q++, i++) {
            if (pI[i] < 0 || pc >= PMAX) continue;
            int h = h2(q, r, 7);
            hexToWorld(q, r, HW);
            pBX[pc] = (HW[0] + (((h >>> 8) & 255) / 255f - 0.5f) * TS * 0.45f - camX) * zoom + vw * 0.5f;
            pBY[pc] = (HW[1] + HEX * (0.2f + ((h >>> 16) & 255) / 255f * 0.4f) - camY) * zoom + vh * 0.5f;
            pBI[pc] = pI[i];
            pc++;
        }
        for (int a = 1; a < pc; a++) {
            float ky = pBY[a], kx = pBX[a]; int ki = pBI[a], j = a - 1;
            while (j >= 0 && pBY[j] > ky) { pBY[j + 1] = pBY[j]; pBX[j + 1] = pBX[j]; pBI[j + 1] = pBI[j]; j--; }
            pBY[j + 1] = ky; pBX[j + 1] = kx; pBI[j + 1] = ki;
        }
        for (int a = 0; a < pc; a++) {
            int p = pBI[a];
            Bitmap b = p < 16 ? pB : pA;
            if (b == null) continue;
            int ci = p < 16 ? p : P_A[p - 16];
            int cw = b.getWidth() >> 2, ch = b.getHeight() >> 2;
            srcR.set((ci & 3) * cw, (ci >> 2) * ch, ((ci & 3) + 1) * cw, ((ci >> 2) + 1) * ch);
            float ps = P_S[p] * TS * zoom;
            dstR.set((int) (pBX[a] - ps * 0.5f), (int) (pBY[a] - ps), (int) (pBX[a] + ps * 0.5f), (int) pBY[a]);
            c.drawBitmap(b, srcR, dstR, pp);
        }

        // PASS 4 — additive glow
        if (gGlow != null) {
            int gw = gGlow.getWidth() >> 2, gh = gGlow.getHeight() >> 2;
            for (int r = r0, i = 0; r <= r1; r++) for (int q = q0; q <= q1; q++, i++) {
                int g = gI[i];
                if (g < 0) continue;
                hexToWorld(q, r, HW);
                float cx = (HW[0] - camX) * zoom + vw * 0.5f, cy = (HW[1] - camY) * zoom + vh * 0.5f;
                float gs = G_S[g] * TS * zoom;
                srcR.set((g & 3) * gw, (g >> 2) * gh, ((g & 3) + 1) * gw, ((g >> 2) + 1) * gh);
                dstR.set((int) (cx - gs * 0.5f), (int) (cy - gs * 0.5f), (int) (cx + gs * 0.5f), (int) (cy + gs * 0.5f));
                c.drawBitmap(gGlow, srcR, dstR, gp);
            }
        }
        if (craterVisible) drawCrater(c, camX, camY, zoom, vw, vh);
    }

    private int fallback(float wx, float wy) {
        float hy = wy * 1.6666667f, qf = wx * 0.0060141f - hy * 0.0057870f, rf = hy * 0.0115741f;
        if (qf < 10.5f) return (rf < -4.5f || rf > 12.5f) ? 0xFF0c0b0e : 0xFF322d2b;
        if (qf < 36.5f) return 0xFF3a332e;
        if (qf < 60f) return 0xFF353136;
        if (qf < 78.5f) return 0xFF7a7264;
        return 0xFF1a2420;
    }

    public void setCraterVisible(boolean v) { craterVisible = v; }

    private void drawCrater(Canvas c, float camX, float camY, float zoom, int vw, int vh) {
        if (craterGlow == null) return;
        hexToWorld(88, 4, HW);
        float gx = (HW[0] - camX) * zoom + vw * 0.5f, gy = (HW[1] - camY) * zoom + vh * 0.5f;
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
        recycle(tAsh); recycle(tRoad); recycle(tCity); recycle(tCrater); recycle(tCliff);
        recycle(gGlow); recycle(pA); recycle(pB); recycle(craterGlow);
        tAsh = tRoad = tCity = tCrater = tCliff = gGlow = pA = pB = craterGlow = null;
        shaders = new Shader[5];
    }
    private static void recycle(Bitmap b) { if (b != null && !b.isRecycled()) b.recycle(); }
}
