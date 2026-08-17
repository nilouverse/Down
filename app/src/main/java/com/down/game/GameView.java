package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GameView extends SurfaceView implements Runnable {

    private static final int PH_PLAYER = 0, PH_ENEMY = 1;
    private static final float HEX = 96f, SQUASH = 0.5f;
    private static final int MOVE_HEX = 3;
    private static final float TILE = 192f;
    private static final int CHUNK_T = 3;
    private static final float CHUNK_PX = TILE * CHUNK_T;
    private static final float PLAYER_H = 260f, ENEMY_H = 200f;
    private static final long FRAME_NS = 16666667L;
    private static final float ZOOM_MIN = 0.9f, ZOOM_MAX = 2.0f;
    private static final int[][] DIRS = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

    private Thread loop;
    private volatile boolean running;

    private Thread chunkThread;
    private final Object chunkLock = new Object();
    private final ArrayDeque<Long> chunkQueue = new ArrayDeque<>();
    private volatile boolean chunkRunning;
    private int lastCX = Integer.MIN_VALUE, lastCY;

    private final Player player = new Player();
    private float camX, camY;
    private float zoom = 1.25f;
    private boolean exploring;
    private float exploreT;
    private float downX = -9999, downY, lastPX, lastPY;
    private boolean moved, panning;

    private List<Bitmap> idleF, glide, attack, eGlide, eAttack, props, props2;
    private final ArrayList<Bitmap> tileVar = new ArrayList<>();
    private final ArrayList<Bitmap> roadVar = new ArrayList<>();
    private final ArrayList<Bitmap> grassVar = new ArrayList<>();

    private final Map<Long, Bitmap> chunks = Collections.synchronizedMap(
            new LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, Bitmap> e) {
                    return size() > 28;
                }
            });

    private float runeX, runeY, runeT = 99;

    private static class Puff { float x, y, t; }
    private final ArrayList<Puff> puffs = new ArrayList<>();
    private float puffTimer;

    private RadialGradient vig;
    private LinearGradient haze;
    private RadialGradient glow;
    private final Paint vigPaint = new Paint();
    private final Paint hazePaint = new Paint();
    private final Paint glowPaint = new Paint();
    private Bitmap shadowBmp;
    private static class Ember { float x, y, s; }
    private final ArrayList<Ember> embers = new ArrayList<>();

    private boolean hexesShown = false;
    private int moveLeft = 3;
    private int attackRangeShown = 0;
    private int attackType = 1;
    private Enemy targetEnemy = null;
    private Enemy strikeTarget = null;
    private Enemy pendingBolt = null;
    private static class Bolt { float x, y, x0, y0, tx, ty, t; Enemy tgt; }
    private final ArrayList<Bolt> bolts = new ArrayList<>();

    private int phase = PH_PLAYER;
    private float phaseT = 0;
    private int ei = 0;
    private boolean hasAttacked = false;

    private final ArrayList<Enemy> enemies = new ArrayList<>();
    private int playerHp = 100;
    private float hurtT = 0, deadT = 0;
    private boolean playerHitDone = false;
    private static class Dmg { float x, y, t; int val; }
    private final ArrayList<Dmg> dmgs = new ArrayList<>();

    private static class D { float y; int kind; Enemy en; Bitmap pr; float ax, ay; }
    private final ArrayList<D> drawList = new ArrayList<>();
    private final ArrayList<D> dPool = new ArrayList<>();
    private static final Comparator<D> BY_Y = new Comparator<D>() {
        public int compare(D a, D b) { return a.y < b.y ? -1 : (a.y > b.y ? 1 : 0); }
    };

    private static final float[] FW_A = new float[2];
    private static final int[] IH_A = new int[2];
    private static final int[] IH_B = new int[2];
    private static final int[] IH_C = new int[2];
    private static final float[] TW_F = new float[2];
    private static final int[] TW_A = new int[2];
    private static final int[] TW_B = new int[2];
    private static final int[] TW_C = new int[2];
    private static final float[] HO_F = new float[2];
    private static final float[] HO_LA = new float[2];
    private static final int[] HO_A = new int[2];
    private static final int[] HO_B = new int[2];

    private final Paint paint = new Paint();
    private final Paint propPaint = new Paint();
    private final Paint tintPaint = new Paint();
    private final Path hexPath = new Path();
    private final RectF rf = new RectF();
    private final ColorMatrixColorFilter brightFilter = new ColorMatrixColorFilter(new float[] {
            1.16f, 0, 0, 0, 12,
            0, 1.16f, 0, 0, 12,
            0, 0, 1.16f, 0, 12,
            0, 0, 0, 1, 0 });
    private int W, H;

    public GameView(Context ctx) {
        super(ctx);
        idleF   = Sprites.cutSheet(ctx, "sprites/idle.png",         2, 2, 4);
        glide   = Sprites.cutSheet(ctx, "sprites/glide.png",        2, 4, 4);
        attack  = Sprites.cutSheet(ctx, "sprites/attack.png",       2, 4, 4);
        eGlide  = Sprites.cutSheet(ctx, "sprites/enemy_glide.png",  2, 4, 4);
        eAttack = Sprites.cutSheet(ctx, "sprites/enemy_attack.png", 2, 4, 4);
        props   = Sprites.cutSheet(ctx, "sprites/props.png",        2, 4, 4);
        props2  = Sprites.cutSheet(ctx, "sprites/props2.png",       2, 4, 4);

        addMirrored(tileVar, Sprites.cutSheet(ctx, "sprites/tiles.png", 2, 2, 10));
        List<Bitmap> t2 = Sprites.cutSheet(ctx, "sprites/tiles2.png", 2, 2, 10);
        if (t2.size() >= 4) {
            List<Bitmap> road = new ArrayList<>(); road.add(t2.get(0)); road.add(t2.get(1));
            List<Bitmap> grass = new ArrayList<>(); grass.add(t2.get(2)); grass.add(t2.get(3));
            addMirrored(roadVar, road);
            addMirrored(grassVar, grass);
        }

        paint.setFilterBitmap(true);
        propPaint.setFilterBitmap(true);
        tintPaint.setFilterBitmap(true);
        tintPaint.setColorFilter(new ColorMatrixColorFilter(new float[] {
                1, 0, 0, 0, 120,
                0, 0.6f, 0, 0, 0,
                0, 0, 0.6f, 0, 0,
                0, 0, 0, 1, 0 }));

        shadowBmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(shadowBmp);
        Paint shp = new Paint();
        shp.setShader(new RadialGradient(64, 64, 62, 0xB4000000, 0x00000000, Shader.TileMode.CLAMP));
        sc.drawRect(0, 0, 128, 128, shp);

        worldToHex(640, 640, IH_A);
        hexToWorld(IH_A[0], IH_A[1], FW_A);
        player.x = FW_A[0]; player.y = FW_A[1];
        player.targetX = FW_A[0]; player.targetY = FW_A[1];

        for (int i = 0; i < 3; i++) spawnEnemy();
        startPlayerTurn();
    }

    private void addMirrored(ArrayList<Bitmap> dest, List<Bitmap> src) {
        for (Bitmap t : src) {
            dest.add(t);
            Matrix m = new Matrix();
            m.preScale(-1, 1);
            m.postTranslate(t.getWidth(), 0);
            dest.add(Bitmap.createBitmap(t, 0, 0, t.getWidth(), t.getHeight(), m, false));
        }
    }

    public void start() {
        if (running) return;
        running = true;
        chunkRunning = true;
        chunkThread = new Thread(new Runnable() { public void run() { chunkRunner(); } });
        chunkThread.start();
        loop = new Thread(this);
        loop.start();
    }

    public void stop() {
        running = false;
        chunkRunning = false;
        synchronized (chunkLock) { chunkLock.notifyAll(); }
        try { if (loop != null) loop.join(); } catch (Exception e) {}
        try { if (chunkThread != null) chunkThread.join(); } catch (Exception e) {}
    }

    private void chunkRunner() {
        while (chunkRunning) {
            long key;
            synchronized (chunkLock) {
                while (chunkRunning && chunkQueue.isEmpty()) {
                    try { chunkLock.wait(); } catch (Exception e) {}
                }
                if (!chunkRunning) break;
                key = chunkQueue.pollFirst();
            }
            int cx = (int) (key >> 32);
            int cy = (int) key;
            if (chunks.get(key) == null) chunks.put(key, bakeChunk(cx, cy));
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w; H = h;
        vig = new RadialGradient(W / 2f, H / 2f, Math.max(W, H) * 0.75f,
                0x00000000, 0x78000000, Shader.TileMode.CLAMP);
        vigPaint.setShader(vig);
        haze = new LinearGradient(0, 0, 0, H,
                new int[] { 0x26140a1c, 0x00000000, 0x00000000, 0x1C10061a },
                new float[] { 0f, 0.22f, 0.72f, 1f }, Shader.TileMode.CLAMP);
        hazePaint.setShader(haze);
        glow = new RadialGradient(W * 0.18f, -H * 0.25f, H * 1.15f,
                0x20ffffff, 0x00000000, Shader.TileMode.CLAMP);
        glowPaint.setShader(glow);
        if (embers.isEmpty()) {
            for (int i = 0; i < 40; i++) {
                Ember em = new Ember();
                em.x = (float) (Math.random() * W);
                em.y = (float) (Math.random() * H);
                em.s = 20 + (float) (Math.random() * 60);
                embers.add(em);
            }
        }
    }

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = (now - last) / 1e9f;
            last = now;
            if (dt > 0.1f) dt = 0.1f;
            update(dt);
            draw();
            long rem = FRAME_NS - (System.nanoTime() - now);
            if (rem > 0) {
                try { Thread.sleep(rem / 1000000, (int) (rem % 1000000)); } catch (Exception e) {}
            }
        }
    }

    // ---------- hex math ----------
    private static void hexToWorld(int q, int r, float[] out) {
        out[0] = HEX * (float) Math.sqrt(3) * (q + r / 2f);
        out[1] = HEX * 1.5f * r * SQUASH;
    }

    private static void worldToHex(float x, float y, int[] out) {
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

    private static int hexDist(int q1, int r1, int q2, int r2) {
        int dq = q1 - q2, dr = r1 - r2;
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
    }

    // ---------- terrain ----------
    private static float roadCenterF(float tx) {
        return 2.2f * (float) Math.sin(tx * 0.12f) + 1.5f * (float) Math.sin(tx * 0.05f + 1.7f);
    }

    private static int terrainAt(int tx, int ty) {
        if (Math.abs(ty - roadCenterF(tx)) < 0.9f) return 1;
        int gh = ((tx >> 1) * 331) ^ ((ty >> 1) * 757);
        if (((gh >>> 5) % 6) == 0) return 2;
        return 0;
    }

    private static boolean isLargeCell(int tx, int ty) {
        if (terrainAt(tx, ty) == 1) return false;
        return ((tx * 12347 ^ ty * 98765) & 15) == 0;
    }

    private static void largeAnchor(int tx, int ty, float[] out) {
        int h = (tx * 12347 ^ ty * 98765);
        float ox = ((h >>> 6) & 63) - 32;
        out[0] = tx * TILE + TILE / 2f + ox;
        out[1] = (ty + 1) * TILE;
    }

    private Bitmap groundBitmap(int wx, int wy) {
        int t = terrainAt(wx, wy);
        List<Bitmap> src = (t == 2) ? grassVar : tileVar;
        if (src.isEmpty()) return null;
        int h = ((wx >> 1) * 40503 ^ (wy >> 1) * 66827);
        return src.get(((h & 1) << 1) | ((h >>> 2) & 1));
    }

    private synchronized boolean hexBlocked(int q, int r) {
        hexToWorld(q, r, HO_F);
        int tx0 = (int) Math.floor(HO_F[0] / TILE), ty0 = (int) Math.floor(HO_F[1] / TILE);
        for (int ty = ty0 - 1; ty <= ty0 + 1; ty++)
            for (int tx = tx0 - 1; tx <= tx0 + 1; tx++) {
                if (!isLargeCell(tx, ty)) continue;
                largeAnchor(tx, ty, HO_LA);
                worldToHex(HO_LA[0], HO_LA[1] - 30, HO_A);
                worldToHex(HO_LA[0], HO_LA[1] - 30 - HEX, HO_B);
                if ((HO_A[0] == q && HO_A[1] == r) || (HO_B[0] == q && HO_B[1] == r)) return true;
            }
        return false;
    }

    private synchronized boolean hexOccupied(int q, int r, Enemy self) {
        worldToHex(player.x, player.y, HO_A);
        if (HO_A[0] == q && HO_A[1] == r) return true;
        for (Enemy en : enemies) {
            if (en.dead || en == self) continue;
            worldToHex(en.x, en.y, HO_B);
            if (HO_B[0] == q && HO_B[1] == r) return true;
        }
        return false;
    }

    private synchronized boolean hexFree(int q, int r, Enemy self) {
        return !hexOccupied(q, r, self) && !hexBlocked(q, r);
    }

    // ---------- ground chunk baking (worker thread only for prewarm) ----------
    private Bitmap getChunk(int cx, int cy) {
        long key = ((long) cx << 32) | (cy & 0xFFFFFFFFL);
        Bitmap b = chunks.get(key);
        if (b != null) return b;
        b = bakeChunk(cx, cy);
        chunks.put(key, b);
        return b;
    }

    private Bitmap bakeChunk(int cx, int cy) {
        int px = (int) CHUNK_PX;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setFilterBitmap(true);
        p.setColorFilter(brightFilter);
        Paint sp = new Paint();
        sp.setFilterBitmap(true);
        RectF lr = new RectF(), lr2 = new RectF();
        Rect src = new Rect();
        Path ep = new Path();
        float[] la = new float[2];
        int ox = cx * CHUNK_T, oy = cy * CHUNK_T;

        for (int ty = 0; ty < CHUNK_T; ty++) {
            for (int tx = 0; tx < CHUNK_T; tx++) {
                int wx = ox + tx, wy = oy + ty;
                float x = tx * TILE, y = ty * TILE;

                Bitmap base = groundBitmap(wx, wy);
                lr.set(x, y, x + TILE + 1, y + TILE + 1);
                if (base != null) {
                    c.drawBitmap(base, null, lr, p);
                } else {
                    p.setColorFilter(null);
                    p.setColor(0xFF241a2e);
                    c.drawRect(lr, p);
                    p.setColorFilter(brightFilter);
                }

                for (int[] dr : DIRS) {
                    Bitmap nb = groundBitmap(wx + dr[0], wy + dr[1]);
                    if (nb == null || nb == base) continue;
                    int eh = (wx * 7331 ^ wy * 9283 ^ (dr[0] * 5 + dr[1] * 3));
                    int n = 2 + (eh & 1);
                    ep.reset();
                    for (int k2 = 0; k2 < n; k2++) {
                        float f1 = ((eh >>> (k2 * 5 + 2)) & 255) / 255f;
                        float f2 = ((eh >>> (k2 * 5 + 6)) & 255) / 255f;
                        float rad = 26 + f2 * 44;
                        float exx, eyy;
                        if (dr[0] != 0) { exx = (dr[0] > 0) ? x + TILE : x; eyy = y + f1 * TILE; }
                        else            { eyy = (dr[1] > 0) ? y + TILE : y; exx = x + f1 * TILE; }
                        ep.addCircle(exx, eyy, rad, Path.Direction.CW);
                    }
                    float nx = x + dr[0] * TILE, ny = y + dr[1] * TILE;
                    lr2.set(nx, ny, nx + TILE + 1, ny + TILE + 1);
                    c.save();
                    c.clipPath(ep);
                    c.drawBitmap(nb, null, lr2, p);
                    c.restore();
                }

                p.setColorFilter(null);
                int sh = ((wx >> 1) * 40503 ^ (wy >> 1) * 66827);
                int shade = (sh >>> 8) & 3;
                if (shade > 0) {
                    lr.set(x, y, x + TILE + 1, y + TILE + 1);
                    if (shade == 3) { p.setColor(0xFFffffff); p.setAlpha(6); }
                    else { p.setColor(0xFF000000); p.setAlpha(shade * 4); }
                    c.drawRect(lr, p);
                    p.setAlpha(255);
                }

                int t = terrainAt(wx, wy);
                int ph2 = (wx * 92821 ^ wy * 68927);
                if (t == 0 && !isLargeCell(wx, wy) && ((ph2 >>> 2) & 15) == 0 && !props.isEmpty()) {
                    Bitmap pr = props.get((ph2 >>> 3) % props.size());
                    float sox = ((ph2 >>> 6) & 127) - 64;
                    float soy = ((ph2 >>> 12) & 63) - 32;
                    float s = (TILE * 0.45f) / pr.getHeight();
                    float pxx = x + TILE / 2f + sox, pyy = y + TILE + soy;
                    lr.set(pxx - pr.getWidth() * s / 2f, pyy - pr.getHeight() * s,
                           pxx + pr.getWidth() * s / 2f, pyy);
                    c.drawBitmap(pr, null, lr, sp);
                }
                p.setColorFilter(brightFilter);
            }
        }
        p.setColorFilter(null);

        int chh = (cx * 92821 ^ cy * 68927);
        for (int i = 0; i < 3; i++) {
            float bx = ((chh >>> (i * 5)) & 511) / 511f * px;
            float by = ((chh >>> (i * 5 + 9)) & 511) / 511f * px;
            float br = 260 + ((chh >>> (i * 3)) & 255);
            int col = (i == 2) ? 0x10000000 : 0x12ffffff;
            p.setShader(new RadialGradient(bx, by, br, col, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, px, px, p);
        }
        p.setShader(null);

        for (int ty = 0; ty < CHUNK_T; ty++) {
            for (int tx = 0; tx < CHUNK_T; tx++) {
                int wx = ox + tx, wy = oy + ty;
                if (!isLargeCell(wx, wy)) continue;
                largeAnchor(wx, wy, la);
                float lx = la[0] - ox * TILE, ly = la[1] - oy * TILE;
                lr.set(lx - TILE * 0.55f, ly - TILE * 0.20f, lx + TILE * 0.55f, ly + TILE * 0.12f);
                c.drawBitmap(shadowBmp, null, lr, sp);
            }
        }

        if (!roadVar.isEmpty()) {
            float strip = 24f;
            int tw = roadVar.get(0).getWidth(), th = roadVar.get(0).getHeight();
            for (float x = -strip; x < px + strip; x += strip) {
                float wxp = ox * TILE + x;
                float tx = wxp / TILE;
                float cyw = (roadCenterF(tx) - oy * CHUNK_T) * TILE;
                float half = TILE * (0.82f + 0.10f * (float) Math.sin(tx * 0.21f + 0.9f));
                p.setColor(0xFF000000);
                for (int e = 0; e < 3; e++) {
                    p.setAlpha(16 - e * 5);
                    float grow = 4 + e * 6f;
                    lr.set(x - 2, cyw - half - grow, x + strip + 2, cyw - half + 2);
                    c.drawRect(lr, p);
                    lr.set(x - 2, cyw + half - 2, x + strip + 2, cyw + half + grow);
                    c.drawRect(lr, p);
                }
                p.setAlpha(255);
            }
            for (float x = -strip; x < px + strip; x += strip) {
                float wxp = ox * TILE + x;
                float tx = wxp / TILE;
                float cyw = (roadCenterF(tx) - oy * CHUNK_T) * TILE;
                float half = TILE * (0.82f + 0.10f * (float) Math.sin(tx * 0.21f + 0.9f));
                int vi = (((int) Math.floor(wxp / CHUNK_PX)) & 1) * 2;
                float u = (wxp % CHUNK_PX) / CHUNK_PX;
                if (u < 0) u += 1f;
                int s0 = (int) (u * tw);
                int s1 = Math.min(tw, (int) (u * tw + (strip + 4) * tw / CHUNK_PX) + 1);
                if (s1 <= s0) s1 = s0 + 1;
                src.set(s0, 0, s1, th);
                lr.set(x - 2, cyw - half, x + strip + 2, cyw + half);
                c.drawBitmap(roadVar.get(vi), src, lr, sp);
            }
        }

        return b;
    }

    // ---------- turns ----------
    private void spawnEnemy() {
        for (int tries = 0; tries < 12; tries++) {
            float a = (float) (Math.random() * Math.PI * 2);
            float x = player.x + (float) Math.cos(a) * HEX * 7;
            float y = player.y + (float) Math.sin(a) * HEX * 7 * SQUASH * 2;
            worldToHex(x, y, IH_A);
            if (!hexFree(IH_A[0], IH_A[1], null)) continue;
            hexToWorld(IH_A[0], IH_A[1], FW_A);
            Enemy e = new Enemy();
            e.x = FW_A[0]; e.y = FW_A[1];
            enemies.add(e);
            return;
        }
    }

    private void startPlayerTurn() {
        phase = PH_PLAYER; phaseT = 0;
        moveLeft = 3;
        hasAttacked = false;
        attackRangeShown = 0;
        targetEnemy = null; strikeTarget = null; pendingBolt = null;
        hexesShown = false;
        ei = 0;
        if (enemies.size() < 5) spawnEnemy();
    }

    private void endPlayerTurn() {
        phase = PH_ENEMY; phaseT = 0; ei = 0;
        hexesShown = false; targetEnemy = null; attackRangeShown = 0;
        for (Enemy en : enemies) en.resetTurn();
    }

    private void resetFight() {
        playerHp = 100;
        enemies.clear();
        dmgs.clear();
        bolts.clear();
        for (int i = 0; i < 3; i++) spawnEnemy();
        startPlayerTurn();
    }

    private void planEnemy(Enemy en) {
        en.planned = true;
        worldToHex(player.x, player.y, IH_A);
        worldToHex(en.x, en.y, IH_B);
        if (hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= 1) return;
        float dx = player.x - en.x, dy = player.y - en.y;
        float d = (float) Math.hypot(dx, dy);
        float[] steps = { HEX * 3.2f, HEX * 1.6f };
        for (float st : steps) {
            worldToHex(en.x + dx / d * st, en.y + dy / d * st, IH_C);
            if (hexFree(IH_C[0], IH_C[1], en)) {
                hexToWorld(IH_C[0], IH_C[1], FW_A);
                en.tx = FW_A[0]; en.ty = FW_A[1];
                en.planMove = true;
                return;
            }
        }
    }

    private void addDmg(float x, float y, int val) {
        Dmg d = new Dmg(); d.x = x; d.y = y; d.val = val; dmgs.add(d);
    }

    // ---------- update ----------
    private void update(float dt) {
        if (deadT > 0) {
            deadT -= dt;
            if (deadT <= 0) resetFight();
            return;
        }
        phaseT += dt;
        if (hurtT > 0) hurtT -= dt;
        player.update(dt);

        if (exploring) {
            exploreT += dt;
            if (exploreT > 6 || player.isMoving()) exploring = false;
        }
        if (!exploring) {
            float k = 1 - (float) Math.exp(-dt * 8);
            camX += (player.x - camX) * k;
            camY += ((player.y - H * 0.28f) - camY) * k;
        }
        runeT += dt;

        int ccx = (int) Math.floor(camX / CHUNK_PX);
        int ccy = (int) Math.floor(camY / CHUNK_PX);
        if (ccx != lastCX || ccy != lastCY) {
            lastCX = ccx; lastCY = ccy;
            synchronized (chunkLock) {
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++) {
                        long key = ((long) (ccx + dx) << 32) | ((ccy + dy) & 0xFFFFFFFFL);
                        if (!chunks.containsKey(key)) chunkQueue.addLast(key);
                    }
                chunkLock.notify();
            }
        }

        for (Ember em : embers) {
            em.y -= em.s * dt;
            em.x += (float) Math.sin(em.y * 0.02f) * 12 * dt;
            if (em.y < -10) { em.y = H + 10; em.x = (float) (Math.random() * W); }
        }

        if (strikeTarget != null && player.isAttacking() && attackType == 1
                && !playerHitDone && player.attackTime > 0.4f) {
            playerHitDone = true;
            Enemy en = strikeTarget;
            if (!en.dead) {
                en.hp -= 12;
                en.hitFlash = 0.25f;
                addDmg(en.x, en.y - ENEMY_H - 20, 12);
                if (en.hp <= 0) en.dead = true;
            }
        }
        if (!player.isAttacking()) playerHitDone = false;

        if (pendingBolt != null && player.isAttacking() && attackType == 2
                && player.attackTime > 0.4f) {
            Bolt b = new Bolt();
            b.x0 = player.x + player.facing * 40;
            b.y0 = player.y - PLAYER_H * 0.75f;
            b.tx = pendingBolt.x;
            b.ty = pendingBolt.y - ENEMY_H * 0.5f;
            b.x = b.x0; b.y = b.y0; b.t = 0;
            b.tgt = pendingBolt;
            bolts.add(b);
            pendingBolt = null;
        }

        for (int i = bolts.size() - 1; i >= 0; i--) {
            Bolt b = bolts.get(i);
            b.t += dt;
            float kk = b.t / 0.28f;
            if (kk >= 1f) {
                if (!b.tgt.dead) {
                    Enemy en = b.tgt;
                    en.hp -= 12;
                    en.hitFlash = 0.25f;
                    addDmg(en.x, en.y - ENEMY_H - 20, 12);
                    if (en.hp <= 0) en.dead = true;
                }
                bolts.remove(i);
            } else {
                b.x = b.x0 + (b.tx - b.x0) * kk;
                b.y = b.y0 + (b.ty - b.y0) * kk - (float) Math.sin(kk * Math.PI) * 40;
            }
        }

        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy en = enemies.get(i);
            if (en.hitFlash > 0) en.hitFlash -= dt;
            if (en.dead) {
                en.deathT += dt;
                en.floater.moving = false;
                en.floater.update(dt);
                if (en.deathT > 0.7f) enemies.remove(i);
            }
        }

        if (phase == PH_PLAYER) {
            if (hasAttacked && moveLeft == 0 && !player.isMoving()
                    && !player.isAttacking() && player.floater.state == 0) {
                endPlayerTurn();
            }
        } else {
            if (ei < enemies.size()) {
                Enemy en = enemies.get(ei);
                if (en.dead) {
                    ei++;
                } else {
                    if (!en.planned) planEnemy(en);
                    worldToHex(player.x, player.y, IH_A);
                    worldToHex(en.x, en.y, IH_B);
                    boolean adj = hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) == 1;
                    en.turnUpdate(dt, player.x, player.y, adj);
                    if (en.attacking() && !en.struck && en.attackT > 0.45f) {
                        en.struck = true;
                        if (adj) {
                            playerHp -= 10;
                            hurtT = 0.3f;
                            addDmg(player.x, player.y - PLAYER_H - 20, -10);
                            if (playerHp <= 0) { playerHp = 0; deadT = 2f; }
                        }
                    }
                    if (en.act == 3) ei++;
                }
            } else {
                startPlayerTurn();
            }
        }

        if (player.floater.floating() && player.isMoving()) {
            puffTimer += dt;
            if (puffTimer > 0.09f) {
                puffTimer = 0;
                Puff p = new Puff();
                p.x = player.x + (float) (Math.random() * 36 - 18);
                p.y = player.y + (float) (Math.random() * 10 - 5);
                p.t = 0;
                puffs.add(p);
            }
        }
        for (int i = puffs.size() - 1; i >= 0; i--) {
            puffs.get(i).t += dt;
            if (puffs.get(i).t > 0.5f) puffs.remove(i);
        }
        for (int i = dmgs.size() - 1; i >= 0; i--) {
            dmgs.get(i).t += dt;
            if (dmgs.get(i).t > 0.8f) dmgs.remove(i);
        }
    }

    // ---------- draw ----------
    private void draw() {
        SurfaceHolder h = getHolder();
        if (!h.getSurface().isValid()) return;
        Canvas cv = h.lockCanvas();
        if (cv == null) return;

        W = cv.getWidth(); H = cv.getHeight();
        cv.drawColor(0xFF120a18);

        drawGround(cv);
        if (hexesShown) drawMoveFan(cv);
        if (attackRangeShown > 0) drawAttackRange(cv);
        if (targetEnemy != null && !targetEnemy.dead) {
            worldToHex(targetEnemy.x, targetEnemy.y, IH_A);
            hexToWorld(IH_A[0], IH_A[1], FW_A);
            drawHex(cv, sx(FW_A[0]), sy(FW_A[1]), 0xAAcc2233, true);
        }
        drawRune(cv);
        drawPuffs(cv);

        drawSorted(cv);

        drawBolts(cv);
        drawDmgs(cv);

        cv.drawRect(0, 0, W, H, hazePaint);
        cv.drawRect(0, 0, W, H, glowPaint);

        paint.setColor(0xFFff7a30);
        for (Ember em : embers) {
            paint.setAlpha((int) (40 + em.s));
            cv.drawCircle(em.x, em.y, 1.5f + em.s / 40f, paint);
        }
        paint.setAlpha(255);
        cv.drawRect(0, 0, W, H, vigPaint);

        drawUI(cv);

        if (hurtT > 0) {
            paint.setColor(0xFFff0000);
            paint.setAlpha((int) (hurtT / 0.3f * 100));
            cv.drawRect(0, 0, W, H, paint);
            paint.setAlpha(255);
        }
        if (phaseT < 1.2f) {
            int a = phaseT < 0.9f ? 220 : (int) ((1.2f - phaseT) / 0.3f * 220);
            paint.setAlpha(a);
            paint.setTextSize(64);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(phase == PH_PLAYER ? 0xFFff2bd6 : 0xFFff2233);
            cv.drawText(phase == PH_PLAYER ? "YOUR TURN" : "ENEMY TURN",
                    W / 2f, H * 0.3f, paint);
            paint.setAlpha(255);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        if (deadT > 0) {
            paint.setColor(0xFFff2233);
            paint.setTextSize(90);
            paint.setTextAlign(Paint.Align.CENTER);
            cv.drawText("YOU DIED", W / 2f, H / 2f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        h.unlockCanvasAndPost(cv);
    }

    private float sx(float wx) { return (wx - camX) * zoom + W / 2f; }
    private float sy(float wy) { return (wy - camY) * zoom + H / 2f; }

    private void drawGround(Canvas cv) {
        float halfW = W / (2f * zoom), halfH = H / (2f * zoom);
        int x0 = (int) Math.floor((camX - halfW) / CHUNK_PX);
        int x1 = (int) Math.floor((camX + halfW) / CHUNK_PX);
        int y0 = (int) Math.floor((camY - halfH) / CHUNK_PX);
        int y1 = (int) Math.floor((camY + halfH) / CHUNK_PX);
        float cs = CHUNK_PX * zoom;
        for (int cy = y0; cy <= y1; cy++) {
            for (int cx = x0; cx <= x1; cx++) {
                float x = sx(cx * CHUNK_PX), y = sy(cy * CHUNK_PX);
                rf.set(x, y, x + cs + 1, y + cs + 1);
                cv.drawBitmap(getChunk(cx, cy), null, rf, paint);
            }
        }
    }

    private D obtainD() {
        return dPool.isEmpty() ? new D() : dPool.remove(dPool.size() - 1);
    }

    private void drawSorted(Canvas cv) {
        for (int i = 0; i < drawList.size(); i++) dPool.add(drawList.get(i));
        drawList.clear();

        float halfW = W / (2f * zoom), halfH = H / (2f * zoom);
        int tx0 = (int) Math.floor((camX - halfW) / TILE) - 1;
        int tx1 = (int) Math.ceil ((camX + halfW) / TILE) + 1;
        int ty0 = (int) Math.floor((camY - halfH) / TILE) - 1;
        int ty1 = (int) Math.ceil ((camY + halfH) / TILE) + 1;
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if (!isLargeCell(tx, ty) || props2.isEmpty()) continue;
                largeAnchor(tx, ty, FW_A);
                D d = obtainD();
                d.kind = 0;
                d.ax = FW_A[0]; d.ay = FW_A[1];
                d.y = FW_A[1];
                d.pr = props2.get(((tx * 12347 ^ ty * 98765) >>> 4) % props2.size());
                drawList.add(d);
            }
        }
        D p = obtainD(); p.kind = 1; p.y = player.y; drawList.add(p);
        for (Enemy en : enemies) { D d = obtainD(); d.kind = 2; d.en = en; d.y = en.y; drawList.add(d); }

        Collections.sort(drawList, BY_Y);
        for (D d : drawList) {
            if (d.kind == 0) drawLargeProp(cv, d);
            else if (d.kind == 1) drawPlayer(cv);
            else drawEnemy(cv, d.en);
        }
    }

    private void drawLargeProp(Canvas cv, D d) {
        float s = (TILE * 1.5f * zoom) / d.pr.getHeight();
        rf.set(sx(d.ax) - d.pr.getWidth() * s / 2f, sy(d.ay) - d.pr.getHeight() * s,
               sx(d.ax) + d.pr.getWidth() * s / 2f, sy(d.ay));
        cv.drawBitmap(d.pr, null, rf, propPaint);
    }

    private void drawHex(Canvas cv, float cx, float cy, int color, boolean filled) {
        float hr = HEX * 0.92f * zoom;
        hexPath.reset();
        for (int i = 0; i < 6; i++) {
            float a = (float) Math.toRadians(60 * i - 30);
            float x = cx + hr * (float) Math.cos(a);
            float y = cy + hr * SQUASH * (float) Math.sin(a);
            if (i == 0) hexPath.moveTo(x, y); else hexPath.lineTo(x, y);
        }
        hexPath.close();
        if (filled) {
            paint.setColor(color);
            cv.drawPath(hexPath, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setColor(0x66ffffff);
            cv.drawPath(hexPath, paint);
            paint.setStyle(Paint.Style.FILL);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            paint.setColor(color);
            cv.drawPath(hexPath, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawMoveFan(Canvas cv) {
        worldToHex(player.x, player.y, IH_A);
        for (int r = -MOVE_HEX; r <= MOVE_HEX; r++) {
            for (int q = -MOVE_HEX; q <= MOVE_HEX; q++) {
                int d = hexDist(IH_A[0], IH_A[1], IH_A[0] + q, IH_A[1] + r);
                if (d < 1 || d > MOVE_HEX) continue;
                hexToWorld(IH_A[0] + q, IH_A[1] + r, FW_A);
                boolean ok = d <= moveLeft && hexFree(IH_A[0] + q, IH_A[1] + r, null);
                if (ok) drawHex(cv, sx(FW_A[0]), sy(FW_A[1]), 0x7722cc44, true);
                else    drawHex(cv, sx(FW_A[0]), sy(FW_A[1]), 0xCCcc2233, false);
            }
        }
    }

    private void drawAttackRange(Canvas cv) {
        int range = (attackRangeShown == 1) ? 1 : 3;
        worldToHex(player.x, player.y, IH_A);
        for (int r = -range; r <= range; r++) {
            for (int q = -range; q <= range; q++) {
                int d = hexDist(IH_A[0], IH_A[1], IH_A[0] + q, IH_A[1] + r);
                if (d < 1 || d > range) continue;
                hexToWorld(IH_A[0] + q, IH_A[1] + r, FW_A);
                drawHex(cv, sx(FW_A[0]), sy(FW_A[1]), 0xCCcc2233, false);
            }
        }
        for (Enemy en : enemies) {
            if (en.dead) continue;
            worldToHex(en.x, en.y, IH_B);
            if (hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= range) {
                hexToWorld(IH_B[0], IH_B[1], FW_A);
                drawHex(cv, sx(FW_A[0]), sy(FW_A[1]), 0x66ff2233, true);
            }
        }
    }

    private void drawRune(Canvas cv) {
        if (runeT > 0.6f) return;
        float k = runeT / 0.6f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        paint.setColor(0xFFff2bd6);
        paint.setAlpha((int) (255 * (1 - k)));
        float r = (20 + k * 50) * zoom;
        rf.set(sx(runeX) - r, sy(runeY) - r / 2f, sx(runeX) + r, sy(runeY) + r / 2f);
        cv.drawOval(rf, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
    }

    private void drawPuffs(Canvas cv) {
        for (Puff p : puffs) {
            float k = p.t / 0.5f;
            paint.setColor(0xFF6a2f8f);
            paint.setAlpha((int) (110 * (1 - k)));
            cv.drawCircle(sx(p.x), sy(p.y) - k * 26 * zoom, (12 + k * 46) * zoom, paint);
        }
        paint.setAlpha(255);
    }

    private void drawPlayer(Canvas cv) {
        boolean fl = player.floater.floating();
        float sw = (fl ? 45 : 55) * zoom;
        paint.setAlpha(fl ? 150 : 220);
        rf.set(sx(player.x) - sw, sy(player.y) - sw * 0.36f,
               sx(player.x) + sw, sy(player.y) + sw * 0.36f);
        cv.drawBitmap(shadowBmp, null, rf, paint);
        paint.setAlpha(255);

        Bitmap frame = pickFrame();
        cv.save();
        cv.translate(sx(player.x), sy(player.y));
        if (player.facing < 0) cv.scale(-1, 1);
        if (frame != null) {
            float s = PLAYER_H * zoom / frame.getHeight();
            rf.set(-frame.getWidth() * s / 2f, -frame.getHeight() * s,
                    frame.getWidth() * s / 2f, 0);
            cv.drawBitmap(frame, null, rf, paint);
        }
        cv.restore();

        float top = sy(player.y) - PLAYER_H * zoom - 26;
        paint.setColor(0xFF330000);
        cv.drawRect(sx(player.x) - 45, top, sx(player.x) + 45, top + 12, paint);
        paint.setColor(0xFFff2bd6);
        cv.drawRect(sx(player.x) - 45, top,
                sx(player.x) - 45 + 90f * playerHp / 100, top + 12, paint);
    }

    private Bitmap pickFrame() {
        if (player.isAttacking() && !attack.isEmpty()) {
            float prog = player.attackTime / player.attackDuration;
            if (attackType == 2 && attack.size() >= 8) {
                int[] seq = { 0, 1, 2, 3, 6, 7 };
                int i = (int) (prog * seq.length);
                if (i >= seq.length) i = seq.length - 1;
                return attack.get(seq[i]);
            }
            int i = (int) (prog * attack.size());
            if (i >= attack.size()) i = attack.size() - 1;
            return attack.get(i);
        }
        if (player.floater.state == 0 && !idleF.isEmpty()) {
            return idleF.get(((int) (player.bobTime * 2)) % idleF.size());
        }
        if (!glide.isEmpty()) {
            return glide.get(player.floater.frame(player.bobTime));
        }
        return null;
    }

    private Bitmap pickEnemyFrame(Enemy en) {
        if (en.attacking() && !eAttack.isEmpty()) {
            int i = (int) (en.attackT / 0.9f * eAttack.size());
            if (i >= eAttack.size()) i = eAttack.size() - 1;
            return eAttack.get(i);
        }
        if (!eGlide.isEmpty()) {
            return eGlide.get(en.floater.frame(en.animT));
        }
        return null;
    }

    private void drawEnemy(Canvas cv, Enemy en) {
        float x = sx(en.x), y = sy(en.y);
        boolean fl = en.floater.floating();
        float sw = (fl ? 35 : 45) * zoom;
        paint.setAlpha(fl ? 150 : 220);
        rf.set(x - sw, y - sw * 0.36f, x + sw, y + sw * 0.36f);
        cv.drawBitmap(shadowBmp, null, rf, paint);
        paint.setAlpha(255);

        Bitmap frame = pickEnemyFrame(en);
        Paint p = (en.hitFlash > 0) ? tintPaint : paint;
        cv.save();
        cv.translate(x, y);
        if (en.facing < 0) cv.scale(-1, 1);
        if (en.dead) p.setAlpha((int) (255 * (1 - en.deathT / 0.7f)));
        if (frame != null) {
            float s = ENEMY_H * zoom / frame.getHeight();
            rf.set(-frame.getWidth() * s / 2f, -frame.getHeight() * s,
                    frame.getWidth() * s / 2f, 0);
            cv.drawBitmap(frame, null, rf, p);
        } else {
            p.setColor(0xFFaa2233);
            rf.set(-30 * zoom, -ENEMY_H * 0.8f * zoom, 30 * zoom, 0);
            cv.drawOval(rf, p);
        }
        p.setAlpha(255);
        cv.restore();

        if (!en.dead && en.hp < en.maxHp) {
            float top = y - ENEMY_H * zoom - 26;
            paint.setColor(0xFF330000);
            cv.drawRect(x - 45, top, x + 45, top + 12, paint);
            paint.setColor(0xFFff3344);
            cv.drawRect(x - 45, top, x - 45 + 90f * en.hp / en.maxHp, top + 12, paint);
        }
    }

    private void drawBolts(Canvas cv) {
        for (Bolt b : bolts) {
            float dx = b.tx - b.x0, dy = b.ty - b.y0;
            float d = (float) Math.hypot(dx, dy);
            if (d < 1) d = 1;
            for (int t = 3; t >= 1; t--) {
                paint.setColor(0xFFff3344);
                paint.setAlpha(60 - t * 15);
                cv.drawCircle(sx(b.x - dx / d * t * 22), sy(b.y - dy / d * t * 22),
                        (12 - t * 2) * zoom, paint);
            }
            paint.setAlpha(220);
            paint.setColor(0xFFff3344);
            cv.drawCircle(sx(b.x), sy(b.y), 13 * zoom, paint);
            paint.setColor(0xFFffffff);
            cv.drawCircle(sx(b.x), sy(b.y), 6 * zoom, paint);
            paint.setAlpha(255);
        }
    }

    private void drawDmgs(Canvas cv) {
        paint.setTextSize(34);
        paint.setTextAlign(Paint.Align.CENTER);
        for (Dmg d : dmgs) {
            float k = d.t / 0.8f;
            paint.setAlpha((int) (255 * (1 - k)));
            paint.setColor(d.val < 0 ? 0xFFff2233 : 0xFFffffff);
            cv.drawText(String.valueOf(d.val), sx(d.x), sy(d.y) - k * 80, paint);
        }
        paint.setAlpha(255);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawUI(Canvas cv) {
        paint.setColor(0xFF330000);
        cv.drawRect(W / 2f - 160, 26, W / 2f + 160, 44, paint);
        paint.setColor(0xFFff3344);
        cv.drawRect(W / 2f - 160, 26, W / 2f - 160 + 320f * playerHp / 100, 44, paint);

        paint.setColor(0x66ffffff);
        cv.drawCircle(W - 64, 64, 40, paint);
        cv.drawCircle(W - 164, 64, 40, paint);
        paint.setColor(0xFFffffff);
        paint.setTextSize(44);
        paint.setTextAlign(Paint.Align.CENTER);
        cv.drawText("+", W - 64, 80, paint);
        cv.drawText("-", W - 164, 80, paint);

        paint.setColor(0x663355ff);
        cv.drawCircle(110, H - 110, 70, paint);
        paint.setColor(0xFFffffff);
        paint.setTextSize(34);
        cv.drawText("END", 110, H - 98, paint);

        paint.setColor(0x66ff3355);
        cv.drawCircle(W - 110, H - 110, 70, paint);
        paint.setColor(0xFFffffff);
        cv.drawText("A1", W - 110, H - 98, paint);

        paint.setColor(0x66ff8833);
        cv.drawCircle(W - 260, H - 110, 70, paint);
        paint.setColor(0xFFffffff);
        cv.drawText("A2", W - 260, H - 98, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(28);
        cv.drawText("D O W N", 24, 48, paint);
        paint.setTextSize(22);
        paint.setColor(0x88ffffff);
        cv.drawText("her hex: fan - A1/A2: range - foe hex x2: hit - drag: look", 24, 80, paint);
    }

    private boolean uiZone(float x, float y) {
        if (x < 190 && y > H - 190) return true;
        if (x > W - 340 && y > H - 190) return true;
        if (Math.hypot(x - (W - 64), y - 64) < 50) return true;
        if (Math.hypot(x - (W - 164), y - 64) < 50) return true;
        return false;
    }

    // ---------- input ----------
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (deadT > 0 || phase != PH_PLAYER) return true;
        int act = e.getActionMasked();

        if (act == MotionEvent.ACTION_DOWN) {
            downX = e.getX(); downY = e.getY();
            moved = false; panning = false;
            return true;
        }
        if (act == MotionEvent.ACTION_MOVE) {
            if (downX < -9000) return true;
            float x = e.getX(), y = e.getY();
            if (!moved && Math.hypot(x - downX, y - downY) > 26) {
                moved = true;
                panning = !uiZone(downX, downY);
                lastPX = downX; lastPY = downY;
            }
            if (moved && panning) {
                camX -= (x - lastPX) / zoom;
                camY -= (y - lastPY) / zoom;
                exploring = true;
                exploreT = 0;
                lastPX = x; lastPY = y;
            }
            return true;
        }
        if (act != MotionEvent.ACTION_UP) return true;
        float x = e.getX(), y = e.getY();
        downX = -9999;
        if (moved) return true;

        if (Math.hypot(x - (W - 64), y - 64) < 46) {
            zoom = Math.min(ZOOM_MAX, zoom + 0.15f);
            return true;
        }
        if (Math.hypot(x - (W - 164), y - 64) < 46) {
            zoom = Math.max(ZOOM_MIN, zoom - 0.15f);
            return true;
        }

        if (x < 190 && y > H - 190) {
            endPlayerTurn();
            return true;
        }
        if (x > W - 190 && y > H - 190) {
            if (!hasAttacked) {
                attackRangeShown = (attackRangeShown == 1) ? 0 : 1;
                hexesShown = false; targetEnemy = null;
            }
            return true;
        }
        if (x > W - 340 && x < W - 190 && y > H - 190) {
            if (!hasAttacked) {
                attackRangeShown = (attackRangeShown == 2) ? 0 : 2;
                hexesShown = false; targetEnemy = null;
            }
            return true;
        }

        float wx = camX + (x - W / 2f) / zoom;
        float wy = camY + (y - H / 2f) / zoom;
        worldToHex(wx, wy, TW_A);
        worldToHex(player.x, player.y, TW_B);
        int dTap = hexDist(TW_B[0], TW_B[1], TW_A[0], TW_A[1]);

        Enemy tapped = null;
        for (Enemy en : enemies) {
            if (en.dead) continue;
            worldToHex(en.x, en.y, TW_C);
            if (TW_C[0] == TW_A[0] && TW_C[1] == TW_A[1]) { tapped = en; break; }
        }

        if (tapped != null) {
            int range = (attackRangeShown == 1) ? 1 : (attackRangeShown == 2) ? 3 : 0;
            if (!hasAttacked && range > 0 && dTap <= range) {
                if (targetEnemy != tapped) {
                    targetEnemy = tapped;
                } else {
                    hasAttacked = true;
                    attackType = attackRangeShown;
                    player.facing = tapped.x >= player.x ? 1 : -1;
                    player.startAttack();
                    if (attackType == 1) strikeTarget = tapped;
                    else pendingBolt = tapped;
                    targetEnemy = null;
                    attackRangeShown = 0;
                    hexesShown = false;
                }
            }
            return true;
        }

        if (dTap == 0) {
            hexesShown = !hexesShown;
            attackRangeShown = 0;
            return true;
        }

        if (hexesShown && moveLeft > 0 && dTap >= 1 && dTap <= moveLeft
                && hexFree(TW_A[0], TW_A[1], null)) {
            hexToWorld(TW_A[0], TW_A[1], TW_F);
            player.setTarget(TW_F[0], TW_F[1]);
            moveLeft -= dTap;
            hexesShown = false;
            runeX = TW_F[0]; runeY = TW_F[1]; runeT = 0;
        }
        return true;
    }
}
