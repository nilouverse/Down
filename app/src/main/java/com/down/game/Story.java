package com.down.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Story {

    private static final int C_INK = 0xFF0a0608;
    private static final int C_BLOOD = 0xFFb3102a;
    private static final int C_BRIGHT = 0xFFff2747;
    private static final int C_EMBER = 0xFFff7a1a;
    private static final int C_BONE = 0xFFefe6dd;
    private static final int C_BONE_DIM = 0xFFb7a6ab;
    private static final int C_CYAN = 0xFF34e3d6;
    private static final int C_VIOLET = 0xFFb07cff;
    private static final int C_MAGENTA = 0xFFff2d7e;

    private static final float CPS = 42f;

    // 0 = dialogue, 1 = waiting for fight result, 2 = end card
    private int mode = 0;
    public boolean quitRequested = false;
    public int fightRequest = 0;

    private final Context ctx;
    private final HashMap<String, List<Frame>> frames;
    private final HashMap<String, List<Frame>> spriteCache = new HashMap<>();

    private int W, H;
    private float t;
    private String bg = "road";
    private final ArrayList<Beat> beats = new ArrayList<>();
    private int bi = -1;
    private float tw;
    private float titleT = 99;
    private String title = "";

    private final Paint paint = new Paint();
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private final Rect src = new Rect();
    private final RectF quitBtn = new RectF();

    private Typeface fLogo, fBody, fSerif;

    private static class Beat {
        int type;            // 0 line, 1 fight
        String who, text;
        String scene;        // optional scene switch
        int count;
    }

    public Story(Context c, HashMap<String, List<Frame>> f) {
        ctx = c;
        frames = f;
        paint.setFilterBitmap(true);
        try { fLogo = Typeface.createFromAsset(c.getAssets(), "fonts/MetalMania-Regular.ttf"); } catch (Exception e) { fLogo = Typeface.DEFAULT; }
        try { fBody = Typeface.createFromAsset(c.getAssets(), "fonts/SpaceGrotesk-Bold.ttf"); } catch (Exception e) { fBody = Typeface.DEFAULT_BOLD; }
        try { fSerif = Typeface.createFromAsset(c.getAssets(), "fonts/InstrumentSerif-Italic.ttf"); } catch (Exception e) { fSerif = Typeface.DEFAULT; }
    }

    // ---------------- script ----------------

    public void load(String section) {
        beats.clear();
        bi = -1;
        mode = 0;
        fightRequest = 0;
        quitRequested = false;
        buildScript(section);
        next();
    }

    private void line(String scene, String who, String text) {
        Beat b = new Beat();
        b.type = 0; b.scene = scene; b.who = who; b.text = text;
        beats.add(b);
    }

    private void fight(String scene, int count) {
        Beat b = new Beat();
        b.type = 1; b.scene = scene; b.count = count;
        beats.add(b);
    }

    private void buildScript(String s) {
        line("road", "NilouZila", "They brought everything.");
        line(null, "Vex", "Then we run. We don't stop for anything.");
        line(null, "NilouZila", "The road bends ahead. Stay close.");
        line(null, "???", "Nowhere left to run, little birds.");
        fight(null, 2);
        line(null, "Vex", "Two down. More coming from the treeline.");

        line("bridge", "NilouZila", "Blackwater Bridge. Don't look at the water.");
        line(null, "Vex", "I hear them on the planks.");
        line(null, "???", "Toll is blood.");
        fight(null, 3);
        line(null, "NilouZila", "Across. Now.");

        line("camp", "Vex", "Fire still warm. They were here.");
        line(null, "NilouZila", "One breath. Then the gate.");
        line(null, "???", "Rest? No.");
        fight(null, 3);

        line("gate", "NilouZila", "The Bone Gate. End of the run.");
        line(null, "Vex", "Then we break it open.");
        fight(null, 4);
        line(null, "NilouZila", "It's open. Go. GO.");
    }

    private void next() {
        bi++;
        tw = 0;
        if (bi >= beats.size()) { mode = 2; return; }
        Beat b = beats.get(bi);
        if (b.scene != null) {
            bg = b.scene;
            title = titleFor(bg);
            titleT = 0;
        }
        if (b.type == 1) {
            mode = 1;
            fightRequest = b.count;
        }
    }

    private String titleFor(String b) {
        if (b.equals("road")) return "The Run";
        if (b.equals("bridge")) return "Blackwater Bridge";
        if (b.equals("camp")) return "Ember Camp";
        if (b.equals("gate")) return "The Bone Gate";
        return "";
    }

    // ---------------- fight hooks (GameView wiring later) ----------------

    public void fightWon() {
        fightRequest = 0;
        mode = 0;
        next();
    }

    public void fightLost() {
        Beat b = beats.get(bi);
        fightRequest = (b != null && b.type == 1) ? b.count : 2;
    }

    public boolean awaitingFight() { return mode == 1; }

    // ---------------- walkable ground for battles ----------------

    private float roadCenter(float x) {
        return 260f * (float) Math.sin(x * 0.0016f) + 180f * (float) Math.sin(x * 0.0007f + 1.7f);
    }

    private float roadHalf(float x) {
        return 150f + 40f * (float) Math.sin(x * 0.001f + 0.5f);
    }

    public boolean isWalkable(float x, float y) {
        if (bg.equals("road")) return Math.abs(y - roadCenter(x)) < roadHalf(x) - 12f;
        if (bg.equals("bridge")) return Math.abs(y) < 98f;
        if (bg.equals("camp")) return (x * x + y * y) < 440f * 440f;
        if (bg.equals("gate")) return Math.abs(x) < 500f && Math.abs(y) < 240f;
        return true;
    }

    public float[] fightOrigin() {
        if (bg.equals("road")) return new float[] { 0, roadCenter(0) };
        if (bg.equals("camp")) return new float[] { 0, 40 };
        return new float[] { 0, 0 };
    }

    public String currentBg() { return bg; }

    // ---------------- update / touch ----------------

    public void update(float dt) {
        t += dt;
        titleT += dt;
        if (mode == 0 && bi >= 0 && bi < beats.size()) {
            Beat b = beats.get(bi);
            if (b.type == 0) tw += dt * CPS;
        }
    }

    public boolean touch(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
        float x = e.getX(), y = e.getY();
        if (quitBtn.contains(x, y)) { quitRequested = true; return true; }
        if (mode == 2) { quitRequested = true; return true; }
        if (mode == 1) return true;
        Beat b = beats.get(bi);
        if (b == null) return true;
        int len = b.text == null ? 0 : b.text.length();
        if (tw < len) tw = len;
        else next();
        return true;
    }

    // ---------------- draw ----------------

    public void draw(Canvas cv) {
        W = cv.getWidth(); H = cv.getHeight();
        if (mode == 1) { drawAmbush(cv); return; }
        if (mode == 2) { drawEnd(cv); return; }
        drawSideScene(cv);
    }

    private static int hash(int a, int b) { return (a * 40503) ^ (b * 66827); }

    private void drawSideScene(Canvas cv) {
        float boxH = Math.max(150, H * 0.24f);
        float horizon = H - boxH;
        float feetY = horizon - H * 0.03f;

        // sky
        int top = 0xFF0b0714, bot = 0xFF1a0f22;
        if (bg.equals("bridge")) { top = 0xFF060a14; bot = 0xFF0d1626; }
        else if (bg.equals("camp")) { top = 0xFF140a08; bot = 0xFF241209; }
        else if (bg.equals("gate")) { top = 0xFF120609; bot = 0xFF200a10; }
        paint.setShader(new LinearGradient(0, 0, 0, horizon, top, bot, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, W, horizon, paint);
        paint.setShader(null);

        // stars
        paint.setColor(0xFFefe6dd);
        for (int i = 0; i < 60; i++) {
            int h = hash(i, 77);
            float sx = ((h >>> 3) % 1000) / 1000f * W;
            float sy = ((h >>> 13) % 1000) / 1000f * horizon * 0.6f;
            paint.setAlpha(40 + ((h >>> 23) % 120));
            cv.drawPoint(sx, sy, paint);
        }
        paint.setAlpha(255);

        // moon
        float mx = W * 0.78f, my = horizon * 0.22f;
        paint.setShader(new RadialGradient(mx, my, 90, 0x50efe6dd, 0x00000000, Shader.TileMode.CLAMP));
        cv.drawRect(mx - 90, my - 90, mx + 90, my + 90, paint);
        paint.setShader(null);
        paint.setColor(0xFFe8ded2);
        cv.drawCircle(mx, my, 26, paint);
        paint.setColor(0xFFcfc2b4);
        cv.drawCircle(mx - 8, my - 4, 6, paint);
        cv.drawCircle(mx + 7, my + 8, 4, paint);

        drawSilhouettes(cv, horizon);

        // ground band
        int gcol = 0xFF120b16;
        if (bg.equals("bridge")) gcol = 0xFF0a1220;
        else if (bg.equals("camp")) gcol = 0xFF1c110a;
        else if (bg.equals("gate")) gcol = 0xFF150d14;
        paint.setColor(gcol);
        cv.drawRect(0, horizon - 4, W, H, paint);
        paint.setColor(0x33efe6dd);
        cv.drawRect(0, horizon - 4, W, horizon - 2, paint);

        drawStage(cv, feetY);
        drawBox(cv, boxH);
        drawTitle(cv);
        drawQuit(cv);
    }

    private void drawSilhouettes(Canvas cv, float horizon) {
        if (bg.equals("road") || bg.equals("camp")) {
            paint.setColor(0xFF0d0a12);
            for (int x = 0; x < W + 60; x += 46) {
                int h = hash(x, 5);
                float th = 40 + ((h >>> 4) % 90);
                path.reset();
                path.moveTo(x, horizon);
                path.lineTo(x + 23, horizon - th);
                path.lineTo(x + 46, horizon);
                path.close();
                cv.drawPath(path, paint);
            }
        }
        if (bg.equals("bridge")) {
            paint.setColor(0xFF081018);
            cv.drawRect(0, horizon - 40, W, horizon, paint);
            paint.setColor(0x2234e3d6);
            for (int x = 0; x < W; x += 34) {
                float wy = horizon - 20 + (float) Math.sin(x * 0.05f + t * 2f) * 3;
                cv.drawRect(x, wy, x + 16, wy + 2, paint);
            }
            paint.setColor(0xFF2a1c12);
            for (int x = 20; x < W; x += 90) cv.drawRect(x, horizon - 70, x + 8, horizon - 40, paint);
            cv.drawRect(0, horizon - 74, W, horizon - 68, paint);
        }
        if (bg.equals("camp")) {
            float fx = W * 0.5f, fy = horizon - 30;
            paint.setShader(new RadialGradient(fx, fy, 160, 0x66ff7a1a, 0x00000000, Shader.TileMode.CLAMP));
            cv.drawRect(fx - 160, fy - 160, fx + 160, fy + 160, paint);
            paint.setShader(null);
            paint.setColor(0xFF241a2a);
            path.reset(); path.moveTo(W * 0.12f, horizon); path.lineTo(W * 0.2f, horizon - 70); path.lineTo(W * 0.28f, horizon); path.close(); cv.drawPath(path, paint);
            path.reset(); path.moveTo(W * 0.74f, horizon); path.lineTo(W * 0.82f, horizon - 60); path.lineTo(W * 0.9f, horizon); path.close(); cv.drawPath(path, paint);
            float fl = 10 + (float) Math.sin(t * 9f) * 3;
            paint.setColor(C_EMBER);
            path.reset(); path.moveTo(fx - 8, fy); path.lineTo(fx, fy - fl * 2); path.lineTo(fx + 8, fy); path.close(); cv.drawPath(path, paint);
        }
        if (bg.equals("gate")) {
            paint.setColor(0xFF0a0709);
            cv.drawRect(0, horizon - 160, W, horizon, paint);
            for (int x = 0; x < W; x += 60) cv.drawRect(x + 8, horizon - 176, x + 52, horizon - 160, paint);
            paint.setColor(0xFF050304);
            cv.drawRect(W * 0.42f, horizon - 140, W * 0.58f, horizon, paint);
            paint.setColor(0x44ff2747);
            cv.drawRect(W * 0.42f, horizon - 140, W * 0.58f, horizon - 136, paint);
        }
    }

    // ---------------- stage sprites ----------------

    private List<Frame> spriteFor(String prefix) {
        List<Frame> c = spriteCache.get(prefix);
        if (c != null) return c.isEmpty() ? null : c;
        List<Frame> best = null, first = null;
        if (frames != null) {
            for (String key : frames.keySet()) {
                if (!key.startsWith(prefix)) continue;
                List<Frame> l = frames.get(key);
                if (l == null || l.isEmpty()) continue;
                if (first == null) first = l;
                if (key.contains("idle")) { best = l; break; }
            }
            if (best == null) best = first;
        }
        spriteCache.put(prefix, best == null ? new ArrayList<Frame>() : best);
        return best;
    }

    private String prefixFor(String who) {
        if (who == null) return null;
        if (who.startsWith("Nilou")) return "nilou";
        if (who.startsWith("Vex")) return "vex";
        return null;
    }

    private int colorFor(String who) {
        String p = prefixFor(who);
        if ("nilou".equals(p)) return C_MAGENTA;
        if ("vex".equals(p)) return C_CYAN;
        return C_BRIGHT;
    }

    private void drawSprite(Canvas cv, List<Frame> fr, float x, float feetY, float h, boolean flip, float alpha) {
        if (fr == null || fr.isEmpty()) { drawPlaceholder(cv, x, feetY, h); return; }
        Frame f = fr.get(((int) (t * 4f)) % fr.size());
        float s = h / f.ref;
        if (f.vCrop) src.set(0, f.top, f.bmp.getWidth(), f.top + f.ch);
        else src.set(0, 0, f.bmp.getWidth(), f.bmp.getHeight());
        float w = src.width() * s;
        cv.save();
        cv.translate(x, feetY);
        if (flip) cv.scale(-1, 1);
        paint.setAlpha((int) alpha);
        rf.set(-w / 2f, -src.height() * s, w / 2f, 0);
        cv.drawBitmap(f.bmp, src, rf, paint);
        paint.setAlpha(255);
        cv.restore();
    }

    private void drawPlaceholder(Canvas cv, float x, float feetY, float h) {
        paint.setColor(0xFF2a1c2c);
        cv.drawCircle(x, feetY - h * 0.86f, h * 0.12f, paint);
        path.reset();
        path.moveTo(x - h * 0.16f, feetY);
        path.lineTo(x - h * 0.1f, feetY - h * 0.78f);
        path.lineTo(x + h * 0.1f, feetY - h * 0.78f);
        path.lineTo(x + h * 0.16f, feetY);
        path.close();
        cv.drawPath(path, paint);
    }

    private void drawStage(Canvas cv, float feetY) {
        Beat b = beats.get(bi);
        String who = b == null ? "" : b.who;
        float h = (H * 0.72f) * 0.46f;

        float nx = W * 0.3f, vx = W * 0.7f, ex = W * 0.52f;

        // speaker glow
        if (who != null) {
            float gx = who.startsWith("Nilou") ? nx : who.startsWith("Vex") ? vx : ex;
            paint.setShader(new RadialGradient(gx, feetY, 90, 0x33ffffff, 0x00000000, Shader.TileMode.CLAMP));
            cv.drawRect(gx - 90, feetY - 90, gx + 90, feetY + 90, paint);
            paint.setShader(null);
        }

        drawSprite(cv, spriteFor("nilou"), nx, feetY, h, false, who != null && who.startsWith("Nilou") ? 255 : 170);
        drawSprite(cv, spriteFor("vex"), vx, feetY, h, true, who != null && who.startsWith("Vex") ? 255 : 170);
        if (who != null && prefixFor(who) == null) drawPlaceholder(cv, ex, feetY, h * 1.08f);
    }

    private void drawBox(Canvas cv, float boxH) {
        float y0 = H - boxH;
        paint.setColor(0xF20a0608);
        cv.drawRect(0, y0, W, H, paint);
        paint.setColor(C_MAGENTA);
        cv.drawRect(0, y0, W, y0 + 3, paint);

        Beat b = beats.get(bi);
        if (b == null) return;
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(fBody);
        paint.setTextSize(Math.min(30, W * 0.03f));
        paint.setColor(colorFor(b.who));
        cv.drawText(b.who == null ? "" : b.who, 40, y0 + 52, paint);

        paint.setTypeface(fBody);
        paint.setTextSize(Math.min(28, W * 0.028f));
        paint.setColor(C_BONE);
        String full = b.text == null ? "" : b.text;
        String shown = full.substring(0, Math.min(full.length(), (int) tw));
        // wrap
        float maxW = W - 80;
        String[] words = shown.split(" ");
        StringBuilder sb = new StringBuilder();
        float ly = y0 + 96;
        for (String w : words) {
            String test = sb.length() == 0 ? w : sb + " " + w;
            if (paint.measureText(test) > maxW && sb.length() > 0) {
                cv.drawText(sb.toString(), 40, ly, paint);
                ly += 40;
                sb = new StringBuilder(w);
            } else sb = new StringBuilder(test);
        }
        cv.drawText(sb.toString(), 40, ly, paint);

        if (tw >= full.length()) {
            paint.setColor(0xAAefe6dd);
            path.reset();
            path.moveTo(W - 56, H - 40);
            path.lineTo(W - 36, H - 40);
            path.lineTo(W - 46, H - 26);
            path.close();
            cv.drawPath(path, paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawTitle(Canvas cv) {
        if (titleT > 2.2f || title.length() == 0) return;
        int a = titleT < 1.6f ? 255 : (int) ((2.2f - titleT) / 0.6f * 255);
        paint.setAlpha(a);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(fLogo);
        paint.setTextSize(Math.min(64, W * 0.07f));
        paint.setColor(C_MAGENTA);
        cv.drawText(title, W / 2f, H * 0.2f, paint);
        paint.setAlpha(255);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawQuit(Canvas cv) {
        quitBtn.set(16, 16, 96, 76);
        paint.setColor(0x66050508);
        cv.drawRect(quitBtn, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(0x66b7a6ab);
        cv.drawRect(quitBtn, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(C_BONE_DIM);
        paint.setTextSize(28);
        paint.setTypeface(fBody);
        cv.drawText("✕", 44, 58, paint);
    }

    private void drawAmbush(Canvas cv) {
        cv.drawColor(C_INK);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(fLogo);
        paint.setTextSize(72);
        paint.setColor(C_BRIGHT);
        cv.drawText("AMBUSH", W / 2f, H / 2f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawEnd(Canvas cv) {
        cv.drawColor(C_INK);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(fLogo);
        paint.setTextSize(Math.min(80, W * 0.09f));
        paint.setColor(C_MAGENTA);
        cv.drawText("END OF ACT I", W / 2f, H * 0.44f, paint);
        paint.setTypeface(fSerif);
        paint.setTextSize(30);
        paint.setColor(C_BONE_DIM);
        cv.drawText("the descent continues…", W / 2f, H * 0.44f + 60, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    // ---------------- top-down battle ground (called by GameView later) ----------------

    public void drawBattleGround(Canvas cv, float camX, float camY, float zoom, int W, int H) {
        float halfW = W / (2f * zoom), halfH = H / (2f * zoom);
        float wx0 = camX - halfW, wx1 = camX + halfW;
        float wy0 = camY - halfH, wy1 = camY + halfH;

        paint.setAlpha(255);

        if (bg.equals("bridge")) {
            paint.setColor(0xFF081018);
            cv.drawRect(0, 0, W, H, paint);
            paint.setColor(0x1834e3d6);
            for (float y = (float) Math.floor(wy0 / 48) * 48; y < wy1; y += 48) {
                float off = (float) Math.sin(y * 0.11f + t * 2f) * 30;
                cv.drawRect(sx(wx0, camX, zoom, W) , sy(y, camY, zoom, H) + off * 0, W, 0, paint);
                cv.drawRect(0, sy(y, camY, zoom, H), W, sy(y, camY, zoom, H) + 2 * zoom, paint);
            }
            float bt = sy(-110, camY, zoom, H), bb = sy(110, camY, zoom, H);
            paint.setColor(0xFF2a1c12);
            cv.drawRect(0, bt, W, bb, paint);
            paint.setColor(0xFF1c120a);
            for (float x = (float) Math.floor(wx0 / 28) * 28; x < wx1; x += 28) {
                cv.drawRect(sx(x, camX, zoom, W), bt, sx(x, camX, zoom, W) + 2 * zoom, bb, paint);
            }
            paint.setColor(0xFF4a3a22);
            cv.drawRect(0, bt - 4 * zoom, W, bt, paint);
            cv.drawRect(0, bb, W, bb + 4 * zoom, paint);
            return;
        }

        if (bg.equals("camp")) {
            paint.setColor(0xFF0d120a);
            cv.drawRect(0, 0, W, H, paint);
            float cx = sx(0, camX, zoom, W), cy = sy(0, camY, zoom, H);
            float r = 460 * zoom;
            paint.setColor(0xFF1a120c);
            cv.drawCircle(cx, cy, r, paint);
            paint.setColor(0xFF241812);
            cv.drawCircle(cx, cy, r * 0.6f, paint);
            float fx = sx(0, camX, zoom, W), fy = sy(-40, camY, zoom, H);
            paint.setShader(new RadialGradient(fx, fy, 200 * zoom, 0x55ff7a1a, 0x00000000, Shader.TileMode.CLAMP));
            cv.drawRect(fx - 200 * zoom, fy - 200 * zoom, fx + 200 * zoom, fy + 200 * zoom, paint);
            paint.setShader(null);
            paint.setColor(0xFF3a2a1a);
            cv.drawRect(fx - 20 * zoom, fy - 4 * zoom, fx + 20 * zoom, fy + 4 * zoom, paint);
            cv.drawRect(fx - 4 * zoom, fy - 20 * zoom, fx + 4 * zoom, fy + 20 * zoom, paint);
            drawTopTrees(cv, camX, camY, zoom, W, H, wx0, wx1, wy0, wy1, true);
            return;
        }

        if (bg.equals("gate")) {
            paint.setColor(0xFF0a0709);
            cv.drawRect(0, 0, W, H, paint);
            float ct = sy(-240, camY, zoom, H), cb = sy(240, camY, zoom, H);
            paint.setColor(0xFF1c1620);
            cv.drawRect(0, ct, W, cb, paint);
            paint.setColor(0x14000000);
            for (float y = -240; y < 240; y += 80) cv.drawRect(0, sy(y, camY, zoom, H), W, sy(y, camY, zoom, H) + zoom, paint);
            for (float x = (float) Math.floor(wx0 / 80) * 80; x < wx1; x += 80) cv.drawRect(sx(x, camX, zoom, W), ct, sx(x, camX, zoom, W) + zoom, cb, paint);
            paint.setColor(0xFF050304);
            cv.drawRect(0, 0, W, ct, paint);
            cv.drawRect(0, cb, W, H, paint);
            paint.setColor(0x44ff7a1a);
            for (float x = (float) Math.floor(wx0 / 240) * 240; x < wx1; x += 240) {
                float tx = sx(x, camX, zoom, W);
                paint.setShader(new RadialGradient(tx, ct, 60 * zoom, 0x55ff7a1a, 0x00000000, Shader.TileMode.CLAMP));
                cv.drawRect(tx - 60 * zoom, ct - 60 * zoom, tx + 60 * zoom, ct + 60 * zoom, paint);
                paint.setShader(null);
            }
            return;
        }

        // road (default)
        paint.setColor(0xFF0d120a);
        cv.drawRect(0, 0, W, H, paint);

        float strip = 24f;
        for (float x = ((float) Math.floor(wx0 / strip) - 1) * strip; x < wx1 + strip; x += strip) {
            float c = roadCenter(x), hf = roadHalf(x);
            float top = sy(c - hf, camY, zoom, H), bot = sy(c + hf, camY, zoom, H);
            float sxp = sx(x, camX, zoom, W), w = strip * zoom + 2f;
            paint.setColor(0xFF241a12);
            cv.drawRect(sxp, top, sxp + w, bot, paint);
            paint.setColor(0xFF3a2a1a);
            cv.drawRect(sxp, top, sxp + w, top + 3 * zoom, paint);
            cv.drawRect(sxp, bot - 3 * zoom, sxp + w, bot, paint);
            paint.setColor(0x33120b08);
            float my = sy(c, camY, zoom, H);
            cv.drawRect(sxp + 2 * zoom, my - zoom, sxp + w - 2 * zoom, my + zoom, paint);
        }

        // fence posts + lanterns along edges
        for (float x = (float) Math.floor(wx0 / 240) * 240; x < wx1; x += 240) {
            float c = roadCenter(x), hf = roadHalf(x);
            float px = sx(x, camX, zoom, W);
            paint.setColor(0xFF3a2a1a);
            cv.drawRect(px - 3 * zoom, sy(c - hf - 24, camY, zoom, H) - 8 * zoom, px + 3 * zoom, sy(c - hf - 24, camY, zoom, H));
            cv.drawRect(px - 3 * zoom, sy(c + hf + 24, camY, zoom, H) - 8 * zoom, px + 3 * zoom, sy(c + hf + 24, camY, zoom, H));
            if (((int) (x / 240)) % 4 == 0) {
                paint.setShader(new RadialGradient(px, sy(c - hf - 24, camY, zoom, H), 70 * zoom, 0x44ff7a1a, 0x00000000, Shader.TileMode.CLAMP));
                cv.drawRect(px - 70 * zoom, sy(c - hf - 24, camY, zoom, H) - 70 * zoom, px + 70 * zoom, sy(c - hf - 24, camY, zoom, H) + 70 * zoom, paint);
                paint.setShader(null);
            }
        }

        drawTopTrees(cv, camX, camY, zoom, W, H, wx0, wx1, wy0, wy1, false);
    }

    private void drawTopTrees(Canvas cv, float camX, float camY, float zoom, int W, int H,
                              float wx0, float wx1, float wy0, float wy1, boolean avoidCircle) {
        int gx0 = (int) Math.floor(wx0 / 192) - 1, gx1 = (int) Math.ceil(wx1 / 192) + 1;
        int gy0 = (int) Math.floor(wy0 / 192) - 1, gy1 = (int) Math.ceil(wy1 / 192) + 1;
        for (int gy = gy0; gy <= gy1; gy++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                int h = hash(gx, gy);
                if (((h >>> 3) % 100) >= 34) continue;
                float tx = gx * 192 + ((h >>> 9) & 127) / 127f * 96;
                float ty = gy * 192 + ((h >>> 15) & 127) / 127f * 96;
                boolean ok;
                if (avoidCircle) ok = (tx * tx + ty * ty) > 480f * 480f;
                else ok = Math.abs(ty - roadCenter(tx)) > roadHalf(tx) + 50;
                if (!ok) continue;
                float r = (34 + ((h >>> 21) & 31)) * zoom;
                float sx = sx(tx, camX, zoom, W), sy = sy(ty, camY, zoom, H);
                paint.setColor(0x66000000);
                cv.drawCircle(sx + 6 * zoom, sy + 6 * zoom, r, paint);
                paint.setColor(0xFF16240f);
                cv.drawCircle(sx, sy, r, paint);
                paint.setColor(0xFF2c4a1a);
                cv.drawCircle(sx - r * 0.25f, sy - r * 0.25f, r * 0.5f, paint);
            }
        }
    }

    private float sx(float wx, float camX, float zoom, int W) { return (wx - camX) * zoom + W / 2f; }
    private float sy(float wy, float camY, float zoom, int H) { return (wy - camY) * zoom + H / 2f; }
}
