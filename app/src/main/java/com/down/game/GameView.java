package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.List;

public class GameView extends SurfaceView implements Runnable {

    private Thread loop;
    private volatile boolean running;

    private final Player player = new Player();
    private float camX, camY;

    private List<Bitmap> idle;    // 2x2 sheet -> 4 frames
    private List<Bitmap> attack;  // 4x2 sheet -> 8 frames

    private int stickId = -1;
    private float stickSX, stickSY, stickX, stickY;
    private float moveX, moveY;

    private final Paint paint = new Paint();
    private int W, H;

    public GameView(Context ctx) {
        super(ctx);
        idle   = Sprites.cutSheet(ctx, "sprites/idle.png",   2, 2, 6);
        attack = Sprites.cutSheet(ctx, "sprites/attack.png", 4, 2, 6);
        paint.setFilterBitmap(true);
    }

    public void start() { running = true; loop = new Thread(this); loop.start(); }
    public void stop()  { running = false; try { loop.join(); } catch (Exception e) {} }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { W = w; H = h; }

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
        player.update(dt, moveX, moveY);
        camX += (player.x - camX) * Math.min(1, dt * 6);
        camY += (player.y - camY) * Math.min(1, dt * 6);
    }

    private void draw() {
        SurfaceHolder h = getHolder();
        if (!h.getSurface().isValid()) return;
        Canvas cv = h.lockCanvas();
        if (cv == null) return;

        W = cv.getWidth(); H = cv.getHeight();
        cv.drawColor(0xFF120a18);

        drawWorld(cv);
        drawPlayer(cv);
        drawUI(cv);

        h.unlockCanvasAndPost(cv);
    }

    // THE "MASSIVE WORLD": infinite procedural tiles, only visible ones drawn
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
                float sx = tx * TILE - camX + W / 2f;
                float sy = ty * TILE - camY + H / 2f;
                cv.drawRect(sx, sy, sx + TILE + 1, sy + TILE + 1, paint);
            }
        }
    }

    private void drawPlayer(Canvas cv) {
        paint.setColor(0x88000000);
        cv.drawOval(new RectF(W / 2f - 60, H / 2f + 150, W / 2f + 60, H / 2f + 185), paint);

        Bitmap frame = pickFrame();

        cv.save();
        cv.translate(W / 2f, H / 2f + 170);              // feet anchor
        if (player.facing < 0) cv.scale(-1, 1);
        boolean moving = (moveX != 0 || moveY != 0);
        float bob = moving ? (float) Math.sin(player.bobTime * 10) * 6
                           : (float) Math.sin(player.bobTime * 2.5f) * 3;
        cv.translate(0, bob);

        if (frame != null) {
            float drawH = H * 0.62f;
            float s = drawH / frame.getHeight();
            cv.drawBitmap(frame, null, new RectF(
                    -frame.getWidth() * s / 2f, -frame.getHeight() * s,
                     frame.getWidth() * s / 2f, 0), paint);
        } else {
            paint.setColor(0xFFff00ff);                  // placeholder until sheets uploaded
            cv.drawOval(new RectF(-40, -220, 40, 0), paint);
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

        if (stickId != -1) {
            paint.setColor(0x44ffffff);
            cv.drawCircle(stickSX, stickSY, 70, paint);
            paint.setColor(0x88ffffff);
            cv.drawCircle(stickX, stickY, 34, paint);
        }

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(0xFFffffff);
        paint.setTextSize(28);
        cv.drawText("D O W N", 24, 48, paint);
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
                } else if (stickId == -1) {
                    stickId = pid; stickSX = x; stickSY = y; stickX = x; stickY = y;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (e.getPointerId(i) == stickId) {
                        float dx = e.getX(i) - stickSX, dy = e.getY(i) - stickSY;
                        float len = (float) Math.hypot(dx, dy);
                        if (len > 70) { dx = dx / len * 70; dy = dy / len * 70; }
                        moveX = dx / 70; moveY = dy / 70;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pid == stickId) { stickId = -1; moveX = 0; moveY = 0; }
                break;
        }
        return true;
    }
}
