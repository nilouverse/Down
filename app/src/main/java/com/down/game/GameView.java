package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;

public class GameView extends SurfaceView implements Runnable {

    private static final int PH_PLAYER = 0, PH_ENEMY = 1;
    private static final float HEX = 96f, SQUASH = 0.5f;
    private static final int MOVE_HEX = 3;
    private static final float PLAYER_H = 260f, ENEMY_H = 200f;

    private Thread loop;
    private volatile boolean running;

    private final Player player = new Player();
    private float camX, camY;

    private List<Bitmap> idleF, glide, attack, eGlide, eAttack;

    private float runeX, runeY, runeT = 99;

    private static class Puff { float x, y, t; }
    private final ArrayList<Puff> puffs = new ArrayList<>();
    private float puffTimer;

    // hex interaction
    private boolean hexesShown = false;
    private int moveLeft = 3;
    private int attackRangeShown = 0;      // 0 none, 1 melee, 2 ranged
    private int attackType = 1;
    private Enemy targetEnemy = null;
    private Enemy strikeTarget = null;     // melee, hits mid-swing
    private Enemy pendingBolt = null;      // ranged, ball flies
    private static class Bolt { float x, y; Enemy tgt; }
    private final ArrayList<Bolt> bolts = new ArrayList<>();

    // turns
    private int phase = PH_PLAYER;
    private float phaseT = 0;
    private int ei = 0;
    private boolean hasAttacked = false;

    // combat
    private final ArrayList<Enemy> enemies = new ArrayList<>();
    private int playerHp = 100;
    private float hurtT = 0, deadT = 0;
    private boolean playerHitDone = false;
    private static class Dmg { float x, y, t; int val; }
    private final ArrayList<Dmg> dmgs = new ArrayList<>();
    private final Paint tintPaint = new Paint();

    private final Paint paint = new Paint();
    private final Path hexPath = new Path();
    private int W, H;

    public GameView(Context ctx) {
        super(ctx);
        idleF   = Sprites.cutSheet(ctx, "sprites/idle.png",         2, 2, 4);
        glide   = Sprites.cutSheet(ctx, "sprites/glide.png",        2, 4, 4);
        attack  = Sprites.cutSheet(ctx, "sprites/attack.png",       2, 4, 4);
        eGlide  = Sprites.cutSheet(ctx, "sprites/enemy_glide.png",  2, 4, 4);
        eAttack = Sprites.cutSheet(ctx, "sprites/enemy_attack.png", 2, 4, 4);
        paint.setFilterBitmap(true);
        tintPaint.setFilterBitmap(true);
        tintPaint.setColorFilter(new ColorMatrixColorFilter(new float[] {
                1, 0, 0, 0, 120,
                0, 0.6f, 0, 0, 0,
                0, 0, 0.6f, 0, 0,
                0, 0, 0, 1, 0 }));

        int[] h0 = worldToHex(640, 640);
        float[] c0 = hexToWorld(h0[0], h0[1]);
        player.x = c0[0]; player.y = c0[1];
        player.targetX = c0[0]; player.targetY = c0[1];

        for (int i = 0; i < 3; i++) spawnEnemy();
        startPlayerTurn();
    }

    public void start() { running = true; loop = new Thread(this); loop.start(); }
    public void stop()  { running = false; try { loop.join(); } catch (Exception e) {} }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { W = w; H = h; }

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
            try { Thread.sleep(8); } catch (Exception e) {}
        }
    }

    // ---------- hex math ----------
    private static float[] hexToWorld(int q, int r) {
        float x = HEX * (float) Math.sqrt(3) * (q + r / 2f);
        float y = HEX * 1.5f * r * SQUASH;
        return new float[] { x, y };
    }

    private static int[] worldToHex(float x, float y) {
        float hy = y / SQUASH;
        float qf = ((float) Math.sqrt(3) / 3f * x - 1f / 3f * hy) / HEX;
        float rf = (2f / 3f * hy) / HEX;
        float sf = -qf - rf;
        int rq = Math.round(qf), rr = Math.round(rf), rs = Math.round(sf);
        float dq = Math.abs(rq - qf), dr = Math.abs(rr - rf), ds = Math.abs(rs - sf);
        if (dq > dr && dq > ds) rq = -rr - rs;
        else if (dr > ds) rr = -rq - rs;
        return new int[] { rq, rr };
    }

    private static int hexDist(int q1, int r1, int q2, int r2) {
        int dq = q1 - q2, dr = r1 - r2;
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
    }

    private boolean hexOccupied(int q, int r, Enemy self) {
        int[] ph = worldToHex(player.x, player.y);
        if (ph[0] == q && ph[1] == r) return true;
        for (Enemy en : enemies) {
            if (en.dead || en == self) continue;
            int[] eh = worldToHex(en.x, en.y);
            if (eh[0] == q && eh[1] == r) return true;
        }
        return false;
    }

    // ---------- turns ----------
    private void spawnEnemy() {
        for (int tries = 0; tries < 8; tries++) {
            float a = (float) (Math.random() * Math.PI * 2);
            float x = player.x + (float) Math.cos(a) * HEX * 7;
            float y = player.y + (float) Math.sin(a) * HEX * 7 * SQUASH * 2;
            int[] h = worldToHex(x, y);
            float[] c = hexToWorld(h[0], h[1]);
            if (hexOccupied(h[0], h[1], null)) continue;
            Enemy e = new Enemy();
            e.x = c[0]; e.y = c[1];
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
        int[] ph = worldToHex(player.x, player.y);
        int[] eh = worldToHex(en.x, en.y);
        if (hexDist(ph[0], ph[1], eh[0], eh[1]) <= 1) return;
        float dx = player.x - en.x, dy = player.y - en.y;
        float d = (float) Math.hypot(dx, dy);
        float[] steps = { HEX * 3.2f, HEX * 1.6f };
        for (float st : steps) {
            int[] th = worldToHex(en.x + dx / d * st, en.y + dy / d * st);
            if (!hexOccupied(th[0], th[1], en)) {
                float[] c = hexToWorld(th[0], th[1]);
                en.tx = c[0]; en.ty = c[1];
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

        float k = Math.min(1, dt * 6);
        camX += (player.x - camX) * k;
        camY += ((player.y - H * 0.28f) - camY) * k;
        runeT += dt;

        // melee: connects mid-swing, no ball
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

        // ranged: ball launches mid-swing
        if (pendingBolt != null && player.isAttacking() && attackType == 2
                && player.attackTime > 0.4f) {
            Bolt b = new Bolt();
            b.x = player.x + player.facing * 40;
            b.y = player.y - PLAYER_H * 0.75f;
            b.tgt = pendingBolt;
            bolts.add(b);
            pendingBolt = null;
        }

        for (int i = bolts.size() - 1; i >= 0; i--) {
            Bolt b = bolts.get(i);
            if (b.tgt.dead) { bolts.remove(i); continue; }
            float dx = b.tgt.x - b.x, dy = (b.tgt.y - ENEMY_H * 0.5f) - b.y;
            float d = (float) Math.hypot(dx, dy);
            if (d < 30) {
                Enemy en = b.tgt;
                en.hp -= 12;
                en.hitFlash = 0.25f;
                addDmg(en.x, en.y - ENEMY_H - 20, 12);
                if (en.hp <= 0) en.dead = true;
                bolts.remove(i);
            } else {
                b.x += dx / d * 900 * dt;
                b.y += dy / d * 900 * dt;
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
                    int[] ph = worldToHex(player.x, player.y);
                    int[] eh = worldToHex(en.x, en.y);
                    boolean adj = hexDist(ph[0], ph[1], eh[0], eh[1]) == 1;
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

        drawWorld(cv);
        if (hexesShown) drawMoveFan(cv);
        if (attackRangeShown > 0) drawAttackRange(cv);
        if (targetEnemy != null && !targetEnemy.dead) {
            int[] th = worldToHex(targetEnemy.x, targetEnemy.y);
            float[] c = hexToWorld(th[0], th[1]);
            drawHex(cv, sx(c[0]), sy(c[1]), 0xAAcc2233);
        }
        drawRune(cv);
        drawPuffs(cv);

        for (Enemy en : enemies) if (en.y < player.y) drawEnemy(cv, en);
        drawPlayer(cv);
        for (Enemy en : enemies) if (en.y >= player.y) drawEnemy(cv, en);

        drawBolts(cv);
        drawDmgs(cv);
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

    private float sx(float wx) { return wx - camX + W / 2f; }
    private float sy(float wy) { return wy - camY + H / 2f; }

    private void drawWorld(Canvas cv) {
        final int TILE = 96;
        int x0 = (int) Math.floor((camX - W / 2f) / TILE) - 1;
        int x1 = (int) Math.ceil ((camX + W / 2f) / TILE) + 1;
        int y0 = (int) Math.floor((camY - H / 2f) / TILE) - 1;
        int y1 = (int) Math.ceil ((camY + H / 2f) / TILE) + 1;
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                int hash = (tx * 73856093 ^ ty * 19349663) & 3;
                int c;
                switch (hash) {
                    case 0:  c = 0xFF241a2e; break;
                    case 1:  c = 0xFF2b2036; break;
                    case 2:  c = 0xFF1d1526; break;
                    default: c = 0xFF281d31; break;
                }
                paint.setColor(c);
                float x = sx(tx * TILE), y = sy(ty * TILE);
                cv.drawRect(x, y, x + TILE + 1, y + TILE + 1, paint);
            }
        }
    }

    private void drawHex(Canvas cv, float cx, float cy, int color) {
        hexPath.reset();
        for (int i = 0; i < 6; i++) {
            float a = (float) Math.toRadians(60 * i - 30);
            float x = cx + HEX * 0.92f * (float) Math.cos(a);
            float y = cy + HEX * 0.92f * SQUASH * (float) Math.sin(a);
            if (i == 0) hexPath.moveTo(x, y); else hexPath.lineTo(x, y);
        }
        hexPath.close();
        paint.setColor(color);
        cv.drawPath(hexPath, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(0x66ffffff);
        cv.drawPath(hexPath, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // green = still reachable with remaining points, red = spent/blocked
    private void drawMoveFan(Canvas cv) {
        int[] ph = worldToHex(player.x, player.y);
        for (int r = -MOVE_HEX; r <= MOVE_HEX; r++) {
            for (int q = -MOVE_HEX; q <= MOVE_HEX; q++) {
                int d = hexDist(ph[0], ph[1], ph[0] + q, ph[1] + r);
                if (d < 1 || d > MOVE_HEX) continue;
                float[] c = hexToWorld(ph[0] + q, ph[1] + r);
                boolean ok = d <= moveLeft && !hexOccupied(ph[0] + q, ph[1] + r, null);
                drawHex(cv, sx(c[0]), sy(c[1]), ok ? 0x7722cc44 : 0x99cc2233);
            }
        }
    }

    private void drawAttackRange(Canvas cv) {
        int range = (attackRangeShown == 1) ? 1 : 3;
        int[] ph = worldToHex(player.x, player.y);
        for (int r = -range; r <= range; r++) {
            for (int q = -range; q <= range; q++) {
                int d = hexDist(ph[0], ph[1], ph[0] + q, ph[1] + r);
                if (d < 1 || d > range) continue;
                float[] c = hexToWorld(ph[0] + q, ph[1] + r);
                drawHex(cv, sx(c[0]), sy(c[1]), 0x66ff2233);
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
        float r = 20 + k * 50;
        cv.drawOval(new RectF(sx(runeX) - r, sy(runeY) - r / 2f,
                              sx(runeX) + r, sy(runeY) + r / 2f), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
    }

    private void drawPuffs(Canvas cv) {
        for (Puff p : puffs) {
            float k = p.t / 0.5f;
            paint.setColor(0xFF6a2f8f);
            paint.setAlpha((int) (110 * (1 - k)));
            cv.drawCircle(sx(p.x), sy(p.y) - k * 26, 12 + k * 46, paint);
        }
        paint.setAlpha(255);
    }

    private void drawPlayer(Canvas cv) {
        boolean fl = player.floater.floating();
        float sw = fl ? 45 : 55;
        paint.setColor(0x88000000);
        cv.drawOval(new RectF(sx(player.x) - sw, sy(player.y) - sw / 3f,
                              sx(player.x) + sw, sy(player.y) + sw / 4f), paint);

        Bitmap frame = pickFrame();
        cv.save();
        cv.translate(sx(player.x), sy(player.y));
        if (player.facing < 0) cv.scale(-1, 1);
        if (frame != null) {
            float s = PLAYER_H / frame.getHeight();
            cv.drawBitmap(frame, null, new RectF(
                    -frame.getWidth() * s / 2f, -frame.getHeight() * s,
                     frame.getWidth() * s / 2f, 0), paint);
        }
        cv.restore();

        // overhead HP bar, same style as enemies
        float top = sy(player.y) - PLAYER_H - 26;
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
                // ranged: frames 1-4 then 7-8 (skip 5 & 6)
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

    private void drawEnemy(Canvas cv, Enemy en) {
        float x = sx(en.x), y = sy(en.y);
        boolean fl = en.floater.floating();
        float sw = fl ? 35 : 45;
        paint.setColor(0x88000000);
        cv.drawOval(new RectF(x - sw, y - sw / 3f, x + sw, y + sw / 4f), paint);

        Bitmap frame = pickEnemyFrame(en);
        Paint p = (en.hitFlash > 0) ? tintPaint : paint;
        cv.save();
        cv.translate(x, y);
        if (en.facing < 0) cv.scale(-1, 1);
        if (en.dead) p.setAlpha((int) (255 * (1 - en.deathT / 0.7f)));
        if (frame != null) {
            float s = ENEMY_H / frame.getHeight();
            cv.drawBitmap(frame, null, new RectF(
                    -frame.getWidth() * s / 2f, -frame.getHeight() * s,
                     frame.getWidth() * s / 2f, 0), p);
        } else {
            p.setColor(0xFFaa2233);
            cv.drawOval(new RectF(-30, -ENEMY_H * 0.8f, 30, 0), p);
        }
        p.setAlpha(255);
        cv.restore();

        if (!en.dead && en.hp < en.maxHp) {
            float top = y - ENEMY_H - 26;
            paint.setColor(0xFF330000);
            cv.drawRect(x - 45, top, x + 45, top + 12, paint);
            paint.setColor(0xFFff3344);
            cv.drawRect(x - 45, top, x - 45 + 90f * en.hp / en.maxHp, top + 12, paint);
        }
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

    private void drawBolts(Canvas cv) {
        for (Bolt b : bolts) {
            float dx = b.tgt.x - b.x, dy = (b.tgt.y - ENEMY_H * 0.5f) - b.y;
            float d = (float) Math.hypot(dx, dy);
            if (d < 1) d = 1;
            for (int t = 3; t >= 1; t--) {
                paint.setColor(0xFFff3344);
                paint.setAlpha(60 - t * 15);
                cv.drawCircle(sx(b.x - dx / d * t * 22), sy(b.y - dy / d * t * 22),
                        12 - t * 2, paint);
            }
            paint.setAlpha(220);
            paint.setColor(0xFFff3344);
            cv.drawCircle(sx(b.x), sy(b.y), 13, paint);
            paint.setColor(0xFFffffff);
            cv.drawCircle(sx(b.x), sy(b.y), 6, paint);
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

        // END (left)
        paint.setColor(0x663355ff);
        cv.drawCircle(110, H - 110, 70, paint);
        paint.setColor(0xFFffffff);
        paint.setTextSize(34);
        paint.setTextAlign(Paint.Align.CENTER);
        cv.drawText("END", 110, H - 98, paint);

        // A1 melee (range 1)
        paint.setColor(0x66ff3355);
        cv.drawCircle(W - 110, H - 110, 70, paint);
        paint.setColor(0xFFffffff);
        cv.drawText("A1", W - 110, H - 98, paint);

        // A2 ranged (range 3)
        paint.setColor(0x66ff8833);
        cv.drawCircle(W - 260, H - 110, 70, paint);
        paint.setColor(0xFFffffff);
        cv.drawText("A2", W - 260, H - 98, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(28);
        cv.drawText("D O W N", 24, 48, paint);
        paint.setTextSize(22);
        paint.setColor(0x88ffffff);
        cv.drawText("her hex: fan - A1/A2: range - foe hex x2: hit", 24, 80, paint);
    }

    // ---------- input ----------
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (deadT > 0 || phase != PH_PLAYER) return true;
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;

        int idx = e.getActionIndex();
        float x = e.getX(idx), y = e.getY(idx);

        if (x < 190 && y > H - 190) {                 // END
            endPlayerTurn();
            return true;
        }
        if (x > W - 190 && y > H - 190) {             // A1 melee
            if (!hasAttacked) {
                attackRangeShown = (attackRangeShown == 1) ? 0 : 1;
                hexesShown = false; targetEnemy = null;
            }
            return true;
        }
        if (x > W - 340 && x < W - 190 && y > H - 190) {  // A2 ranged
            if (!hasAttacked) {
                attackRangeShown = (attackRangeShown == 2) ? 0 : 2;
                hexesShown = false; targetEnemy = null;
            }
            return true;
        }

        float wx = camX + x - W / 2f;
        float wy = camY + y - H / 2f;
        int[] th = worldToHex(wx, wy);
        int[] ph = worldToHex(player.x, player.y);
        int dTap = hexDist(ph[0], ph[1], th[0], th[1]);

        // enemy hex tapped?
        Enemy tapped = null;
        for (Enemy en : enemies) {
            if (en.dead) continue;
            int[] eh = worldToHex(en.x, en.y);
            if (eh[0] == th[0] && eh[1] == th[1]) { tapped = en; break; }
        }

        if (tapped != null) {
            int range = (attackRangeShown == 1) ? 1 : (attackRangeShown == 2) ? 3 : 0;
            if (!hasAttacked && range > 0 && dTap <= range) {
                if (targetEnemy != tapped) {
                    targetEnemy = tapped;            // 1st tap: lock (hex floods red)
                } else {                             // 2nd tap: execute
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

        // own hex: toggle move fan
        if (dTap == 0) {
            hexesShown = !hexesShown;
            attackRangeShown = 0;
            return true;
        }

        // green hex: spend move points
        if (hexesShown && moveLeft > 0 && dTap >= 1 && dTap <= moveLeft
                && !hexOccupied(th[0], th[1], null)) {
            float[] c = hexToWorld(th[0], th[1]);
            player.setTarget(c[0], c[1]);
            moveLeft -= dTap;
            runeX = c[0]; runeY = c[1]; runeT = 0;
        }
        return true;
    }
}
