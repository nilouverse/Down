package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;

public class GameView extends SurfaceView implements Runnable {

    private Thread loop;
    private volatile boolean running;

    private final Player player = new Player();
    private float camX, camY;

    private List<Bitmap> glide, attack, eGlide, eAttack;

    private int movePointer = -1;
    private float runeX, runeY, runeT = 99;

    private static class Puff { float x, y, t; }
    private final ArrayList<Puff> puffs = new ArrayList<>();
    private float puffTimer;

    // combat
    private final ArrayList<Enemy> enemies = new ArrayList<>();
    private int playerHp = 100;
    private float hurtT = 0, deadT = 0, spawnT = 0;
    private boolean playerHitDone = false;
    private static class Dmg { float x, y, t; int val; }
    private final ArrayList<Dmg> dmgs = new ArrayList<>();
    private final Paint tintPaint = new Paint();

    private final Paint paint = new Paint();
    private int W, H;

    public GameView(Context ctx) {
        super(ctx);
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
        spawnWave();
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

    // ---------- combat helpers ----------
    private void spawnWave() { for (int i = 0; i < 3; i++) spawnEnemy(); }

    private void spawnEnemy() {
        Enemy e = new Enemy();
        float a = (float) (Math.random() * Math.PI * 2);
        e.x = player.x + (float) Math.cos(a) * 700;
        e.y = player.y + (float) Math.sin(a) * 500;
        enemies.add(e);
    }

    private void addDmg(float x, float y, int val) {
        Dmg d = new Dmg(); d.x = x; d.y = y; d.val = val; dmgs.add(d);
    }

    private static float dist(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(ax - bx, ay - by);
    }

    private void resetFight() {
        playerHp = 100;
        enemies.clear();
        dmgs.clear();
        spawnWave();
    }

    // ---------- update ----------
    private void update(float dt) {
        if (deadT > 0) {
            deadT -= dt;
            if (deadT <= 0) resetFight();
            return;
        }

        player.update(dt);
        float k = Math.min(1, dt * 6);
        camX += (player.x - camX) * k;
        camY += ((player.y - H * 0.28f) - camY) * k;
        runeT += dt;
        if (hurtT > 0) hurtT -= dt;

        // player swing hits once, mid-swing
        if (player.isAttacking()) {
            if (!playerHitDone && player.attackTime > 0.4f) {
                playerHitDone = true;
                for (Enemy en : enemies) {
                    if (en.dead) continue;
                    float d = dist(player.x, player.y, en.x, en.y);
                    boolean inFront = (en.x - player.x) * player.facing > 0;
                    if (d < 90 || (d < 190 && inFront)) {
                        en.hp -= 12;
                        en.hitFlash = 0.25f;
                        if (d > 1) { en.x += (en.x - player.x) / d * 46; en.y += (en.y - player.y) / d * 46; }
                        addDmg(en.x, en.y - 260, 12);
                        if (en.hp <= 0) en.dead = true;
                    }
                }
            }
        } else {
            playerHitDone = false;
        }

        // enemies
        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy en = enemies.get(i);
            en.update(dt, player.x, player.y);
            if (en.attacking() && !en.struck && en.attackT > 0.45f) {
                en.struck = true;
                if (dist(en.x, en.y, player.x, player.y) < 130) {
                    playerHp -= 10;
                    hurtT = 0.3f;
                    addDmg(player.x, player.y - 300, -10);
                    if (playerHp <= 0) { playerHp = 0; deadT = 2f; }
                }
            }
            if (en.dead && en.deathT > 0.7f) enemies.remove(i);
        }

        // endless pressure
        spawnT += dt;
        if (spawnT > 4f) { spawnT = 0; if (enemies.size() < 5) spawnEnemy(); }

        // glide mist
        if (player.isMoving() && !player.isAttacking()) {
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
        drawRune(cv);
        drawPuffs(cv);

        for (Enemy en : enemies) if (en.y < player.y) drawEnemy(cv, en);
        drawPlayer(cv);
        for (Enemy en : enemies) if (en.y >= player.y) drawEnemy(cv, en);

        drawDmgs(cv);
        drawUI(cv);

        if (hurtT > 0) {
            paint.setColor(0xFFff0000);
            paint.setAlpha((int) (hurtT / 0.3f * 100));
            cv.drawRect(0, 0, W, H, paint);
            paint.setAlpha(255);
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
        paint.setColor(0x88000000);
        cv.drawOval(new RectF(sx(player.x) - 60, sy(player.y) - 20,
                              sx(player.x) + 60, sy(player.y) + 15), paint);
        Bitmap frame = pickFrame();
        cv.save();
        cv.translate(sx(player.x), sy(player.y));
        if (player.facing < 0) cv.scale(-1, 1);
        boolean moving = player.isMoving() && !player.isAttacking();
        float bob = moving ? (float) Math.sin(player.bobTime * 10) * 4 : 0;
        cv.translate(0, bob);
        if (frame != null) {
            float drawH = H * 0.58f;
            float s = drawH / frame.getHeight();
            cv.drawBitmap(frame, null, new RectF(
                    -frame.getWidth() * s / 2f, -frame.getHeight() * s,
                     frame.getWidth() * s / 2f, 0), paint);
        }
        cv.restore();
    }

    private Bitmap pickFrame() {
        if (player.isAttacking() && !attack.isEmpty()) {
            int i = (int) (player.attackTime / player.attackDuration * attack.size());
            if (i >= attack.size()) i = attack.size() - 1;
            return attack.get(i);
        }
        if (!glide.isEmpty()) {
            float fps = player.isMoving() ? 9f : 2.5f;
            return glide.get(((int) (player.bobTime * fps)) % glide.size());
        }
        return null;
    }

    private void drawEnemy(Canvas cv, Enemy en) {
        float x = sx(en.x), y = sy(en.y);
        paint.setColor(0x88000000);
        cv.drawOval(new RectF(x - 50, y - 16, x + 50, y + 12), paint);

        Bitmap frame = pickEnemyFrame(en);
        Paint p = (en.hitFlash > 0) ? tintPaint : paint;
        cv.save();
        cv.translate(x, y);
        if (en.facing < 0) cv.scale(-1, 1);
        if (en.dead) p.setAlpha((int) (255 * (1 - en.deathT / 0.7f)));
        if (frame != null) {
            float drawH = H * 0.42f;
            float s = drawH / frame.getHeight();
            cv.drawBitmap(frame, null, new RectF(
                    -frame.getWidth() * s / 2f, -frame.getHeight() * s,
                     frame.getWidth() * s / 2f, 0), p);
        } else {
            p.setColor(0xFFaa2233);
            cv.drawOval(new RectF(-35, -160, 35, 0), p);
        }
        p.setAlpha(255);
        cv.restore();

        if (!en.dead && en.hp < en.maxHp) {
            float top = y - H * 0.42f - 40;
            paint.setColor(0xFF330000);
            cv.drawRect(x - 45, top, x + 45, top + 12, paint);
            paint.setColor(0xFFff3344);
            cv.drawRect(x - 45, top, x - 45 + 90f * en.hp / en.maxHp, top + 12, paint);
        }
    }

    private Bitmap pickEnemyFrame(Enemy en) {
        if (en.attacking() && !eAttack.isEmpty()) {
            int i = (int) (en.attackT / 0.8f * eAttack.size());
            if (i >= eAttack.size()) i = eAttack.size() - 1;
            return eAttack.get(i);
        }
        if (!eGlide.isEmpty()) {
            float fps = en.dead ? 2f : 6f;
            return eGlide.get(((int) (en.animT * fps)) % eGlide.size());
        }
        return null;
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
        // player HP bar
        paint.setColor(0xFF330000);
        cv.drawRect(W / 2f - 160, 26, W / 2f + 160, 44, paint);
        paint.setColor(0xFFff3344);
        cv.drawRect(W / 2f - 160, 26, W / 2f - 160 + 320f * playerHp / 100, 44, paint);

        paint.setColor(0x66ff3355);
        cv.drawCircle(W - 110, H - 110, 70, paint);
        paint.setColor(0xFFffffff);
        paint.setTextSize(40);
        paint.setTextAlign(Paint.Align.CENTER);
        cv.drawText("ATK", W - 110, H - 96, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(28);
        cv.drawText("D O W N", 24, 48, paint);
        paint.setTextSize(22);
        paint.setColor(0x88ffffff);
        cv.drawText("tap ground to glide", 24, 80, paint);
    }

    // ---------- input ----------
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (deadT > 0) return true;
        int idx = e.getActionIndex();
        int pid = e.getPointerId(idx);
        float x = e.getX(idx), y = e.getY(idx);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (x > W - 190 && y > H - 190) {
                    player.startAttack();
                } else if (movePointer == -1) {
                    movePointer = pid;
                    tap(x, y);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++)
                    if (e.getPointerId(i) == movePointer) tap(e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pid == movePointer) movePointer = -1;
                break;
        }
        return true;
    }

    private void tap(float x, float y) {
        runeX = camX + x - W / 2f;
        runeY = camY + y - H / 2f;
        runeT = 0;
        player.setTarget(runeX, runeY);
    }
}
