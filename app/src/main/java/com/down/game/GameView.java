package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class GameView extends SurfaceView implements Runnable {

    private static final int STATE_MENU = 0, STATE_GAME = 1, STATE_STORY = 2;
    private static final int PH_PLAYER = 0, PH_ENEMY = 1;

    private static final float SQUASH = 0.6f;
    private static final float HEX = 96f;
    private static final float TILE = 192f;
    private static final float TH = TILE * SQUASH;
    private static final int MOVE_HEX = 3;
    private static final float PLAYER_H = 200f, ENEMY_H = 200f;
    private static final float FOOT_DROP = 40f;
    private static final long FRAME_NS = 16666667L;
    private static final float ZOOM_MIN = 0.9f, ZOOM_MAX = 2.0f;
    private static final int GROUND_COL = 0xFF140B16;

    private static final int[][] ATK_SEQ = {
            { 0, 1, 2, 3, 4, 9, 5 },
            { 0, 1, 2, 9, 5 },
            { 0, 1, 2, 8, 3, 4, 10, 11, 5 } };
    private static final float[] ATK_DUR = { 0.95f, 0.75f, 1.25f };
    private static final float[] ATK_HIT = { 0.60f, 0.55f, 0.80f };
    private static final int[] ATK_RANGE = { 1, 3, 2 };
    private static final int[] ATK_DMG = { 15, 10, 20 };
    private static final int[] ATK_MANA = { 0, 20, 50 };
    private static final int[][] NEIGH6 = {
            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, -1 }, { -1, 1 } };

    private Thread loop;
    private volatile boolean running;

    private int state = STATE_MENU;
    private Story story;
    private boolean camSnap;
    private int menuPress;
    private final RectF menuBtnTest = new RectF();
    private final RectF menuBtnStory = new RectF();

    private final Player player = new Player();
    private float camX, camY;
    private float zoom = 1.25f;
    private boolean exploring;
    private float exploreT;
    private float downX = -9999, downY, lastPX, lastPY;
    private boolean moved, panning;
    private boolean pinching;
    private float pinchDist0, pinchZoom0;
    private float velX, velY, flingX, flingY;
    private long lastMoveT;

    private final ArrayList<Frame> idleFr = new ArrayList<>();
    private final ArrayList<Frame> glideFr = new ArrayList<>();
    private final ArrayList<Frame> atkFr = new ArrayList<>();
    private Frame frameA, frameB;
    private float frameK;

    private final ArrayList<Frame> eIdleFr = new ArrayList<>();
    private final ArrayList<Frame> eGlideFr = new ArrayList<>();
    private final ArrayList<Frame> eGlowFr = new ArrayList<>();
    private final ArrayList<Frame> eAtkFr = new ArrayList<>();
    private List<Bitmap> props, props2;

    private float runeX, runeY, runeT = 99;

    private static class Particle { float x, y, vx, vy, t, life; int col; boolean active; }
    private final Particle[] particlePool = new Particle[200];
    private static class Puff { float x, y, t; boolean active; }
    private final Puff[] puffPool = new Puff[50];
    private float puffTimer;

    private static class Blast { float x, y, t; boolean active; }
    private final Blast[] blastPool = new Blast[20];

    private Bitmap overlay, menuBmp, shadowBmp;
    private static class Ember { float x, y, s; }
    private final ArrayList<Ember> embers = new ArrayList<>();

    private boolean hexesShown = false;
    private int moveLeft = 3;
    private int attackRangeShown = 0;
    private int attackType = 1;
    private int mana = 100;
    private Enemy targetEnemy = null;
    private Enemy strikeTarget = null;
    private Enemy pendingBolt = null;
    private static class Bolt { float x, y, x0, y0, tx, ty, t; Enemy tgt; boolean active; }
    private final Bolt[] boltPool = new Bolt[20];

    private int phase = PH_PLAYER;
    private float phaseT = 0;
    private int ei = 0;
    private boolean hasAttacked = false;

    private final ArrayList<Enemy> enemies = new ArrayList<>();
    private final HashMap<Long, Integer> flow = new HashMap<>();
    private final HashSet<Long> reserved = new HashSet<>();
    private int playerHp = 100;
    private float hurtT = 0, deadT = 0;
    private boolean playerHitDone = false;
    private static class Dmg { float x, y, t; int val; boolean active; }
    private final Dmg[] dmgPool = new Dmg[30];

    private static class D { float y; int kind; Enemy en; Bitmap pr; float ax, ay, s; }
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
    private static final int[] HO_A = new int[2];

    private final Paint paint = new Paint();
    private final Paint tintPaint = new Paint();
    private final Path hexPath = new Path();
    private final RectF rf = new RectF();
    private final Rect frameSrc = new Rect();
    private final ColorMatrixColorFilter brightFlash = new ColorMatrixColorFilter(new float[] {
            1.16f, 0, 0, 0, 90,
            0, 0.6f, 0, 0, 0,
            0, 0, 0.6f, 0, 0,
            0, 0, 0, 1, 0 });
    private PorterDuffColorFilter hexFilter;
    private PorterDuffColorFilter blastFilter;
    private int W, H;

    private float hitstopT = 0;
    private float shakeT = 0, shakeX = 0, shakeY = 0;
    private Bitmap hexBmp, blastBmp;

    public GameView(Context ctx) {
        super(ctx);
        idleFr.addAll(Sprites.buildFrames(Sprites.cutSheet(ctx, "sprites/idle.png", 2, 2, 4), false, true));
        glideFr.addAll(Sprites.buildFrames(Sprites.cutSheet(ctx, "sprites/glide.png", 2, 2, 2), true, false));
        atkFr.addAll(Sprites.buildFrames(Sprites.cutSheet(ctx, "sprites/attack_a.png", 2, 3, 2), true, false));
        atkFr.addAll(Sprites.buildFrames(Sprites.cutSheet(ctx, "sprites/attack_b.png", 2, 3, 2), true, false));
        if (idleFr.size() >= 4) {
            idleFr.get(1).dx = -8f;
            idleFr.get(2).dx = -8f;
        }
        eIdleFr.addAll(Sprites.buildFrames(Sprites.cutSheet(ctx, "sprites/enemy_idle.png", 2, 2, 4), false, true));
        eGlideFr.addAll(Sprites.buildFrames(Sprites.cutSheet(ctx, "sprites/enemy_glide.png", 2, 2, 2), true, false));
        eGlowFr.addAll(Sprites.buildFrames(Sprites.cutSheet(ctx, "sprites/enemy_glow.png", 2, 2, 2), true, false));
        eAtkFr.addAll(Sprites.buildFrames(Sprites.cutSheet(ctx, "sprites/enemy_attack.png", 2, 3, 2), false, false));
        props   = Sprites.trimBottom(Sprites.cutSheet(ctx, "sprites/props.png",  2, 4, 4), 0.9f);
        props2  = Sprites.trimBottom(Sprites.cutSheet(ctx, "sprites/props2.png", 2, 4, 4), 0.9f);

        paint.setFilterBitmap(true);
        tintPaint.setFilterBitmap(true);
        tintPaint.setColorFilter(brightFlash);

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

        for (int i = 0; i < particlePool.length; i++) particlePool[i] = new Particle();
        for (int i = 0; i < puffPool.length; i++) puffPool[i] = new Puff();
        for (int i = 0; i < blastPool.length; i++) blastPool[i] = new Blast();
        for (int i = 0; i < boltPool.length; i++) boltPool[i] = new Bolt();
        for (int i = 0; i < dmgPool.length; i++) dmgPool[i] = new Dmg();

        hexBmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas hc = new Canvas(hexBmp);
        Paint hp = new Paint(); hp.setColor(0xFFFFFFFF); hp.setAntiAlias(true);
        Path hPath = new Path();
        for (int i = 0; i < 6; i++) {
            float a = (float) Math.toRadians(60 * i - 30);
            float x = 64 + 60 * (float) Math.cos(a);
            float y = 64 + 60 * SQUASH * (float) Math.sin(a);
            if (i == 0) hPath.moveTo(x, y); else hPath.lineTo(x, y);
        }
        hPath.close(); hc.drawPath(hPath, hp);

        blastBmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(blastBmp);
        Paint bp = new Paint(); bp.setColor(0xFFFFFFFF); bp.setAntiAlias(true);
        bc.drawCircle(64, 64, 60, bp);
    }

    public void start() {
        if (running) return;
        running = true;
        loop = new Thread(this);
        loop.start();
    }

    public void stop() {
        running = false;
        try { if (loop != null) loop.join(); } catch (Exception e) {}
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w; H = h;
        if (W <= 0 || H <= 0) return;

        int w2 = Math.max(2, W / 2), h2 = Math.max(2, H / 2);
        Paint p = new Paint();

        overlay = Bitmap.createBitmap(w2, h2, Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(overlay);
        p.setShader(new LinearGradient(0, 0, 0, h2,
                new int[] { 0x26140a1c, 0x00000000, 0x00000000, 0x1C10061a },
                new float[] { 0f, 0.22f, 0.72f, 1f }, Shader.TileMode.CLAMP));
        oc.drawRect(0, 0, w2, h2, p);
        p.setShader(new RadialGradient(w2 * 0.18f, -h2 * 0.25f, h2 * 1.15f,
                0x20ffffff, 0x00000000, Shader.TileMode.CLAMP));
        oc.drawRect(0, 0, w2, h2, p);
        p.setShader(new RadialGradient(w2 / 2f, h2 / 2f, Math.max(w2, h2) * 0.75f,
                0x00000000, 0x78000000, Shader.TileMode.CLAMP));
        oc.drawRect(0, 0, w2, h2, p);

        menuBmp = Bitmap.createBitmap(w2, h2, Bitmap.Config.ARGB_8888);
        Canvas mc = new Canvas(menuBmp);
        mc.drawColor(0xFF0d0714);
        p.setShader(new RadialGradient(w2 / 2f, h2 * 0.32f, Math.max(w2, h2) * 0.62f,
                0x34ff2bd6, 0x00000000, Shader.TileMode.CLAMP));
        mc.drawRect(0, 0, w2, h2, p);
        p.setShader(new RadialGradient(w2 / 2f, h2 * 1.2f, Math.max(w2, h2) * 0.85f,
                0x2a4a1a66, 0x00000000, Shader.TileMode.CLAMP));
        mc.drawRect(0, 0, w2, h2, p);
        p.setShader(new RadialGradient(w2 / 2f, h2 / 2f, Math.max(w2, h2) * 0.72f,
                0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
        mc.drawRect(0, 0, w2, h2, p);

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

    private static long hexKey(int q, int r) {
        return ((long) q << 32) | (r & 0xFFFFFFFFL);
    }

    private static float roadCenterF(float tx) {
        return 2.2f * (float) Math.sin(tx * 0.12f) + 1.5f * (float) Math.sin(tx * 0.05f + 1.7f);
    }

    private boolean hexBlocked(int q, int r) {
        hexToWorld(q, r, HO_F);
        int tx0 = (int) Math.floor(HO_F[0] / TILE), ty0 = (int) Math.floor(HO_F[1] / TH);
        for (int ty = ty0 - 1; ty <= ty0 + 1; ty++) {
            for (int tx = tx0 - 1; tx <= tx0 + 1; tx++) {
                int h = (tx * 40503) ^ (ty * 66827);
                int roll = (h >>> 3) % 100;
                boolean large = roll < 8 && !props2.isEmpty();
                boolean small = !large && roll < 22 && !props.isEmpty();
                if (!large && !small) continue;
                Bitmap pr = large ? props2.get((h >>> 5) % props2.size())
                                  : props.get((h >>> 5) % props.size());
                float var = large ? (0.85f + ((h >>> 13) & 31) / 31f * 0.45f)
                                  : (0.8f + ((h >>> 15) & 31) / 31f * 0.5f);
                float s = ((large ? TH * 2.43f : TH * 0.45f) / pr.getHeight()) * var;
                float ax = large ? tx * TILE + TILE * (0.3f + ((h >>> 9) & 127) / 127f * 0.4f)
                                 : tx * TILE + TILE * (0.25f + ((h >>> 9) & 127) / 127f * 0.5f);
                float ay = large ? (ty + 1) * TH - TH * 0.10f
                                 : (ty + 1) * TH - TH * (0.15f + ((h >>> 11) & 31) / 31f * 0.25f);
                if (large) {
                    float bw = Math.min(pr.getWidth() * s * 0.35f, HEX * 0.9f);
                    float fh = TH * 0.8f;
                    if (HO_F[0] >= ax - bw && HO_F[0] <= ax + bw
                            && HO_F[1] >= ay - fh && HO_F[1] <= ay + TH * 0.25f) {
                        return true;
                    }
                } else {
                    worldToHex(ax, ay, HO_A);
                    if (HO_A[0] == q && HO_A[1] == r) return true;
                }
            }
        }
        return false;
    }

    private boolean hexOccupied(int q, int r, Enemy self) {
        worldToHex(player.x, player.y, HO_A);
        if (HO_A[0] == q && HO_A[1] == r) return true;
        for (Enemy en : enemies) {
            if (en.dead || en == self) continue;
            worldToHex(en.x, en.y, IH_C);
            if (IH_C[0] == q && IH_C[1] == r) return true;
        }
        return false;
    }

    private boolean hexFree(int q, int r, Enemy self) {
        return !hexOccupied(q, r, self) && !hexBlocked(q, r);
    }

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
        mana = Math.min(100, mana + 25);
        if (enemies.size() < 5) spawnEnemy();
    }

    private void endPlayerTurn() {
        phase = PH_ENEMY; phaseT = 0; ei = 0;
        hexesShown = false; targetEnemy = null; attackRangeShown = 0;
        for (Enemy en : enemies) en.resetTurn();
        reserved.clear();
        buildFlow();
    }

    private void buildFlow() {
        flow.clear();
        worldToHex(player.x, player.y, IH_A);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.addLast(new int[] { IH_A[0], IH_A[1] });
        flow.put(hexKey(IH_A[0], IH_A[1]), 0);
        while (!q.isEmpty()) {
            int[] c = q.pollFirst();
            int d = flow.get(hexKey(c[0], c[1]));
            if (d >= 30) continue;
            for (int[] n : NEIGH6) {
                int nq = c[0] + n[0], nr = c[1] + n[1];
                long k = hexKey(nq, nr);
                if (flow.containsKey(k) || hexBlocked(nq, nr)) continue;
                flow.put(k, d + 1);
                q.addLast(new int[] { nq, nr });
            }
        }
    }

    private void resetFight() {
        playerHp = 100;
        mana = 100;
        enemies.clear();
        for (Dmg d : dmgPool) d.active = false;
        for (Bolt b : boltPool) b.active = false;
        for (Blast b : blastPool) b.active = false;
        for (Puff p : puffPool) p.active = false;
        for (Particle p : particlePool) p.active = false;
        for (int i = 0; i < 3; i++) spawnEnemy();
        startPlayerTurn();
    }

    private void planEnemy(Enemy en) {
        en.planned = true;
        worldToHex(player.x, player.y, IH_A);
        worldToHex(en.x, en.y, IH_B);
        if (hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= 1) return;
        Integer cur = flow.get(hexKey(IH_B[0], IH_B[1]));
        if (cur == null) {
            float dx = player.x - en.x, dy = player.y - en.y;
            float d = (float) Math.hypot(dx, dy);
            if (d > 1) {
                float[] steps = { HEX * 3.2f, HEX * 1.6f };
                for (float st : steps) {
                    worldToHex(en.x + dx / d * st, en.y + dy / d * st, IH_C);
                    long k = hexKey(IH_C[0], IH_C[1]);
                    if (hexFree(IH_C[0], IH_C[1], en) && !reserved.contains(k)) {
                        hexToWorld(IH_C[0], IH_C[1], FW_A);
                        en.tx = FW_A[0]; en.ty = FW_A[1];
                        en.planMove = true;
                        reserved.add(k);
                        return;
                    }
                }
            }
            return;
        }
        int bd = cur, bq = IH_B[0], br = IH_B[1];
        for (int[] n : NEIGH6) {
            int nq = IH_B[0] + n[0], nr = IH_B[1] + n[1];
            Integer nd = flow.get(hexKey(nq, nr));
            if (nd == null || nd >= bd) continue;
            if (reserved.contains(hexKey(nq, nr))) continue;
            if (!hexFree(nq, nr, en)) continue;
            bd = nd; bq = nq; br = nr;
        }
        if (bq != IH_B[0] || br != IH_B[1]) {
            hexToWorld(bq, br, FW_A);
            en.tx = FW_A[0]; en.ty = FW_A[1];
            en.planMove = true;
            reserved.add(hexKey(bq, br));
        }
    }

    private void addDmg(float x, float y, int val) {
        for (Dmg d : dmgPool) {
            if (!d.active) {
                d.x = x; d.y = y; d.val = val; d.t = 0; d.active = true;
                return;
            }
        }
    }

    private void spawnPuff(float x, float y) {
        for (Puff p : puffPool) {
            if (!p.active) {
                p.x = x; p.y = y; p.t = 0; p.active = true;
                return;
            }
        }
    }

    private void spawnBlast(float x, float y) {
        for (Blast b : blastPool) {
            if (!b.active) {
                b.x = x; b.y = y; b.t = 0; b.active = true;
                return;
            }
        }
    }

    private void spawnBolt(float x0, float y0, float tx, float ty, Enemy tgt) {
        for (Bolt b : boltPool) {
            if (!b.active) {
                b.x0 = x0; b.y0 = y0; b.tx = tx; b.ty = ty;
                b.x = x0; b.y = y0; b.t = 0; b.tgt = tgt; b.active = true;
                return;
            }
        }
    }

    private void spawnDeathParticles(float x, float y) {
        for (int i = 0; i < 15; i++) {
            for (Particle p : particlePool) {
                if (!p.active) {
                    p.x = x; p.y = y;
                    p.vx = (float)(Math.random() * 100 - 50);
                    p.vy = (float)(Math.random() * -150 - 50);
                    p.life = 0.5f + (float)Math.random() * 0.5f;
                    p.t = 0;
                    p.col = (Math.random() > 0.5) ? 0xFF8A0303 : 0xFF050508;
                    p.active = true;
                    break;
                }
            }
        }
    }

    private void hurtEnemy(Enemy en, int dmg) {
        if (en.dead) return;
        en.hp -= dmg;
        en.hitFlash = 0.25f;
        addDmg(en.x, en.y - ENEMY_H - 20, dmg);
        if (en.hp <= 0) en.dead = true;
    }

    private void startGame() {
        state = STATE_GAME;
        camSnap = false;
    }

    private void startStory() {
        story = new Story(getContext());
        story.load("section1");
        state = STATE_STORY;
    }

    private void updateEmbers(float dt) {
        for (Ember em : embers) {
            em.y -= em.s * dt;
            em.x += (float) Math.sin(em.y * 0.02f) * 12 * dt;
            if (em.y < -10) { em.y = H + 10; em.x = (float) (Math.random() * W); }
        }
    }

    private void update(float dt) {
        updateEmbers(dt);
        if (state == STATE_MENU) return;
        if (state == STATE_STORY) {
            story.update(dt);
            if (story.quitRequested) { story = null; state = STATE_MENU; }
            return;
        }

        if (shakeT > 0) {
            shakeT -= dt;
            float mag = shakeT * 40f;
            shakeX = (float)(Math.random() * mag * 2 - mag);
            shakeY = (float)(Math.random() * mag * 2 - mag);
        } else { shakeX = 0; shakeY = 0; }

        if (hitstopT > 0) {
            hitstopT -= dt;
            for (Puff p : puffPool) if (p.active) p.t += dt;
            for (Dmg d : dmgPool) if (d.active) d.t += dt;
            return;
        }

        if (deadT > 0) {
            deadT -= dt;
            if (deadT <= 0) resetFight();
            return;
        }

        if (!camSnap && H > 0) {
            camX = player.x;
            camY = player.y - (H * 0.28f) / zoom;
            camSnap = true;
        }

        phaseT += dt;
        if (hurtT > 0) hurtT -= dt;
        player.update(dt);

        if (!panning && !player.isMoving() && (flingX != 0 || flingY != 0)) {
            camX += flingX * dt;
            camY += flingY * dt;
            float dk = (float) Math.exp(-dt * 3f);
            flingX *= dk; flingY *= dk;
            if (flingX * flingX + flingY * flingY < 400) { flingX = 0; flingY = 0; }
            exploring = true;
            exploreT = 0;
        }
        if (exploring) {
            exploreT += dt;
            if (exploreT > 6 || player.isMoving()) exploring = false;
        }
        float fx = player.x, fy = player.y;
        if (phase == PH_ENEMY && ei < enemies.size()) {
            Enemy ae = enemies.get(ei);
            if (!ae.dead) {
                worldToHex(player.x, player.y, IH_A);
                worldToHex(ae.x, ae.y, IH_B);
                if (hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= 8
                        && (ae.planMove || ae.attacking())) {
                    fx = ae.x;
                    fy = ae.y;
                }
            }
        }
        if (!exploring && H > 0) {
            float k = 1 - (float) Math.exp(-dt * 8);
            camX += (fx - camX) * k;
            camY += ((fy - (H * 0.28f) / zoom) - camY) * k;
        }
        runeT += dt;

        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy en = enemies.get(i);
            en.animT += dt;
            if (en.hitFlash > 0) en.hitFlash -= dt;
            if (en.dead) {
                en.deathT += dt;
                en.floater.moving = false;
                en.floater.update(dt);
                if (en.deathT > 0.7f) {
                    spawnDeathParticles(en.x, en.y);
                    enemies.remove(i);
                }
            }
        }

        if (player.isAttacking() && !playerHitDone
                && player.attackTime > ATK_DUR[attackType - 1] * ATK_HIT[attackType - 1]) {
            playerHitDone = true;
            hitstopT = 0.05f;
            shakeT = 0.15f;
            if (attackType == 1) {
                if (strikeTarget != null) hurtEnemy(strikeTarget, ATK_DMG[0]);
            } else if (attackType == 2) {
                if (pendingBolt != null) {
                    spawnBolt(player.x + player.facing * 40, player.y - PLAYER_H * 0.75f,
                              pendingBolt.x, pendingBolt.y - ENEMY_H * 0.5f, pendingBolt);
                    pendingBolt = null;
                }
            } else {
                spawnBlast(player.x, player.y);
                worldToHex(player.x, player.y, IH_A);
                for (Enemy en : enemies) {
                    if (en.dead) continue;
                    worldToHex(en.x, en.y, IH_B);
                    if (hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= ATK_RANGE[2]) {
                        hurtEnemy(en, ATK_DMG[2]);
                    }
                }
            }
        }
        if (!player.isAttacking()) playerHitDone = false;

        for (Bolt b : boltPool) {
            if (!b.active) continue;
            b.t += dt;
            float kk = b.t / 0.28f;
            if (kk >= 1f) {
                if (!b.tgt.dead) {
                    hurtEnemy(b.tgt, ATK_DMG[1]);
                    hitstopT = 0.04f;
                    shakeT = 0.1f;
                }
                b.active = false;
            } else {
                b.x = b.x0 + (b.tx - b.x0) * kk;
                b.y = b.y0 + (b.ty - b.y0) * kk - (float) Math.sin(kk * Math.PI) * 40;
            }
        }

        for (Blast bl : blastPool) {
            if (!bl.active) continue;
            bl.t += dt;
            if (bl.t > 0.5f) bl.active = false;
        }

        for (Particle p : particlePool) {
            if (!p.active) continue;
            p.t += dt;
            if (p.t >= p.life) { p.active = false; continue; }
            p.vy += 400 * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
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
                    if (en.attacking() && !en.glowing && en.attackT > 0.45f && !en.struck) {
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
                spawnPuff(player.x + (float) (Math.random() * 36 - 18),
                          player.y + (float) (Math.random() * 10 - 5));
            }
        }
        for (Puff p : puffPool) {
            if (!p.active) continue;
            p.t += dt;
            if (p.t > 0.5f) p.active = false;
        }
        for (Dmg d : dmgPool) {
            if (!d.active) continue;
            d.t += dt;
            if (d.t > 0.8f) d.active = false;
        }
    }

    private void draw() {
        SurfaceHolder h = getHolder();
        if (!h.getSurface().isValid()) return;
        Canvas cv;
        if (Build.VERSION.SDK_INT >= 26) cv = h.lockHardwareCanvas();
        else cv = h.lockCanvas();
        if (cv == null) return;

        W = cv.getWidth(); H = cv.getHeight();
        if (state == STATE_MENU) drawMenu(cv);
        else if (state == STATE_STORY) story.draw(cv);
        else drawGame(cv);
        h.unlockCanvasAndPost(cv);
    }

    private float sx(float wx) { return (wx - camX + shakeX) * zoom + W / 2f; }
    private float sy(float wy) { return (wy - camY + shakeY) * zoom + H / 2f; }

    private void drawMenu(Canvas cv) {
        if (menuBmp != null) { rf.set(0, 0, W, H); cv.drawBitmap(menuBmp, null, rf, paint); }
        else cv.drawColor(0xFF0d0714);

        paint.setColor(0xFFff7a30);
        for (Ember em : embers) {
            paint.setAlpha((int) (40 + em.s));
            cv.drawCircle(em.x, em.y, 1.5f + em.s / 40f, paint);
        }
        paint.setAlpha(255);

        paint.setTextAlign(Paint.Align.CENTER);
        float ts = Math.min(W * 0.16f, 170);
        paint.setTextSize(ts);
        paint.setFakeBoldText(true);
        paint.setColor(0xFF1a0716);
        cv.drawText("DOWN", W / 2f + ts * 0.035f, H * 0.30f + ts * 0.06f, paint);
        paint.setColor(0xFFff2bd6);
        cv.drawText("DOWN", W / 2f, H * 0.30f, paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(Math.min(W * 0.028f, 26));
        paint.setColor(0x88cfc6e8);
        cv.drawText("a hex descent", W / 2f, H * 0.30f + ts * 0.42f, paint);

        float bw = Math.min(W * 0.55f, 540), bh = Math.min(H * 0.13f, 92);
        float gap = Math.max(24, H * 0.045f);
        menuBtnTest.set(W / 2f - bw / 2, H * 0.52f, W / 2f + bw / 2, H * 0.52f + bh);
        menuBtnStory.set(W / 2f - bw / 2, H * 0.52f + bh + gap,
                W / 2f + bw / 2, H * 0.52f + bh * 2 + gap);
        drawMenuButton(cv, menuBtnTest, "TEST MODE", 0xFFff2bd6, menuPress == 1, true);
        drawMenuButton(cv, menuBtnStory, "STORY MODE", 0xFF7d78a0, menuPress == 2, true);

        if (overlay != null) { rf.set(0, 0, W, H); cv.drawBitmap(overlay, null, rf, paint); }

        paint.setTextSize(22);
        paint.setColor(0x55ffffff);
        cv.drawText("v0.1", 24, H - 24, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawMenuButton(Canvas cv, RectF r, String label, int accent,
                                boolean pressed, boolean enabled) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? 0x50301f4a : 0x2A1c1230);
        cv.drawRoundRect(r, 22, 22, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(pressed ? 4f : 2.5f);
        paint.setColor(enabled ? accent : 0xFF55506e);
        cv.drawRoundRect(r, 22, 22, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(enabled ? 0xFFffffff : 0xFF9a94b8);
        paint.setTextSize(Math.min(r.height() * 0.38f, 40));
        cv.drawText(label, r.centerX(), r.centerY() + paint.getTextSize() * 0.35f, paint);
        paint.setStrokeWidth(0);
    }

    private boolean onMenuTouch(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            menuPress = 0;
            if (menuBtnTest.contains(e.getX(), e.getY())) menuPress = 1;
            else if (menuBtnStory.contains(e.getX(), e.getY())) menuPress = 2;
            return true;
        }
        if (act == MotionEvent.ACTION_UP) {
            if (menuPress == 1 && menuBtnTest.contains(e.getX(), e.getY())) startGame();
            if (menuPress == 2 && menuBtnStory.contains(e.getX(), e.getY())) startStory();
            menuPress = 0;
            return true;
        }
        return true;
    }

    private void drawGround(Canvas cv) {
        float halfW = W / (2f * zoom), halfH = H / (2f * zoom);
        float wx0 = camX - halfW, wx1 = camX + halfW;
        float wy0 = camY - halfH, wy1 = camY + halfH;

        paint.setAlpha(255);
        paint.setColor(0xFF1c1320);
        cv.drawRect(0, 0, W, H, paint);

        float strip = 30f;
        float x = ((float) Math.floor(wx0 / strip) - 1) * strip;
        for (; x < wx1 + strip; x += strip) {
            float tx = x / TILE;
            float cyw = roadCenterF(tx) * TH;
            float half = TILE * (0.80f + 0.10f * (float) Math.sin(tx * 0.21f + 0.9f)) * SQUASH;
            float sxp = sx(x), w = strip * zoom + 2f;
            float top = sy(cyw - half), bot = sy(cyw + half);

            paint.setColor(0xFF150d18);
            rf.set(sxp - 1, top - 9 * zoom, sxp + w + 1, top + 2 * zoom);
            cv.drawRect(rf, paint);
            rf.set(sxp - 1, bot - 2 * zoom, sxp + w + 1, bot + 9 * zoom);
            cv.drawRect(rf, paint);

            paint.setColor(0xFF2c2030);
            rf.set(sxp, top, sxp + w, bot);
            cv.drawRect(rf, paint);

            paint.setColor(0xFF3a2c40);
            rf.set(sxp, top + 2 * zoom, sxp + w, top + 5 * zoom);
            cv.drawRect(rf, paint);
            rf.set(sxp, bot - 5 * zoom, sxp + w, bot - 2 * zoom);
            cv.drawRect(rf, paint);

            int idx = (int) Math.floor(x / (TILE * 0.9f));
            if ((idx & 1) == 0) {
                paint.setColor(0x55120b16);
                float my = sy(cyw);
                rf.set(sxp + 3 * zoom, my - 1.5f * zoom, sxp + w - 3 * zoom, my + 1.5f * zoom);
                cv.drawRect(rf, paint);
            }
        }

        int px0 = (int) Math.floor(wx0 / (TILE * 2)) - 1, px1 = (int) Math.ceil(wx1 / (TILE * 2)) + 1;
        int py0 = (int) Math.floor(wy0 / (TH * 2)) - 1, py1 = (int) Math.ceil(wy1 / (TH * 2)) + 1;
        for (int py = py0; py <= py1; py++) {
            for (int px = px0; px <= px1; px++) {
                int h = (px * 40503) ^ (py * 66827);
                if (((h >>> 4) & 7) != 0) continue;
                float cxw = px * TILE * 2 + TILE * (0.3f + ((h >>> 7) & 127) / 127f * 1.4f);
                float cyw = py * TH * 2 + TH * (0.3f + ((h >>> 11) & 127) / 127f * 1.4f);
                if (Math.abs(cyw / TH - roadCenterF(cxw / TILE)) < 1.0f) continue;
                float rw = (50 + ((h >>> 15) & 63)) * zoom;
                float rh = rw * SQUASH * (0.6f + ((h >>> 21) & 31) / 31f * 0.5f);
                paint.setColor(0xFF160e1c);
                rf.set(sx(cxw) - rw, sy(cyw) - rh, sx(cxw) + rw, sy(cyw) + rh);
                cv.drawOval(rf, paint);
            }
        }

        int ty0 = (int) Math.floor(wy0 / TH) - 1, ty1 = (int) Math.ceil(wy1 / TH) + 1;
        paint.setColor(0x12000000);
        for (int ty = ty0; ty <= ty1 + 1; ty++) {
            float y = sy(ty * TH);
            rf.set(-2, y - zoom, W + 2, y + 1.6f * zoom);
            cv.drawRect(rf, paint);
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
        int ty0 = (int) Math.floor((camY - halfH) / TH) - 1;
        int ty1 = (int) Math.ceil ((camY + halfH) / TH) + 1;

        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int h = (tx * 40503) ^ (ty * 66827);
                int roll = (h >>> 3) % 100;
                if (roll < 8 && !props2.isEmpty()) {
                    D d = obtainD();
                    d.kind = 0;
                    d.pr = props2.get((h >>> 5) % props2.size());
                    d.ax = tx * TILE + TILE * (0.3f + ((h >>> 9) & 127) / 127f * 0.4f);
                    d.ay = (ty + 1) * TH - TH * 0.10f;
                    d.s = (TH * 2.43f / d.pr.getHeight())
                            * (0.85f + ((h >>> 13) & 31) / 31f * 0.45f);
                    d.y = d.ay;
                    drawList.add(d);
                } else if (roll < 22 && !props.isEmpty()) {
                    D d = obtainD();
                    d.kind = 0;
                    d.pr = props.get((h >>> 5) % props.size());
                    d.ax = tx * TILE + TILE * (0.25f + ((h >>> 9) & 127) / 127f * 0.5f);
                    d.ay = (ty + 1) * TH - TH * (0.15f + ((h >>> 11) & 31) / 31f * 0.25f);
                    d.s = (TH * 0.45f / d.pr.getHeight())
                            * (0.8f + ((h >>> 15) & 31) / 31f * 0.5f);
                    d.y = d.ay;
                    drawList.add(d);
                }
            }
        }

        D p = obtainD(); p.kind = 1; p.y = player.y; drawList.add(p);
        for (Enemy en : enemies) { D d = obtainD(); d.kind = 2; d.en = en; d.y = en.y; drawList.add(d); }

        Collections.sort(drawList, BY_Y);
        for (D d : drawList) {
            if (d.kind == 0) drawProp(cv, d);
            else if (d.kind == 1) drawPlayer(cv);
            else drawEnemy(cv, d.en);
        }
    }

    private void drawProp(Canvas cv, D d) {
        float s = d.s * zoom;
        rf.set(sx(d.ax) - d.pr.getWidth() * s / 2f, sy(d.ay) - d.pr.getHeight() * s,
               sx(d.ax) + d.pr.getWidth() * s / 2f, sy(d.ay));
        paint.setAlpha(255);
        cv.drawBitmap(d.pr, null, rf, paint);
    }

    private void drawHex(Canvas cv, float cx, float cy, int color, boolean filled) {
        float hr = HEX * 1.1f * zoom;
        rf.set(cx - hr, cy - hr * SQUASH, cx + hr, cy + hr * SQUASH);
        if (hexFilter == null || hexFilter.getColor() != color) {
            hexFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        paint.setColorFilter(hexFilter);
        cv.drawBitmap(hexBmp, null, rf, paint);
        paint.setColorFilter(null);

        if (filled) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * zoom);
            paint.setColor(0x44ffffff);
            cv.drawOval(rf, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(0);
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
        int range = ATK_RANGE[attackRangeShown - 1];
        boolean nova = attackRangeShown == 3;
        worldToHex(player.x, player.y, IH_A);
        for (int r = -range; r <= range; r++) {
            for (int q = -range; q <= range; q++) {
                int d = hexDist(IH_A[0], IH_A[1], IH_A[0] + q, IH_A[1] + r);
                if (d > range) continue;
                if (d < 1 && !nova) continue;
                hexToWorld(IH_A[0] + q, IH_A[1] + r, FW_A);
                if (nova) drawHex(cv, sx(FW_A[0]), sy(FW_A[1]), 0x77ff2233, true);
                else      drawHex(cv, sx(FW_A[0]), sy(FW_A[1]), 0xCCcc2233, false);
            }
        }
        if (!nova) {
            for (Enemy en : enemies) {
                if (en.dead) continue;
                worldToHex(en.x, en.y, IH_B);
                if (hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= range) {
                    hexToWorld(IH_B[0], IH_B[1], FW_A);
                    drawHex(cv, sx(FW_A[0]), sy(FW_A[1]), 0x66ff2233, true);
                }
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
        for (Puff p : puffPool) {
            if (!p.active) continue;
            float k = p.t / 0.5f;
            paint.setColor(0xFF6a2f8f);
            paint.setAlpha((int) (110 * (1 - k)));
            cv.drawCircle(sx(p.x), sy(p.y) - k * 26 * zoom, (12 + k * 46) * zoom, paint);
        }
        paint.setAlpha(255);
    }

    private void drawBlasts(Canvas cv) {
        for (Blast b : blastPool) {
            if (!b.active) continue;
            float k = b.t / 0.5f;
            float r = (40 + k * HEX * 3.6f) * zoom;
            rf.set(sx(b.x) - r, sy(b.y) - r * SQUASH, sx(b.x) + r, sy(b.y) + r * SQUASH);

            if (blastFilter == null) {
                blastFilter = new PorterDuffColorFilter(0xFF8A0303, PorterDuff.Mode.SRC_IN);
            }
            paint.setColorFilter(blastFilter);
            paint.setAlpha((int) (220 * (1 - k)));
            cv.drawBitmap(blastBmp, null, rf, paint);
            paint.setColorFilter(null);
            paint.setAlpha(255);
        }
    }

    private void drawParticles(Canvas cv) {
        for (Particle p : particlePool) {
            if (!p.active) continue;
            float k = p.t / p.life;
            paint.setColor(p.col);
            paint.setAlpha((int) (255 * (1 - k)));
            cv.drawCircle(sx(p.x), sy(p.y), (4 + k * 6) * zoom, paint);
        }
        paint.setAlpha(255);
    }

    private static float blendCurve(float frac) {
        float k = (frac - 0.65f) / 0.35f;
        return k < 0 ? 0 : (k > 1 ? 1 : k);
    }

    private void computePlayerFrame() {
        frameB = null; frameK = 0;
        if (player.isAttacking() && !atkFr.isEmpty()) {
            int[] seq = ATK_SEQ[attackType - 1];
            float pos = (player.attackTime / player.attackDuration) * seq.length;
            int i0 = (int) pos;
            if (i0 >= seq.length) { i0 = seq.length - 1; pos = i0; }
            int i1 = Math.min(i0 + 1, seq.length - 1);
            frameA = atkFr.get(seq[i0]);
            frameB = atkFr.get(seq[i1]);
            frameK = blendCurve(pos - i0);
            return;
        }
        if (player.floater.state == 0 && !idleFr.isEmpty()) {
            frameA = idleFr.get(((int) (player.bobTime * 3f)) % idleFr.size());
            return;
        }
        if (glideFr.size() >= 4) {
            Floater f = player.floater;
            if (f.state == 1) {
                frameA = glideFr.get(f.t < 0.1f ? 3 : 1);
            } else if (f.state == 2) {
                float pos = player.bobTime * 6f;
                int i0 = 1 + ((int) pos) % 2;
                int i1 = 1 + (((int) pos) + 1) % 2;
                frameA = glideFr.get(i0);
                frameB = glideFr.get(i1);
                frameK = blendCurve(pos - (int) pos);
            } else {
                frameA = glideFr.get(f.t < 0.06f ? 2 : f.t < 0.12f ? 1 : f.t < 0.17f ? 3 : 0);
            }
            return;
        }
        frameA = null;
    }

    private void drawFrame(Canvas cv, Frame f, int alpha) {
        float s = PLAYER_H * zoom / f.ref;
        paint.setAlpha(alpha);
        if (f.vCrop) {
            frameSrc.set(0, f.top, f.bmp.getWidth(), f.top + f.ch);
            rf.set(-f.bmp.getWidth() * s / 2f, -f.ch * s, f.bmp.getWidth() * s / 2f, 0);
        } else if (f.cCenter) {
            int wl = Math.max(0, f.rgt - f.ww);
            int wr = f.rgt;
            if (wl >= wr || f.top + f.ch > f.bmp.getHeight()) {
                frameSrc.set(0, 0, f.bmp.getWidth(), f.bmp.getHeight());
                rf.set(-f.bmp.getWidth() * s / 2f, -f.bmp.getHeight() * s,
                        f.bmp.getWidth() * s / 2f, 0);
            } else {
                frameSrc.set(wl, f.top, wr, f.top + f.ch);
                float right = f.ww * s / 2f;
                rf.set(right - (wr - wl) * s, -f.ch * s, right, 0);
            }
        } else {
            frameSrc.set(0, 0, f.bmp.getWidth(), f.bmp.getHeight());
            rf.set(-f.bmp.getWidth() * s / 2f, -f.bmp.getHeight() * s,
                    f.bmp.getWidth() * s / 2f, 0);
        }
        if (f.dx != 0) rf.offset(f.dx * zoom, 0);
        cv.drawBitmap(f.bmp, frameSrc, rf, paint);
        paint.setAlpha(255);
    }

    private void drawPlayer(Canvas cv) {
        boolean fl = player.floater.floating();
        boolean idle = player.floater.state == 0 && !player.isAttacking();
        float br = idle ? (float) Math.sin(player.bobTime * 1.7f) : 0f;

        float by = sy(player.y + player.floater.visualY) + FOOT_DROP * zoom;

        float sw = (fl ? 45 : 55) * zoom * (1f - 0.045f * br);
        paint.setAlpha(fl ? 150 : 220);
        rf.set(sx(player.x) - sw, by - sw * 0.36f,
               sx(player.x) + sw, by + sw * 0.36f);
        cv.drawBitmap(shadowBmp, null, rf, paint);
        paint.setAlpha(255);

        computePlayerFrame();
        cv.save();
        cv.translate(sx(player.x), by);
        if (player.facing < 0) cv.scale(-1, 1);
        if (br != 0f) cv.scale(1f - 0.018f * br, 1f + 0.03f * br);
        if (frameA != null) drawFrame(cv, frameA, 255);
        if (frameB != null && frameK > 0.02f) drawFrame(cv, frameB, (int) (frameK * 255));
        cv.restore();

        float top = by - PLAYER_H * zoom - 34;
        float bw = 90, bh = 8;
        rf.set(sx(player.x) - bw/2, top, sx(player.x) + bw/2, top + bh);
        paint.setColor(0xCC050508); cv.drawRoundRect(rf, 4, 4, paint);
        rf.right = rf.left + bw * (playerHp / 100f);
        paint.setColor(0xFF8A0303); cv.drawRoundRect(rf, 4, 4, paint);

        rf.set(sx(player.x) - bw/2, top + 11, sx(player.x) + bw/2, top + 11 + bh);
        paint.setColor(0xCC050508); cv.drawRoundRect(rf, 4, 4, paint);
        rf.right = rf.left + bw * (mana / 100f);
        paint.setColor(0xFF3355ff); cv.drawRoundRect(rf, 4, 4, paint);
    }

    private Frame pickEnemyFrame(Enemy en) {
        if (en.glowing && !eGlowFr.isEmpty()) {
            float pos = (en.attackT / Enemy.GLOW_DUR) * eGlowFr.size();
            int i = (int) pos;
            if (i < 0) i = 0;
            if (i >= eGlowFr.size()) i = eGlowFr.size() - 1;
            return eGlowFr.get(i);
        }
        if (en.attacking() && !en.glowing && !eAtkFr.isEmpty()) {
            float pos = (en.attackT / Enemy.ATK_DUR) * eAtkFr.size();
            int i = (int) pos;
            if (i < 0) i = 0;
            if (i >= eAtkFr.size()) i = eAtkFr.size() - 1;
            return eAtkFr.get(i);
        }
        if (en.floater.state == 0 && !eIdleFr.isEmpty()) {
            return eIdleFr.get(((int) (en.animT * 3f)) % eIdleFr.size());
        }
        if (eGlideFr.size() >= 4) {
            Floater f = en.floater;
            if (f.state == 1) return eGlideFr.get(f.t < 0.1f ? 3 : 1);
            if (f.state == 2) {
                int i = 1 + ((int) (en.animT * 6f)) % 2;
                return eGlideFr.get(i);
            }
            return eGlideFr.get(f.t < 0.06f ? 2 : f.t < 0.12f ? 1 : f.t < 0.17f ? 3 : 0);
        }
        return null;
    }

    private void drawEnemy(Canvas cv, Enemy en) {
        float x = sx(en.x), y = sy(en.y + en.floater.visualY) + FOOT_DROP * zoom;
        boolean idle = en.floater.state == 0 && !en.attacking() && !en.dead;
        float br = idle ? (float) Math.sin(en.animT * 1.7f) : 0f;
        float sw = 45 * zoom * (1f - 0.045f * br);
        paint.setAlpha(220);
        rf.set(x - sw, y - sw * 0.36f, x + sw, y + sw * 0.36f);
        cv.drawBitmap(shadowBmp, null, rf, paint);
        paint.setAlpha(255);

        Frame fr = pickEnemyFrame(en);
        Paint p = (en.hitFlash > 0) ? tintPaint : paint;
        cv.save();
        cv.translate(x, y);
        if (en.facing < 0) cv.scale(-1, 1);
        if (br != 0f) cv.scale(1f - 0.018f * br, 1f + 0.03f * br);
        if (en.dead) p.setAlpha((int) (255 * (1 - en.deathT / 0.7f)));
        if (fr != null) {
            float s = ENEMY_H * zoom / fr.ref;
            if (fr.vCrop) {
                frameSrc.set(0, fr.top, fr.bmp.getWidth(), fr.top + fr.ch);
                rf.set(-fr.bmp.getWidth() * s / 2f, -fr.ch * s, fr.bmp.getWidth() * s / 2f, 0);
            } else if (fr.cCenter) {
                int wl = Math.max(0, fr.rgt - fr.ww);
                int wr = fr.rgt;
                frameSrc.set(wl, fr.top, wr, fr.top + fr.ch);
                float right = fr.ww * s / 2f;
                rf.set(right - (wr - wl) * s, -fr.ch * s, right, 0);
            } else {
                frameSrc.set(0, 0, fr.bmp.getWidth(), fr.bmp.getHeight());
                rf.set(-fr.bmp.getWidth() * s / 2f, -fr.bmp.getHeight() * s,
                        fr.bmp.getWidth() * s / 2f, 0);
            }
            cv.drawBitmap(fr.bmp, frameSrc, rf, p);
        } else {
            p.setColor(0xFFaa2233);
            rf.set(-30 * zoom, -ENEMY_H * 0.8f * zoom, 30 * zoom, 0);
            cv.drawOval(rf, p);
        }
        p.setAlpha(255);
        cv.restore();

        if (!en.dead) {
            float top = y - ENEMY_H * zoom - 24;
            float bw = 90, bh = 8;
            rf.set(x - bw/2, top, x + bw/2, top + bh);
            paint.setColor(0xCC050508); cv.drawRoundRect(rf, 4, 4, paint);
            rf.right = rf.left + bw * (en.hp / (float)en.maxHp);
            paint.setColor(0xFF8A0303); cv.drawRoundRect(rf, 4, 4, paint);
        }
    }

    private void drawBolts(Canvas cv) {
        for (Bolt b : boltPool) {
            if (!b.active) continue;
            float dx = b.tx - b.x0, dy = b.ty - b.y0;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 1) d = 1;
            for (int t = 3; t >= 1; t--) {
                paint.setColor(0xFF8A0303);
                paint.setAlpha(80 - t * 20);
                cv.drawCircle(sx(b.x - dx / d * t * 22), sy(b.y - dy / d * t * 22),
                        (12 - t * 2) * zoom, paint);
            }
            paint.setAlpha(220);
            paint.setColor(0xFF8A0303);
            cv.drawCircle(sx(b.x), sy(b.y), 13 * zoom, paint);
            paint.setColor(0xFFffffff);
            cv.drawCircle(sx(b.x), sy(b.y), 6 * zoom, paint);
            paint.setAlpha(255);
        }
    }

    private void drawDmgs(Canvas cv) {
        paint.setTextSize(34);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        for (Dmg d : dmgPool) {
            if (!d.active) continue;
            float k = d.t / 0.8f;
            paint.setAlpha((int) (255 * (1 - k)));
            paint.setColor(d.val < 0 ? 0xFF8A0303 : 0xFFffffff);
            cv.drawText(String.valueOf(d.val), sx(d.x), sy(d.y) - k * 80, paint);
        }
        paint.setFakeBoldText(false);
        paint.setAlpha(255);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawGame(Canvas cv) {
        cv.drawColor(GROUND_COL);

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
        drawParticles(cv);

        drawBlasts(cv);
        drawBolts(cv);
        drawDmgs(cv);

        if (overlay != null) { rf.set(0, 0, W, H); cv.drawBitmap(overlay, null, rf, paint); }

        paint.setColor(0xFF050508);
        paint.setAlpha(180);
        rf.set(0, 0, W, H);
        cv.drawRoundRect(rf, 0, 0, paint);
        paint.setAlpha(255);

        paint.setColor(0xFFff7a30);
        for (Ember em : embers) {
            paint.setAlpha((int) (40 + em.s));
            cv.drawCircle(em.x, em.y, 1.5f + em.s / 40f, paint);
        }
        paint.setAlpha(255);

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
    }

    private void drawUI(Canvas cv) {
        paint.setTextAlign(Paint.Align.CENTER);
        float btnSize = 120;
        float bottomY = H - 90;

        drawGlassButton(cv, 110, bottomY, btnSize, "END", 0xFF3355ff, true);
        drawGlassButton(cv, W - 110, bottomY, btnSize, "REND", 0xFF8A0303, mana >= ATK_MANA[0]);
        drawGlassButton(cv, W - 250, bottomY, btnSize, "BOLT", 0xFFcc4400, mana >= ATK_MANA[1]);
        drawGlassButton(cv, W - 390, bottomY, btnSize, "NOVA", 0xFF6611aa, mana >= ATK_MANA[2]);

        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawGlassButton(Canvas cv, float cx, float cy, float size, String label, int accent, boolean enabled) {
        float half = size / 2f;
        rf.set(cx - half, cy - half, cx + half, cy + half);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC050508);
        cv.drawRoundRect(rf, 20, 20, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(enabled ? accent : 0xFF222222);
        cv.drawRoundRect(rf, 20, 20, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(enabled ? 0xFFe0e0e0 : 0xFF555555);
        paint.setTextSize(28);
        paint.setFakeBoldText(true);
        cv.drawText(label, cx, cy + 10, paint);
        paint.setFakeBoldText(false);
    }

    private boolean uiZone(float x, float y) {
        if (x < 190 && y > H - 190) return true;
        if (x > W - 490 && y > H - 190) return true;
        return false;
    }

    private static float pointerDist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (state == STATE_MENU) return onMenuTouch(e);
        if (state == STATE_STORY) return story.touch(e);
        if (deadT > 0 || phase != PH_PLAYER) return true;
        int act = e.getActionMasked();

        if (act == MotionEvent.ACTION_DOWN) {
            downX = e.getX(); downY = e.getY();
            moved = false; panning = false; pinching = false;
            flingX = 0; flingY = 0; velX = 0; velY = 0;
            lastMoveT = e.getEventTime();
            return true;
        }
        if (act == MotionEvent.ACTION_POINTER_DOWN) {
            if (e.getPointerCount() == 2) {
                pinching = true;
                moved = true;
                pinchDist0 = pointerDist(e);
                pinchZoom0 = zoom;
            }
            return true;
        }
        if (act == MotionEvent.ACTION_MOVE) {
            if (pinching && e.getPointerCount() >= 2) {
                if (pinchDist0 > 1) {
                    zoom = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN,
                            pinchZoom0 * pointerDist(e) / pinchDist0));
                }
                return true;
            }
            if (downX < -9000) return true;
            float x = e.getX(), y = e.getY();
            if (!moved && Math.hypot(x - downX, y - downY) > 26) {
                moved = true;
                panning = !uiZone(downX, downY);
                lastPX = downX; lastPY = downY;
                lastMoveT = e.getEventTime();
            }
            if (moved && panning) {
                long now = e.getEventTime();
                float dtm = (now - lastMoveT) / 1000f;
                if (dtm > 0.001f) {
                    velX = velX * 0.7f + (-(x - lastPX) / zoom / dtm) * 0.3f;
                    velY = velY * 0.7f + (-(y - lastPY) / zoom / dtm) * 0.3f;
                    lastMoveT = now;
                }
                camX -= (x - lastPX) / zoom;
                camY -= (y - lastPY) / zoom;
                exploring = true;
                exploreT = 0;
                lastPX = x; lastPY = y;
            }
            return true;
        }
        if (act == MotionEvent.ACTION_POINTER_UP) {
            pinching = false;
            return true;
        }
        if (act != MotionEvent.ACTION_UP) return true;
        float x = e.getX(), y = e.getY();
        downX = -9999;
        if (pinching) { pinching = false; return true; }
        if (moved) {
            if (panning) { flingX = velX; flingY = velY; }
            panning = false;
            return true;
        }
        panning = false;

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
            if (!hasAttacked && mana >= ATK_MANA[1]) {
                attackRangeShown = (attackRangeShown == 2) ? 0 : 2;
                hexesShown = false; targetEnemy = null;
            }
            return true;
        }
        if (x > W - 490 && x < W - 340 && y > H - 190) {
            if (!hasAttacked && mana >= ATK_MANA[2]) {
                attackRangeShown = (attackRangeShown == 3) ? 0 : 3;
                hexesShown = false; targetEnemy = null;
            }
            return true;
        }

        float wx = camX + (x - W / 2f) / zoom;
        float wy = camY + (y - H / 2f) / zoom;
        worldToHex(wx, wy, TW_A);
        worldToHex(player.x, player.y, TW_B);
        int dTap = hexDist(TW_B[0], TW_B[1], TW_A[0], TW_A[1]);

        if (attackRangeShown == 3 && !hasAttacked && dTap <= ATK_RANGE[2]) {
            hasAttacked = true;
            attackType = 3;
            player.facing = wx >= player.x ? 1 : -1;
            player.attackDuration = ATK_DUR[2];
            player.startAttack();
            mana -= ATK_MANA[2];
            attackRangeShown = 0;
            hexesShown = false;
            targetEnemy = null;
            return true;
        }

        Enemy tapped = null;
        float bestY = -1f;
        for (Enemy en : enemies) {
            if (en.dead) continue;
            float ex = sx(en.x), ey = sy(en.y) + FOOT_DROP * zoom;
            float hw = ENEMY_H * 0.4f * zoom;
            if (x >= ex - hw && x <= ex + hw
                    && y >= ey - ENEMY_H * zoom - 20 && y <= ey + 10) {
                if (en.y > bestY) { bestY = en.y; tapped = en; }
            }
        }
        if (tapped == null) {
            for (Enemy en : enemies) {
                if (en.dead) continue;
                worldToHex(en.x, en.y, TW_C);
                if (TW_C[0] == TW_A[0] && TW_C[1] == TW_A[1]) { tapped = en; break; }
            }
        }

        if (tapped != null) {
            int range = attackRangeShown > 0 ? ATK_RANGE[attackRangeShown - 1] : 0;
            if (!hasAttacked && range > 0 && dTap <= range) {
                if (targetEnemy != tapped) {
                    targetEnemy = tapped;
                } else {
                    hasAttacked = true;
                    attackType = attackRangeShown;
                    player.facing = tapped.x >= player.x ? 1 : -1;
                    player.attackDuration = ATK_DUR[attackType - 1];
                    player.startAttack();
                    mana -= ATK_MANA[attackType - 1];
                    if (attackType == 1) strikeTarget = tapped;
                    else if (attackType == 2) pendingBolt = tapped;
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
