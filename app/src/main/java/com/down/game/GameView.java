package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

    private List<Bitmap> idle;
    private List<Bitmap> attack;

    // tap-to-move
    private int movePointer = -1;
    private float runeX, runeY, runeT = 99;

    // glide mist
    private static class Puff { float x, y, t; }
    private final ArrayList<Puff> puffs = new ArrayList<>();
    private float puffTimer;

    private final Paint paint = new Paint();
    private int W, H;

    public GameView(Context ctx) {
        super(ctx);
        idle   = Sprites.cutSheet(ctx, "sprites/idle.png",   2, 2, 14);
        attack = Sprites.cutSheet(ctx, "sprites/attack.png", 4, 2, 14);
        paint.setFilterBitmap(true);
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

    private void update(float dt) {
        player.update(dt);
        camX += (player.x - camX) * Math.min(1, dt * 6);
        camY += (player.y - camY) * Math.min(1, dt * 6);
        runeT += dt;

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
    }

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
        drawPlayer(cv);
        drawUI(cv);

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
        float bob = moving ? (float) Math.sin(player.bobTime * 10) * 6
                           : (float) Math.sin(player.bobTime * 2.5f) * 3;
        cv.translate(0, bob);
        if (frame != null) {
            float drawH = H * 0.62f;
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
        if (!idle.isEmpty()) {
            return idle.get(((int) (player.bobTime * 2)) % idle.size());
        }
        return null;
    }

    private void drawUI(Canvas cv) {
        paint.setColor(0x66ff3355);
        cv.drawCircle(W - 110, H - 110, 70, paint);
        paint.setColor(0xFFffffff);
        paint.setTextSize(40);
        paint.setTextAlign(Paint.Align.CENTER);
        cv.drawText("ATK", W - 110, H - 96, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(0xFFffffff);
        paint.setTextSize(28);
        cv.drawText("D O W N", 24, 48, paint);
        paint.setTextSize(22);
        paint.setColor(0x88ffffff);
        cv.drawText("tap ground to glide", 24, 80, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
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
