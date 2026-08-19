package com.down.game;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import java.util.ArrayList;

public class SceneMap {
    public static final int SHAPE_FIELD = 0, SHAPE_BRIDGE = 1, SHAPE_STREET = 2,
            SHAPE_CAMP = 3, SHAPE_RUN = 4;

    public static final float SQUASH = 0.6f;
    public static final float HEX = 96f;
    private static final float TILE = 192f;
    private static final float TH = TILE * SQUASH;

    public ArrayList<SceneActor> actors = new ArrayList<>();
    public ArrayList<Prop> props = new ArrayList<>();
    public ArrayList<Crack> cracks = new ArrayList<>();

    private Bitmap bgBmp;
    private Canvas bgCanvas;

    private Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint objPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint shadowPaint = new Paint();
    private Path path = new Path();
    private Rect srcRect = new Rect();
    private RectF dstRect = new RectF();
    private ArrayList<Object> sortedList = new ArrayList<>();

    public Frame[] propFrames; // injected by GameView

    public float inkFade = 0f;
    private int shapeType = SHAPE_FIELD;
    private int groundColor = Color.BLACK;
    private int builtW, builtH;

    public static class Prop {
        public String type;
        public float x, y;
        public int frameIdx;
        public Prop(String type, float x, float y, int frameIdx) {
            this.type = type; this.x = x; this.y = y; this.frameIdx = frameIdx;
        }
    }

    public static class Crack {
        public float x1, y1, x2, y2;
        public Crack(float x1, float y1, float x2, float y2) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }
    }

    public void reset(int groundColor, String name) {
        props.clear();
        cracks.clear();
        actors.clear();
        if (name.contains("Ashen")) shapeType = SHAPE_FIELD;
        else if (name.contains("Descent")) shapeType = SHAPE_BRIDGE;
        else if (name.contains("Falling")) shapeType = SHAPE_STREET;
        else if (name.contains("Courtyard") || name.contains("Reunion")
                || name.contains("Wave") || name.contains("Last Act")) shapeType = SHAPE_CAMP;
        else if (name.contains("Run")) shapeType = SHAPE_RUN;
        else shapeType = SHAPE_FIELD;
        this.groundColor = groundColor;
    }

    private static float hexX(int q, int r) { return HEX * (float) Math.sqrt(3) * (q + r / 2f); }
    private static float hexY(int r) { return HEX * 1.5f * r * SQUASH; }

    public void addProp(String type, int q, int r) {
        int idx = 0;
        if (type.equals("spire")) idx = 0;
        else if (type.equals("wall")) idx = 1;
        else if (type.equals("rubble")) idx = 2;
        else if (type.equals("bonepillar")) idx = 3;
        else if (type.equals("bones")) idx = 4;
        else if (type.equals("barricade")) idx = 5;
        else if (type.equals("street")) idx = 6;
        props.add(new Prop(type, hexX(q, r), hexY(r), idx));
    }

    public void addCrack(int q1, int r1, int q2, int r2) {
        cracks.add(new Crack(hexX(q1, r1), hexY(r1), hexX(q2, r2), hexY(r2)));
    }

    public void addActor(String id, String type, int q, int r, boolean hidden) {
        actors.add(new SceneActor(id, type, hexX(q, r), hexY(r), q, r, hidden));
    }

    public SceneActor getActor(String id) {
        for (int i = 0; i < actors.size(); i++) {
            SceneActor a = actors.get(i);
            if (a.id.equals(id)) return a;
        }
        return null;
    }

    public static boolean isEnemyActor(String type) {
        return type.startsWith("t") || type.startsWith("i")
                || type.startsWith("d") || type.startsWith("e");
    }

    public void clearEnemyActors() {
        for (int i = actors.size() - 1; i >= 0; i--) {
            if (isEnemyActor(actors.get(i).type)) actors.remove(i);
        }
    }

    public boolean isWalkable(float x, float y) {
        switch (shapeType) {
            case SHAPE_FIELD: return Math.abs(x) < 1200 && Math.abs(y) < 800;
            case SHAPE_BRIDGE: return Math.abs(x) < 1200 && Math.abs(y) < 300;
            case SHAPE_STREET: return x > -400 && x < 1200 && Math.abs(y) < 400;
            case SHAPE_CAMP: return (x * x + y * y) < 800 * 800;
            case SHAPE_RUN: return Math.abs(x) < 1200 && Math.abs(y) < 250;
        }
        return true;
    }

    public void build(int W, int H, int quality) {
        if (W < 16 || H < 16) return; // guard: size not known yet
        if (bgBmp != null) { bgBmp.recycle(); bgBmp = null; }
        builtW = W; builtH = H;
        bgBmp = Bitmap.createBitmap(W / 2, H / 2, Bitmap.Config.RGB_565);
        bgCanvas = new Canvas(bgBmp);

        bgPaint.setShader(null);
        bgPaint.setColor(0xFF000000 | (groundColor & 0xFFFFFF));
        bgPaint.setStyle(Paint.Style.FILL);
        bgCanvas.drawRect(0, 0, W / 2, H / 2, bgPaint);

        bgPaint.setColor(0xFF140a14);
        path.reset();
        path.moveTo(0, H / 6f);
        for (int i = 0; i <= 12; i++) {
            float px = (i / 12f) * (W / 2);
            float py = H / 6f + (i % 2 == 0 ? -25 : 25);
            path.lineTo(px, py);
        }
        path.lineTo(W / 2, 0);
        path.lineTo(0, 0);
        path.close();
        bgCanvas.drawPath(path, bgPaint);

        if (quality > 0) {
            RadialGradient vignette = new RadialGradient(W / 4f, H / 4f, W / 2f,
                    new int[] { 0, 0x80000000 }, new float[] { 0f, 1f }, Shader.TileMode.CLAMP);
            bgPaint.setShader(vignette);
            bgCanvas.drawRect(0, 0, W / 2, H / 2, bgPaint);
            bgPaint.setShader(null);
        }
    }

    public void draw(Canvas cv, float camX, float camY, float zoom, int W, int H, int quality, float t) {
        if (bgBmp != null) {
            bgPaint.setFilterBitmap(true);
            dstRect.set(0, 0, W, H);
            cv.drawBitmap(bgBmp, null, dstRect, bgPaint);
            bgPaint.setFilterBitmap(false);
        }
        objPaint.setStyle(Paint.Style.STROKE);
        objPaint.setColor(0x64000000);
        objPaint.setStrokeWidth(4 * zoom);
        for (int i = 0; i < cracks.size(); i++) {
            Crack c = cracks.get(i);
            cv.drawLine(sx(c.x1, camX, zoom, W), sy(c.y1, camY, zoom, H),
                        sx(c.x2, camX, zoom, W), sy(c.y2, camY, zoom, H), objPaint);
        }
        objPaint.setStyle(Paint.Style.FILL);
    }

    public ArrayList<Object> getSortedDrawables() {
        sortedList.clear();
        for (int i = 0; i < props.size(); i++) sortedList.add(props.get(i));
        for (int i = 0; i < actors.size(); i++) {
            SceneActor a = actors.get(i);
            if (!a.hidden) sortedList.add(a);
        }
        for (int i = 0; i < sortedList.size(); i++) {
            for (int j = i + 1; j < sortedList.size(); j++) {
                if (drawableY(sortedList.get(j)) < drawableY(sortedList.get(i))) {
                    Object tmp = sortedList.get(i);
                    sortedList.set(i, sortedList.get(j));
                    sortedList.set(j, tmp);
                }
            }
        }
        return sortedList;
    }

    public float drawableY(Object obj) {
        if (obj instanceof Prop) return ((Prop) obj).y;
        if (obj instanceof SceneActor) return ((SceneActor) obj).y;
        return 0;
    }

    public void drawDrawable(Canvas cv, Object obj, float camX, float camY, float zoom, int W, int H, float t) {
        if (obj instanceof Prop) drawProp(cv, (Prop) obj, camX, camY, zoom, W, H);
        else if (obj instanceof SceneActor) drawActor(cv, (SceneActor) obj, camX, camY, zoom, W, H, t);
    }

    private static boolean isLarge(String type) {
        return type.equals("spire") || type.equals("wall") || type.equals("bonepillar")
                || type.equals("barricade") || type.equals("street");
    }

    private void drawProp(Canvas cv, Prop p, float camX, float camY, float zoom, int W, int H) {
        if (propFrames == null || propFrames.length == 0) return;
        Frame f = propFrames[p.frameIdx % propFrames.length];
        if (f == null || f.bmp == null || f.ch <= 0) return;

        float sx = sx(p.x, camX, zoom, W);
        float sy = sy(p.y, camY, zoom, H);

        float targetH = isLarge(p.type) ? TH * 2.43f : TH * 1.0f;
        float s = (targetH / f.ch) * zoom;
        float dw = f.cw * s;
        float dh = f.ch * s;

        shadowPaint.setColor(0x60000000);
        cv.drawCircle(sx, sy, dw * 0.28f, shadowPaint);

        srcRect.set(f.left, f.top, f.left + f.cw, f.top + f.ch);
        dstRect.set(sx - dw / 2f, sy - dh, sx + dw / 2f, sy);
        cv.drawBitmap(f.bmp, srcRect, dstRect, bgPaint);
    }

    private void drawActor(Canvas cv, SceneActor a, float camX, float camY, float zoom, int W, int H, float t) {
        float baseSy = sy(a.y, camY, zoom, H);
        float sx = sx(a.x, camX, zoom, W);
        float bob = (float) Math.sin(t * 2f + a.bobPhase) * 5f * zoom;
        float sy = baseSy - bob;

        shadowPaint.setColor(0x60000000);
        cv.drawCircle(sx, baseSy, 18 * zoom, shadowPaint);

        objPaint.setStyle(Paint.Style.FILL);
        path.reset();
        if (a.type.equals("vel")) {
            objPaint.setColor(0xFFb07cff);
            path.moveTo(sx, sy - 80 * zoom);
            path.lineTo(sx - 20 * zoom, sy);
            path.lineTo(sx + 20 * zoom, sy);
            path.close();
            cv.drawPath(path, objPaint);
            objPaint.setStyle(Paint.Style.STROKE);
            objPaint.setStrokeWidth(3 * zoom);
            objPaint.setColor(0xFF34e3d6);
            cv.drawPath(path, objPaint);
            objPaint.setStyle(Paint.Style.FILL);
        } else if (isEnemyActor(a.type) || a.type.equals("ws")) {
            objPaint.setColor(a.type.equals("ws") ? 0xFFb7a6ab : 0xFFb3102a);
            path.moveTo(sx, sy - 60 * zoom);
            path.lineTo(sx - 15 * zoom, sy);
            path.lineTo(sx + 15 * zoom, sy);
            path.close();
            cv.drawPath(path, objPaint);
        } else {
            objPaint.setColor(0xFFefe6dd);
            path.moveTo(sx, sy - 70 * zoom);
            path.lineTo(sx - 18 * zoom, sy);
            path.lineTo(sx + 18 * zoom, sy);
            path.close();
            cv.drawPath(path, objPaint);
        }
    }

    public void drawObjective(Canvas cv, float objX, float objY, float camX, float camY, float zoom, int W, int H, float t) {
        float sx = sx(objX, camX, zoom, W);
        float sy = sy(objY, camY, zoom, H);

        if (sx < 50 || sx > W - 50 || sy < 50 || sy > H - 50) {
            float cx = W / 2f, cy = H / 2f;
            float angle = (float) Math.atan2(sy - cy, sx - cx);
            float ax = cx + (float) Math.cos(angle) * (W / 3f);
            float ay = cy + (float) Math.sin(angle) * (H / 3f);
            cv.save();
            cv.translate(ax, ay);
            cv.rotate(angle * 180f / (float) Math.PI);
            objPaint.setColor(0xFF34e3d6);
            objPaint.setStyle(Paint.Style.FILL);
            path.reset();
            path.moveTo(14 * zoom, 0);
            path.lineTo(-10 * zoom, -10 * zoom);
            path.lineTo(-10 * zoom, 10 * zoom);
            path.close();
            cv.drawPath(path, objPaint);
            cv.restore();
        } else {
            float pulse = 0.8f + 0.2f * (float) Math.sin(t * 4f);
            objPaint.setStyle(Paint.Style.FILL);
            objPaint.setColor(Color.argb((int) (150 * pulse), 0x34, 0xe3, 0xd6));
            cv.drawCircle(sx, sy, 15 * zoom * pulse, objPaint);
            objPaint.setColor(0xFF34e3d6);
            objPaint.setStyle(Paint.Style.STROKE);
            objPaint.setStrokeWidth(2 * zoom);
            cv.drawCircle(sx, sy, 20 * zoom, objPaint);
            objPaint.setStyle(Paint.Style.FILL);
            objPaint.setColor(Color.argb(80, 0x34, 0xe3, 0xd6));
            cv.drawRect(sx - 10 * zoom, sy - 200 * zoom, sx + 10 * zoom, sy, objPaint);
        }
    }

    public void update(float dt) {
        if (inkFade > 0) {
            inkFade -= dt * 3f;
            if (inkFade < 0) inkFade = 0;
        }
        for (int i = 0; i < actors.size(); i++) actors.get(i).update(dt);
    }

    public void triggerTransition() { inkFade = 1f; }

    public void drawInkFade(Canvas cv, int W, int H) {
        if (inkFade > 0) {
            objPaint.setColor(Color.argb((int) (inkFade * 255), 0, 0, 0));
            objPaint.setStyle(Paint.Style.FILL);
            cv.drawRect(0, 0, W, H, objPaint);
        }
    }

    private float sx(float wx, float camX, float zoom, int W) { return (wx - camX) * zoom + W / 2f; }
    private float sy(float wy, float camY, float zoom, int H) { return (wy - camY) * zoom + H / 2f; }
}
