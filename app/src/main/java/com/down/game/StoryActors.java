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
    private final Path bodyPath = new Path();
    private final RectF dstRect = new RectF();
    private final Rect frameSrc = new Rect();
    private int seq = 0;

    private static final float HEX = 96f, SQUASH = 0.6f, SQRT3 = 1.7320508f;

    public StoryActors() {
        shadowPaint.setColor(0x66000000);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(2.5f);
        bitmapPaint.setFilterBitmap(true);
    }

    public void reset() { actors.clear(); seq = 0; }

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
        StoryActor a = get(key);
        if (a != null) a.moveToHex(q, r, dur);
    }

    public void exitTo(String key, int q, int r) {
        StoryActor a = get(key);
        if (a != null) { a.moveToHex(q, r, 0.6f); a.hidden = true; }
    }

    public void setFacing(String key, float f) {
        StoryActor a = get(key);
        if (a != null) a.facing = f;
    }

    public boolean isWalking(String key) {
        StoryActor a = get(key);
        return a != null && a.walking;
    }

    public void update(float dt) {
        for (int i = 0; i < actors.size(); i++) actors.get(i).update(dt);
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
    }

    private void drawActor(Canvas cv, StoryActor a, float camX, float camY, float zoom, int W, int H, float t) {
        float sx = (a.x - camX) * zoom + W / 2f;
        float sy = (a.y - camY) * zoom + H / 2f;
        float bob = (float) Math.sin(t * 3f + a.bobT) * 4.5f * zoom;
        if ("ambient".equals(a.kind)) {
            bob = (((int) (t * 9f + a.bobT * 7f)) % 2 == 0 ? 2.5f : -1.5f) * zoom;
        }
        sy += bob;
        float h = getHeight(a.kind) * zoom;
        float w = h * 0.55f;
        shadowPaint.setColor(0x66000000);
        cv.drawOval(sx - w * 0.62f, sy - h * 0.12f, sx + w * 0.62f, sy, shadowPaint);

        cv.save();
        cv.translate(sx, sy);
        cv.scale(a.facing, 1f);

        if (a.idleFrames != null && a.idleFrames.length > 0) {
            int idx = (int) (t * 8f) % a.idleFrames.length;
            Frame f = a.idleFrames[idx];
            Bitmap bmp = f.bmp;
            if (bmp != null && !bmp.isRecycled()) {
                float s = h / f.ref;
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
            // quadruped read: low wide body hint
            bodyPaint.setColor(0xff140a08);
            cv.drawOval(-w * 0.9f, -h * 0.42f, w * 0.9f, -h * 0.10f, bodyPaint);
        }
        cv.restore();
    }

    private float getHeight(String kind) {
        if ("vel".equals(kind)) return 250f;
        if ("ws".equals(kind)) return 190f;
        if ("npc".equals(kind)) return 195f;
        if ("ambient".equals(kind)) return 165f;
        if ("beast".equals(kind)) return 260f;
        return 175f;
    }

    private int rimColor(String kind) {
        if ("vel".equals(kind)) return 0xffb07cff;
        if ("ws".equals(kind)) return 0xff34e3d6;
        if ("npc".equals(kind)) return 0xffb7a6ab;
        if ("ambient".equals(kind)) return 0xff6f8f6a;
        if ("beast".equals(kind)) return 0xff7a1a10;
        return 0;
    }

    private int bodyColor(String kind) {
        if ("vel".equals(kind)) return 0xff1a0a24;
        if ("ws".equals(kind)) return 0xff120a0e;
        if ("ambient".equals(kind)) return 0xff0d120c;
        if ("beast".equals(kind)) return 0xff140a08;
        return 0xff101418;
    }

    private static String kindFromName(String name) {
        if ("nilou".equalsIgnoreCase(name) || "vex".equalsIgnoreCase(name)) return "hero";
        if ("vel".equalsIgnoreCase(name)) return "vel";
        if ("ws".equalsIgnoreCase(name)) return "ws";
        if ("beast".equalsIgnoreCase(name)) return "beast";
        return "enemy";
    }

    private static float hexX(int q, int r) { return HEX * SQRT3 * (q + r * 0.5f); }
    private static float hexY(int q, int r) { return HEX * 1.5f * r * SQUASH; }
}
