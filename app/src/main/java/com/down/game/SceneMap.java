package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import java.util.ArrayList;

public class SceneMap {

    public static final int KIND_ASHEN = 0, KIND_DESCENT = 1, KIND_CITY = 2,
            KIND_COURTYARD = 3, KIND_RUN = 4;

    private static final float HEX = 96f, SQUASH = 0.6f, SQRT3 = 1.7320508f;

    public static class Prop {
        public String type;
        public int q, r;
        public float x, y;
        public Frame frame;
        public float scale = 1f;
        public boolean blocking;
        public float drawY() { return y + 24f; }
    }

    public static class Crack {
        public int q1, r1, q2, r2;
    }

    private final ArrayList<Prop> props = new ArrayList<>();
    private final ArrayList<Crack> cracks = new ArrayList<>();
    private boolean[] walk;
    private int groundColor, sceneKind;
    private String sceneName;
    private Bitmap bg, fg;
    private Canvas bgCanvas, fgCanvas;
    private float worldMinX, worldMinY, worldMaxX, worldMaxY, pxPerWorld;
    private final Matrix drawMatrix = new Matrix();
    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint groundPaint = new Paint();
    private final Paint crackPaint = new Paint();
    private final Paint lightPaint = new Paint();
    private final Path crackPath = new Path();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private static Frame[] smallPropFrames, largePropFrames;
    private final int[] hexOut = new int[2];
    private Context ctx;

    public SceneMap() {
        bgPaint.setFilterBitmap(true);
        fgPaint.setFilterBitmap(true);
        crackPaint.setStyle(Paint.Style.STROKE);
        crackPaint.setStrokeCap(Paint.Cap.ROUND);
        crackPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void init(Context c) {
        ctx = c;
    }

    public void begin(String name, int ground) {
        props.clear();
        cracks.clear();
        walk = null;
        sceneName = name;
        groundColor = ground;
        if (name.contains("Ashen")) sceneKind = KIND_ASHEN;
        else if (name.contains("Descent")) sceneKind = KIND_DESCENT;
        else if (name.contains("Falling") || name.contains("City")) sceneKind = KIND_CITY;
        else if (name.contains("Courtyard") || name.contains("Reunion")
                || name.contains("Wave") || name.contains("Last Act")) sceneKind = KIND_COURTYARD;
        else if (name.contains("Run")) sceneKind = KIND_RUN;
        else sceneKind = KIND_ASHEN;
    }

    public void crack(int q1, int r1, int q2, int r2) {
        Crack c = new Crack();
        c.q1 = q1; c.r1 = r1; c.q2 = q2; c.r2 = r2;
        cracks.add(c);
    }

    public void prop(String type, int q, int r) {
        Prop p = new Prop();
        p.type = type; p.q = q; p.r = r;
        p.x = hexX(q, r); p.y = hexY(q, r);
        p.frame = getPropFrame(type);
        p.blocking = isBlocking(type);
        if ("spire".equals(type) || "wall".equals(type) || "bonepillar".equals(type)) p.scale = 1.35f;
        props.add(p);
    }

    public void tick(float dt) {
        if (walk == null && bg == null) compile();
    }

    private void compile() {
        walk = buildWalkMask(sceneKind, props);
        final int[] bounds = sceneBounds();
        final int minQ = bounds[0], maxQ = bounds[1], minR = bounds[2], maxR = bounds[3];
        float x0 = hexX(minQ, minR), x1 = hexX(minQ, maxR);
        float x2 = hexX(maxQ, minR), x3 = hexX(maxQ, maxR);
        worldMinX = Math.min(Math.min(x0, x1), Math.min(x2, x3)) - 70f;
        worldMaxX = Math.max(Math.max(x0, x1), Math.max(x2, x3)) + 70f;
        worldMinY = hexY(0, minR) - 70f;
        worldMaxY = hexY(0, maxR) + 100f;
        float density = 1f;
        pxPerWorld = Math.max(0.30f, Math.min(0.75f, 0.50f * density));
        int bw = Math.max(64, (int) ((worldMaxX - worldMinX) * pxPerWorld));
        int bh = Math.max(64, (int) ((worldMaxY - worldMinY) * pxPerWorld));
        if (bw > 1600) bw = 1600;
        if (bh > 900) bh = 900;
        if (bg != null) bg.recycle();
        if (fg != null) fg.recycle();
        bg = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
        bgCanvas = new Canvas(bg);
        bgCanvas.drawColor(groundColor);
        drawGroundVariation(bgCanvas, groundColor, sceneName, minQ, maxQ, minR, maxR);
        drawCracks(bgCanvas, minQ, maxQ, minR, maxR);
        fg = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        fgCanvas = new Canvas(fg);
        drawLightOverlay(fgCanvas, bw, bh);
    }

    public boolean isWalkable(float wx, float wy) {
        if (walk == null) return true;
        worldToHex(wx, wy, hexOut);
        int q = hexOut[0], r = hexOut[1];
        int idx = (q + 64) + (r + 64) * 128;
        if (idx < 0 || idx >= walk.length) return false;
        return walk[idx];
    }

    public void draw(Canvas cv, float camX, float camY, float zoom, int W, int H, int quality, float t) {
        if (bg == null) return;
        cv.save();
        drawMatrix.reset();
        drawMatrix.postScale(zoom / pxPerWorld, zoom / pxPerWorld);
        drawMatrix.postTranslate((worldMinX - camX) * zoom + W / 2f,
                (worldMinY - camY) * zoom + H / 2f);
        cv.drawBitmap(bg, drawMatrix, bgPaint);
        if (quality > 0 && fg != null) cv.drawBitmap(fg, drawMatrix, fgPaint);
        cv.restore();
        for (int i = 0; i < props.size(); i++) drawProp(cv, props.get(i), camX, camY, zoom, W, H);
    }

    public int propCount() { return props.size(); }
    public Prop propAt(int i) { return props.get(i); }

    private void drawProp(Canvas cv, Prop p, float camX, float camY, float zoom, int W, int H) {
        if (p.frame == null || p.frame.bmp == null) return;
        float sx = (p.x - camX) * zoom + W / 2f;
        float sy = (p.y - camY) * zoom + H / 2f;
        float s = p.scale * zoom;
        float dw = p.frame.cw * s, dh = p.frame.ch * s;
        srcRect.set(p.frame.left, p.frame.top, p.frame.left + p.frame.cw, p.frame.top + p.frame.ch);
        dstRect.set(sx - dw / 2f, sy - dh, sx + dw / 2f, sy);
        cv.drawBitmap(p.frame.bmp, srcRect, dstRect, bgPaint);
    }

    private static boolean[] buildWalkMask(int kind, ArrayList<Prop> props) {
        boolean[] mask = new boolean[128 * 128];
        for (int r = -64; r < 64; r++) {
            int base = (r + 64) * 128;
            for (int q = -64; q < 64; q++) {
                mask[base + q + 64] = shapeWalkable(q, r, kind);
            }
        }
        for (int i = 0; i < props.size(); i++) {
            Prop p = props.get(i);
            if (!p.blocking) continue;
            int idx = (p.q + 64) + (p.r + 64) * 128;
            if (idx >= 0 && idx < mask.length) mask[idx] = false;
            int idx2 = (p.q + 1 + 64) + (p.r + 64) * 128;
            if (idx2 >= 0 && idx2 < mask.length) mask[idx2] = false;
            int idx3 = (p.q - 1 + 64) + (p.r + 64) * 128;
            if (idx3 >= 0 && idx3 < mask.length) mask[idx3] = false;
        }
        return mask;
    }

    private static boolean shapeWalkable(int q, int r, int kind) {
        switch (kind) {
            case KIND_ASHEN: return Math.abs(q) <= 10 && Math.abs(r) <= 8 && Math.abs(q + r) <= 14;
            case KIND_DESCENT: return q >= -8 && q <= 10 && r >= -2 && r <= 2;
            case KIND_CITY: return q >= -6 && q <= 10 && r >= -5 && r <= 5;
            case KIND_COURTYARD: return (q * q + q * r + r * r) <= 49;
            case KIND_RUN: return q >= -4 && q <= 10 && r >= -1 && r <= 1;
            default: return true;
        }
    }

    private int[] sceneBounds() {
        int minQ = 1000, maxQ = -1000, minR = 1000, maxR = -1000;
        for (Prop p : props) {
            if (p.q < minQ) minQ = p.q;
            if (p.q > maxQ) maxQ = p.q;
            if (p.r < minR) minR = p.r;
            if (p.r > maxR) maxR = p.r;
        }
        if (minQ == 1000) { minQ = -4; maxQ = 4; minR = -4; maxR = 4; }
        switch (sceneKind) {
            case KIND_ASHEN: minQ = Math.min(minQ, -10); maxQ = Math.max(maxQ, 10);
                minR = Math.min(minR, -8); maxR = Math.max(maxR, 8); break;
            case KIND_DESCENT: minQ = Math.min(minQ, -8); maxQ = Math.max(maxQ, 10);
                minR = Math.min(minR, -2); maxR = Math.max(maxR, 2); break;
            case KIND_CITY: minQ = Math.min(minQ, -6); maxQ = Math.max(maxQ, 10);
                minR = Math.min(minR, -5); maxR = Math.max(maxR, 5); break;
            case KIND_COURTYARD: minQ = Math.min(minQ, -8); maxQ = Math.max(maxQ, 8);
                minR = Math.min(minR, -8); maxR = Math.max(maxR, 8); break;
            case KIND_RUN: minQ = Math.min(minQ, -4); maxQ = Math.max(maxQ, 10);
                minR = Math.min(minR, -1); maxR = Math.max(maxR, 1); break;
        }
        return new int[] { minQ, maxQ, minR, maxR };
    }

    private void drawGroundVariation(Canvas canvas, int ground, String sceneName,
                                     int minQ, int maxQ, int minR, int maxR) {
        java.util.Random rnd = new java.util.Random(sceneName == null ? 7L : sceneName.hashCode());
        groundPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 16; i++) {
            int q = minQ + rnd.nextInt(Math.max(1, maxQ - minQ + 1));
            int r = minR + rnd.nextInt(Math.max(1, maxR - minR + 1));
            float x = hexX(q, r), y = hexY(q, r);
            float rad = 50f + rnd.nextFloat() * 140f;
            int alpha = rnd.nextBoolean() ? 0x16 : 0x13;
            int color = rnd.nextBoolean() ? 0x000000 : 0xffffff;
            groundPaint.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawOval(worldToBitmapX(x) - rad, worldToBitmapY(y) - rad,
                    worldToBitmapX(x) + rad, worldToBitmapY(y) + rad, groundPaint);
        }
    }

    private void drawCracks(Canvas canvas, int minQ, int maxQ, int minR, int maxR) {
        crackPaint.setColor(0x99000000);
        crackPaint.setStrokeWidth(2.5f * pxPerWorld);
        for (Crack c : cracks) {
            float sx = worldToBitmapX(hexX(c.q1, c.r1));
            float sy = worldToBitmapY(hexY(c.q1, c.r1));
            float ex = worldToBitmapX(hexX(c.q2, c.r2));
            float ey = worldToBitmapY(hexY(c.q2, c.r2));
            crackPath.reset();
            crackPath.moveTo(sx, sy);
            java.util.Random rnd = new java.util.Random((long) (c.q1 * 31 + c.r1 * 97 + c.q2 * 131 + c.r2 * 17));
            float dx = ex - sx, dy = ey - sy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            int steps = Math.max(4, (int) (len / (40f * pxPerWorld)));
            for (int i = 1; i < steps; i++) {
                float t = i / (float) steps;
                float jx = sx + dx * t + (rnd.nextFloat() - 0.5f) * 18f * pxPerWorld;
                float jy = sy + dy * t + (rnd.nextFloat() - 0.5f) * 18f * pxPerWorld;
                crackPath.lineTo(jx, jy);
            }
            crackPath.lineTo(ex, ey);
            canvas.drawPath(crackPath, crackPaint);
        }
    }

    private void drawLightOverlay(Canvas canvas, int bw, int bh) {
        LinearGradient fog = new LinearGradient(0, 0, 0, bh,
                new int[] { 0x00000000, 0x48000000, 0x00000000 },
                new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP);
        lightPaint.setShader(fog);
        canvas.drawRect(0, 0, bw, bh, lightPaint);
        for (Prop p : props) {
            int glow = glowColor(p.type);
            if (glow == 0) continue;
            float bx = worldToBitmapX(p.x), by = worldToBitmapY(p.y);
            float radius = 120f * pxPerWorld;
            if ("spire".equals(p.type) || "wall".equals(p.type)) radius *= 1.4f;
            RadialGradient rg = new RadialGradient(bx, by, radius,
                    new int[] { glow, glow & 0x00ffffff },
                    new float[] { 0f, 1f }, Shader.TileMode.CLAMP);
            lightPaint.setShader(rg);
            canvas.drawCircle(bx, by, radius, lightPaint);
        }
        lightPaint.setShader(null);
    }

    private static int glowColor(String type) {
        switch (type) {
            case "bonepillar": return 0x39b07cff;
            case "bones": return 0x3934e3d6;
            case "barricade": return 0x2cff7a1a;
            case "rubble": return 0x24ff2747;
            case "spire": return 0x30b3102a;
            default: return 0;
        }
    }

    private float worldToBitmapX(float wx) { return (wx - worldMinX) * pxPerWorld; }
    private float worldToBitmapY(float wy) { return (wy - worldMinY) * pxPerWorld; }

    private Frame[] getSmallProps() {
        if (smallPropFrames == null && ctx != null) {
            java.util.List<Bitmap> bms = Sprites.trimBottom(
                    Sprites.cutSheet(ctx, "sprites/props.png", 2, 4, 4), 0.9f);
            smallPropFrames = new Frame[bms.size()];
            for (int i = 0; i < bms.size(); i++) {
                smallPropFrames[i] = wrapFrame(bms.get(i));
            }
        }
        return smallPropFrames;
    }

    private Frame[] getLargeProps() {
        if (largePropFrames == null && ctx != null) {
            java.util.List<Bitmap> bms = Sprites.trimBottom(
                    Sprites.cutSheet(ctx, "sprites/props2.png", 2, 4, 4), 0.9f);
            largePropFrames = new Frame[bms.size()];
            for (int i = 0; i < bms.size(); i++) {
                largePropFrames[i] = wrapFrame(bms.get(i));
            }
        }
        return largePropFrames;
    }

    private static Frame wrapFrame(Bitmap b) {
        Frame f = new Frame();
        f.bmp = b;
        f.top = 0; f.left = 0;
        f.cw = b.getWidth(); f.ch = b.getHeight();
        f.rgt = f.cw; f.ww = f.cw;
        f.ref = f.ch;
        return f;
    }

    private Frame getPropFrame(String type) {
        int idx;
        switch (type) {
            case "spire": idx = 0; break;
            case "wall": idx = 1; break;
            case "rubble": idx = 2; break;
            case "bonepillar": idx = 3; break;
            case "bones": idx = 4; break;
            case "barricade": idx = 5; break;
            case "street": idx = 6; break;
            default: idx = 7; break;
        }
        if ("spire".equals(type) || "wall".equals(type) || "bonepillar".equals(type) || "barricade".equals(type)) {
            Frame[] lp = getLargeProps();
            return lp != null ? lp[idx % lp.length] : null;
        }
        Frame[] sp = getSmallProps();
        return sp != null ? sp[idx % sp.length] : null;
    }

    private static boolean isBlocking(String type) {
        switch (type) {
            case "spire": case "wall": case "rubble": case "barricade":
            case "bonepillar": case "bones": return true;
            default: return false;
        }
    }

    public static float hexX(int q, int r) { return HEX * SQRT3 * (q + r * 0.5f); }
    public static float hexY(int q, int r) { return HEX * 1.5f * r * SQUASH; }

    private static void worldToHex(float wx, float wy, int[] out) {
        float rf = wy / (HEX * 1.5f * SQUASH);
        float qf = wx / (HEX * SQRT3) - rf * 0.5f;
        float zf = -qf - rf;
        int rq = Math.round(qf), rr = Math.round(rf), rz = Math.round(zf);
        float dq = Math.abs(rq - qf), dr = Math.abs(rr - rf), dz = Math.abs(rz - zf);
        if (dq > dr && dq > dz) rq = -rr - rz;
        else if (dr > dz) rr = -rq - rz;
        else rz = -rq - rr;
        out[0] = rq; out[1] = rr;
    }
}
