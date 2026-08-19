package com.down.game;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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

    private static final float HEX = 96f, SQUASH = 0.6f, SQRT3 = 1.7320508f;

    public StoryActors() {
        shadowPaint.setColor(0x66000000);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(2.5f);
        bitmapPaint.setFilterBitmap(true);
    }

    public void reset() { actors.clear(); }

    public void add(String name, int q, int r, boolean hidden) {
        for (int i = 0; i < actors.size(); i++) {
            if (actors.get(i).name.equals(name)) return;
        }
        String kind = kindFromName(name);
        StoryActor a = new StoryActor(name, kind, hexX(q, r), hexY(q, r), hidden);
        a.q = q; a.r = r;
        actors.add(a);
    }

    public void show(String name) {
        StoryActor a = get(name);
        if (a != null) a.hidden = false;
    }

    public void hide(String name) {
        StoryActor a = get(name);
        if (a != null) a.hidden = true;
    }

    public void walkTo(String name, int q, int r) {
        StoryActor a = get(name);
        if (a != null) a.moveToHex(q, r, 0.8f);
    }

    public void exitTo(String name, int q, int r) {
        StoryActor a = get(name);
        if (a != null) {
            a.moveToHex(q, r, 0.6f);
            a.hidden = true;
        }
    }

    public void update(float dt) {
        for (int i = 0; i < actors.size(); i++) actors.get(i).update(dt);
    }

    public int size() { return actors.size(); }
    public StoryActor get(int i) { return actors.get(i); }
    public StoryActor get(String name) {
        for (int i = 0; i < actors.size(); i++) {
            if (actors.get(i).name.equals(name)) return actors.get(i);
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
            if (a.hidden) continue;
            drawActor(cv, a, camX, camY, zoom, W, H, t);
        }
    }

    private void drawActor(Canvas cv, StoryActor a, float camX, float camY, float zoom, int W, int H, float t) {
        float sx = (a.x - camX) * zoom + W / 2f;
        float sy = (a.y - camY) * zoom + H / 2f;
        float bob = (float) Math.sin(t * 3f) * 4.5f * zoom;
        sy += bob;
        float h = getHeight(a.kind) * zoom;
        float w = h * 0.55f;
        shadowPaint.setColor(0x66000000);
        cv.drawOval(sx - w * 0.62f, sy - h * 0.12f, sx + w * 0.62f, sy, shadowPaint);
        if (a.idleFrames != null && a.idleFrames.length > 0) {
            int idx = (int) (t * 8f) % a.idleFrames.length;
            Bitmap bmp = a.idleFrames[idx].bmp;
            if (bmp != null && !bmp.isRecycled()) {
                dstRect.set(sx - w, sy - h, sx + w, sy);
                cv.drawBitmap(bmp, null, dstRect, bitmapPaint);
            }
            return;
        }
        float top = sy - h;
        bodyPath.reset();
        bodyPath.moveTo(sx, top);
        bodyPath.cubicTo(sx - w * 0.38f, top + h * 0.22f, sx - w * 0.50f, sy - h * 0.50f, sx - w * 0.46f, sy);
        bodyPath.lineTo(sx + w * 0.46f, sy);
        bodyPath.cubicTo(sx + w * 0.50f, sy - h * 0.50f, sx + w * 0.38f, top + h * 0.22f, sx, top);
        bodyPath.close();
        if ("vel".equals(a.kind)) {
            rimPaint.setColor(0xffb07cff);
            rimPaint.setStrokeWidth(2.5f * zoom);
            cv.drawPath(bodyPath, rimPaint);
        } else if ("ws".equals(a.kind)) {
            rimPaint.setColor(0xff34e3d6);
            rimPaint.setStrokeWidth(1.8f * zoom);
            cv.drawPath(bodyPath, rimPaint);
        }
        bodyPaint.setColor(bodyColor(a.kind));
        cv.drawPath(bodyPath, bodyPaint);
        if ("vel".equals(a.kind)) {
            bodyPaint.setColor(0xffb07cff);
            cv.drawCircle(sx - w * 0.22f, top + h * 0.28f, 3f * zoom, bodyPaint);
            cv.drawCircle(sx + w * 0.22f, top + h * 0.28f, 3f * zoom, bodyPaint);
        }
    }

    public void drawObjective(Canvas cv, float camX, float camY, float zoom, int W, int H, float t) {
        // placeholder - GameView handles objective rendering
    }

    private float getHeight(String kind) {
        switch (kind) {
            case "vel": return 250f;
            case "ws": return 190f;
            default: return 175f;
        }
    }

    private int bodyColor(String kind) {
        switch (kind) {
            case "vel": return 0xff1a0a24;
            case "ws": return 0xff120a0e;
            default: return 0xff101418;
        }
    }

    private static String kindFromName(String name) {
        if ("nilou".equalsIgnoreCase(name) || "vex".equalsIgnoreCase(name)) return "hero";
        if ("vel".equalsIgnoreCase(name)) return "vel";
        if ("ws".equalsIgnoreCase(name)) return "ws";
        return "enemy";
    }

    private static float hexX(int q, int r) { return HEX * SQRT3 * (q + r * 0.5f); }
    private static float hexY(int q, int r) { return HEX * 1.5f * r * SQUASH; }
}
