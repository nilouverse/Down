package com.down.game;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import java.util.ArrayList;

public class StoryActors {

    private final ArrayList<StoryActor> actors = new ArrayList<>();
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dustPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bodyPath = new Path();
    private final RectF dstRect = new RectF();
    private final Rect frameSrc = new Rect();
    private int seq = 0;

    private static final float HEX = 96f, SQUASH = 0.6f, SQRT3 = 1.7320508f;
    private static final float STRIDE = 126f;   // avg world px per hex — march-speed base
    private static final int MAX_PATH_WP = 10;  // must match StoryActor.MAX_WP

    // arrival dust pool (enemy entrances / landings)
    private static final int DUST_MAX = 24;
    private static final float DUST_LIFE = 0.55f;
    private final float[] duX = new float[DUST_MAX], duY = new float[DUST_MAX],
            duT = new float[DUST_MAX], duS = new float[DUST_MAX];
    private final boolean[] duOn = new boolean[DUST_MAX];

    public StoryActors() {
        shadowPaint.setColor(0x66000000);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(2.5f);
        bitmapPaint.setFilterBitmap(true);
        dustPaint.setColor(0xFFb7a6ab);
    }

    public void reset() {
        actors.clear(); seq = 0;
        for (int i = 0; i < DUST_MAX; i++) duOn[i] = false;
    }

    public void add(String type, int q, int r, String tag, String alias) {
        String name = alias != null ? alias : (type + "#" + (seq++));
        for (int i = 0; i < actors.size(); i++) {
            if (actors.get(i).name.equals(name)) return;
        }
        String kind;
        if ("ambient".equals(tag)) kind = "ambient";
        else if ("npc".equals(tag)) kind = "npc";
        else kind = kindFromName(type);
        StoryActor a = new StoryActor(name, kind, type, tag, hexX(q, r), hexY(q, r), false);
        a.alias = alias;
        a.q = q; a.r = r;
        if (a.isEnemy()) a.snapFacing(-1f);   // F4: story enemies face the player (west)
        actors.add(a);
    }

    public void spawn(String type, int q, int r) { add(type, q, r, null, null); }

    public void despawn(String key) {
        for (int i = actors.size() - 1; i >= 0; i--) {
            StoryActor a = actors.get(i);
            if (a.name.equals(key) || a.type.equals(key)) { actors.remove(i); return; }
        }
    }

    public void hideStandins() {
        for (int i = 0; i < actors.size(); i++) {
            StoryActor a = actors.get(i);
            if (a.isEnemy()) a.hidden = true;
        }
    }

    public void show(String key) { StoryActor a = get(key); if (a != null) a.hidden = false; }
    public void hide(String key) { StoryActor a = get(key); if (a != null) a.hidden = true; }

    public void walkTo(String key, int q, int r, float dur) {
        walkPath(key, q, r, null, null, 0, dur, 0f, 0f, false);
    }

    public void walkVia(String key, int q, int r, int vq, int vr, float dur) {
        int[] vqs = { vq }; int[] vrs = { vr };
        walkPath(key, q, r, vqs, vrs, 1, dur, 0f, 0f, false);
    }

    public void glideTo(String key, int q, int r, float dur) {
        walkPath(key, q, r, null, null, 0, dur, 0f, 0f, true);
    }

    // Unified path mover. dur <= 0 → march duration from speed (speedHex > 0)
    // or from the actor kind's natural march speed. delay = stagger seconds.
    public void walkPath(String key, int q, int r, int[] vqs, int[] vrs, int nv,
                         float dur, float speedHex, float delay, boolean glide) {
        StoryActor a = get(key);
        if (a == null) return;
        int n = 2 + (nv > 0 ? nv : 0);
        if (n > MAX_PATH_WP) n = MAX_PATH_WP;
        float[] xs = new float[n], ys = new float[n];
        xs[0] = a.x; ys[0] = a.y;
        int k = 1;
        for (int i = 0; i < nv && k < n - 1; i++) {
            xs[k] = hexX(vqs[i], vrs[i]);
            ys[k] = hexY(vqs[i], vrs[i]);
            k++;
        }
        xs[n - 1] = hexX(q, r);
        ys[n - 1] = hexY(q, r);
        a.q = q; a.r = r;
        float d = dur;
        if (d <= 0f) {
            float sp = speedHex > 0f ? speedHex : marchSpeed(a.kind);
            d = pathDuration(xs, ys, n, sp);
        }
        a.startPath(xs, ys, n, d, delay, glide);
    }

    private static float pathDuration(float[] xs, float[] ys, int n, float speedHex) {
        float len = 0f;
        for (int i = 0; i < n - 1; i++) {
            float dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i];
            len += (float) Math.sqrt(dx * dx + dy * dy);
        }
        float d = len / Math.max(0.05f, speedHex * STRIDE);
        return d < 0.15f ? 0.15f : d;
    }

    private static float marchSpeed(String kind) {
        if ("beast".equals(kind)) return 2.6f;
        if ("soldier".equals(kind)) return 2.2f;
        if ("vel".equals(kind)) return 2.2f;
        if ("npc".equals(kind)) return 1.6f;
        if ("ambient".equals(kind)) return 1.2f;
        return 2.0f;
    }

    public void exitTo(String key, int q, int r) {
        StoryActor a = get(key);
        if (a == null) return;
        a.exitPending = true;
        walkPath(key, q, r, null, null, 0, 0f, 0f, 0f, false);   // march out visibly, hide on arrival
    }

    public void setFacing(String key, float f) {
        StoryActor a = get(key);
        if (a != null) a.turnTo(f);
    }

    public boolean isWalking(String key) {
        StoryActor a = get(key);
        return a != null && a.isWalking();
    }

    public void update(float dt) {
        for (int i = 0; i < actors.size(); i++) {
            StoryActor a = actors.get(i);
            a.update(dt);
            if (a.arrived) {
                a.arrived = false;
                if (!a.hidden && a.isEnemy())
                    spawnDust(a.x, a.y, "beast".equals(a.kind) ? 10 : 6);
            }
        }
        for (int j = 0; j < DUST_MAX; j++) {
            if (!duOn[j]) continue;
            duT[j] += dt;
            if (duT[j] > DUST_LIFE) duOn[j] = false;
        }
    }

    private void spawnDust(float x, float y, int n) {
        for (int k = 0; k < n; k++) {
            for (int j = 0; j < DUST_MAX; j++) {
                if (duOn[j]) continue;
                duX[j] = x + (float) (Math.random() * 70 - 35);
                duY[j] = y + (float) (Math.random() * 18 - 9);
                duT[j] = 0f;
                duS[j] = 0.7f + (float) Math.random() * 0.7f;
                duOn[j] = true;
                break;
            }
        }
    }

    public int size() { return actors.size(); }
    public StoryActor get(int i) { return actors.get(i); }

    public StoryActor get(String key) {
        for (int i = 0; i < actors.size(); i++) {
            StoryActor a = actors.get(i);
            if (a.name.equals(key) || a.type.equals(key)) return a;
        }
        return null;
    }

    public StoryActor getAt(int q, int r) {
        for (int i = 0; i < actors.size(); i++) {
            StoryActor a = actors.get(i);
            if (!a.hidden && a.q == q && a.r == r) return a;
        }
        return null;
    }

    public void draw(Canvas cv, float camX, float camY, float zoom, int W, int H, float t) {
        for (int i = 0; i < actors.size(); i++) {
            StoryActor a = actors.get(i);
            if (a.hidden || "hero".equals(a.kind)) continue;
            drawActor(cv, a, camX, camY, zoom, W, H, t);
        }
        drawDust(cv, camX, camY, zoom, W, H);
    }

    private void drawDust(Canvas cv, float camX, float camY, float zoom, int W, int H) {
        for (int j = 0; j < DUST_MAX; j++) {
            if (!duOn[j]) continue;
            float k = duT[j] / DUST_LIFE;
            float px = (duX[j] - camX) * zoom + W / 2f;
            float py = (duY[j] - camY) * zoom + H / 2f - k * 22f * zoom;
            dustPaint.setAlpha((int) (120 * (1f - k)));
            cv.drawCircle(px, py, (10f + k * 30f) * duS[j] * zoom, dustPaint);
        }
        dustPaint.setAlpha(255);
    }

    private void drawActor(Canvas cv, StoryActor a, float camX, float camY, float zoom, int W, int H, float t) {
        float sx = (a.x - camX) * zoom + W / 2f;
        float sy = (a.y - camY) * zoom + H / 2f - a.lift * zoom;
        float h = getHeight(a.kind) * zoom;
        float w = h * 0.55f;
        float shrink = 1f - (a.lift / 150f) * 0.35f;
        shadowPaint.setColor(0x66000000);
        shadowPaint.setAlpha((int) (0x66 * shrink));
        cv.drawOval(sx - w * 0.62f * shrink, sy + a.lift * zoom - h * 0.12f - 18f * zoom,
                sx + w * 0.62f * shrink, sy + a.lift * zoom - 18f * zoom, shadowPaint);
        shadowPaint.setAlpha(0x66);

        cv.save();
        cv.translate(sx, sy);
        float fc = a.facingDisplay();
        if (fc != 0f) cv.scale(fc, 1f);
        boolean moving = a.movingNow();
        float br = moving ? 0f : (float) Math.sin(a.bobT * 1.7f);
        if (br != 0f) cv.scale(1f - 0.02f * br, 1f + 0.035f * br);

        Frame[] pool = (moving && a.glideFrames != null && a.glideFrames.length > 1)
                ? a.glideFrames : a.idleFrames;
        if (pool != null && pool.length > 0) {
            if (moving && a.glideFrames != null && a.glideFrames.length > 1) {
                // stride-synced walk cycle: phase advances with actual movement speed
                float pos = a.animPhase * pool.length;
                int n = pool.length;
                int i0 = ((int) pos) % n; if (i0 < 0) i0 += n;
                int i1 = (i0 + 1) % n;
                float fr = pos - (int) pos;
                float k = (fr - 0.65f) / 0.35f;
                if (k < 0) k = 0;
                if (k > 1) k = 1;
                drawFr(cv, pool[i0], h, 255);
                if (k > 0.02f) drawFr(cv, pool[i1], h, (int) (k * 255));
            } else {
                // idle: per-actor phase (desynced breathing cast)
                int idx = (int) (a.bobT * 8f) % pool.length; if (idx < 0) idx += pool.length;
                drawFr(cv, pool[idx], h, 255);
            }
            cv.restore();
            return;
        }

        float top = -h;
        bodyPath.reset();
        bodyPath.moveTo(0, top);
        bodyPath.cubicTo(-w * 0.38f, top + h * 0.22f, -w * 0.50f, -h * 0.50f, -w * 0.46f, 0);
        bodyPath.lineTo(w * 0.46f, 0);
        bodyPath.cubicTo(w * 0.50f, -h * 0.50f, w * 0.38f, top + h * 0.22f, 0, top);
        bodyPath.close();

        int rim = rimColor(a.kind);
        if (rim != 0) {
            rimPaint.setColor(rim);
            rimPaint.setStrokeWidth(("vel".equals(a.kind) ? 2.5f : 1.8f) * zoom);
            cv.drawPath(bodyPath, rimPaint);
        }
        bodyPaint.setColor(bodyColor(a.kind));
        cv.drawPath(bodyPath, bodyPaint);

        if ("vel".equals(a.kind)) {
            bodyPaint.setColor(0xffb07cff);
            cv.drawCircle(-w * 0.22f, top + h * 0.28f, 3f * zoom, bodyPaint);
            cv.drawCircle(w * 0.22f, top + h * 0.28f, 3f * zoom, bodyPaint);
        }
        if ("beast".equals(a.kind)) {
            bodyPaint.setColor(0xff140a08);
            cv.drawOval(-w * 0.9f, -h * 0.42f, w * 0.9f, -h * 0.10f, bodyPaint);
        }
        cv.restore();
    }

    private void drawFr(Canvas cv, Frame f, float h, int alpha) {
        if (f == null) return;
        Bitmap bmp = f.bmp;
        if (bmp == null || bmp.isRecycled()) return;
        float s = h / f.ref;
        bitmapPaint.setAlpha(alpha);
        if (f.vCrop) {
            frameSrc.set(0, f.top, bmp.getWidth(), f.top + f.ch);
            dstRect.set(-bmp.getWidth() * s / 2f, -f.ch * s, bmp.getWidth() * s / 2f, 0);
        } else if (f.cCenter) {
            int wl = Math.max(0, f.rgt - f.ww);
            int wr = f.rgt;
            if (wl >= wr || f.top + f.ch > bmp.getHeight()) {
                frameSrc.set(0, 0, bmp.getWidth(), bmp.getHeight());
                dstRect.set(-bmp.getWidth() * s / 2f, -bmp.getHeight() * s,
                        bmp.getWidth() * s / 2f, 0);
            } else {
                frameSrc.set(wl, f.top, wr, f.top + f.ch);
                float right = f.ww * s / 2f;
                dstRect.set(right - (wr - wl) * s, -f.ch * s, right, 0);
            }
        } else {
            frameSrc.set(0, 0, bmp.getWidth(), bmp.getHeight());
            dstRect.set(-bmp.getWidth() * s / 2f, -bmp.getHeight() * s,
                    bmp.getWidth() * s / 2f, 0);
        }
        cv.drawBitmap(bmp, frameSrc, dstRect, bitmapPaint);
        bitmapPaint.setAlpha(255);
    }

    private float getHeight(String kind) {
        if ("vel".equals(kind)) return 250f;
        if ("ws".equals(kind)) return 190f;
        if ("npc".equals(kind)) return 195f;
        if ("soldier".equals(kind)) return 200f;
        if ("ambient".equals(kind)) return 165f;
        if ("beast".equals(kind)) return 260f;
        return 175f;
    }

    private int rimColor(String kind) {
        if ("vel".equals(kind)) return 0xffb07cff;
        if ("ws".equals(kind)) return 0xff34e3d6;
        if ("npc".equals(kind)) return 0xffb7a6ab;
        if ("soldier".equals(kind)) return 0xff8a9096;
        if ("ambient".equals(kind)) return 0xff6f8f6a;
        if ("beast".equals(kind)) return 0xff7a1a10;
        return 0;
    }

    private int bodyColor(String kind) {
        if ("vel".equals(kind)) return 0xff1a0a24;
        if ("ws".equals(kind)) return 0xff120a0e;
        if ("soldier".equals(kind)) return 0xff14161a;
        if ("ambient".equals(kind)) return 0xff0d120c;
        if ("beast".equals(kind)) return 0xff140a08;
        return 0xff101418;
    }

    private static String kindFromName(String name) {
        if ("nilou".equalsIgnoreCase(name) || "vex".equalsIgnoreCase(name)) return "hero";
        if ("sabrina".equalsIgnoreCase(name)) return "soldier";
        if ("vel".equalsIgnoreCase(name)) return "vel";
        if ("ws".equalsIgnoreCase(name)) return "ws";
        if ("soldier".equalsIgnoreCase(name)) return "soldier";
        if ("beast".equalsIgnoreCase(name)) return "beast";
        return "enemy";
    }

    private static float hexX(int q, int r) { return HEX * SQRT3 * (q + r * 0.5f); }
    private static float hexY(int q, int r) { return HEX * 1.5f * r * SQUASH; }
}
