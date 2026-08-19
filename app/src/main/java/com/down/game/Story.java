package com.down.game;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.ArrayList;

public class Story {

    public static final int MODE_DIALOG = 0, MODE_FIGHT = 1, MODE_WALK = 2,
            MODE_RUN = 3, MODE_DONE = 4;

    public interface Host {
        void shTeleport(float x, float y);
        void shWalkTo(float x, float y);
        boolean shPlayerArrived();
        void shClearEnemies();
    }

    private static final float SQUASH = 0.6f, HEX = 96f;
    private static final float CPS = 42f;

    private static final int C_BLOOD = 0xFFb3102a, C_BRIGHT = 0xFFff2747,
            C_EMBER = 0xFFff7a1a, C_BONE = 0xFFefe6dd, C_BONE_DIM = 0xFFb7a6ab,
            C_CYAN = 0xFF34e3d6, C_VIOLET = 0xFFb07cff, C_MAGENTA = 0xFFff2d7e;

    private final Host host;
    private final RectF rf = new RectF();

    public int mode = MODE_RUN;
    public boolean dialogUp = false, ended = false, quitRequested = false;
    public int fightRequest = 0;
    public String speaker = "", text = "", bg = "road", title = "";
    public float tw = 0, titleT = 99;

    private static class Beat {
        int type; String who, txt, scene; int count, q, r;
    }
    private final ArrayList<Beat> beats = new ArrayList<>();
    private int bi = -1, lastFight = 2;

    public Story(Host h) { host = h; buildAct1(); }

    private static void hexToWorld(int q, int r, float[] o) {
        o[0] = HEX * (float) Math.sqrt(3) * (q + r / 2f);
        o[1] = HEX * 1.5f * r * SQUASH;
    }
    private final float[] HW = new float[2];
    private float hx(int q, int r) { hexToWorld(q, r, HW); return HW[0]; }
    private float hy(int q, int r) { hexToWorld(q, r, HW); return HW[1]; }

    private Beat line(String scene, String who, String t) {
        Beat b = new Beat(); b.type = 0; b.scene = scene; b.who = who; b.txt = t;
        beats.add(b); return b;
    }
    private Beat fight(int n) { Beat b = new Beat(); b.type = 1; b.count = n; beats.add(b); return b; }
    private Beat walk(int q, int r) { Beat b = new Beat(); b.type = 2; b.q = q; b.r = r; beats.add(b); return b; }

    private void buildAct1() {
        line("road", "NilouZila", "They brought everything.");
        line(null, "Vex", "Then we run. We don't stop for anything.");
        line(null, "???", "Nowhere left to run, little birds.");
        fight(2);
        line(null, "Vex", "Two down. More coming from the treeline.");
        walk(6, 0);

        line("bridge", "NilouZila", "Blackwater Bridge. Don't look at the water.");
        line(null, "Vex", "I hear them on the planks.");
        line(null, "???", "Toll is blood.");
        fight(3);
        line(null, "NilouZila", "Across. Now.");

        line("camp", "Vex", "Fire still warm. They were here.");
        line(null, "NilouZila", "One breath. Then the gate.");
        line(null, "???", "Rest? No.");
        fight(3);

        line("gate", "NilouZila", "The Bone Gate. End of the run.");
        line(null, "Vex", "Then we break it open.");
        fight(4);
        line(null, "NilouZila", "It's open. Go. GO.");
    }

    private String titleFor(String b) {
        if (b.equals("road")) return "The Run";
        if (b.equals("bridge")) return "Blackwater Bridge";
        if (b.equals("camp")) return "Ember Camp";
        if (b.equals("gate")) return "The Bone Gate";
        return "";
    }

    public void start() { bi = -1; mode = MODE_RUN; next(); }

    private void next() {
        bi++; tw = 0;
        if (bi >= beats.size()) { ended = true; mode = MODE_DONE; dialogUp = false; return; }
        Beat b = beats.get(bi);
        if (b.scene != null) {
            bg = b.scene; title = titleFor(bg); titleT = 0;
            host.shClearEnemies();
            host.shTeleport(hx(0, 0), hy(0, 0));
        }
        if (b.type == 0) { mode = MODE_DIALOG; dialogUp = true; speaker = b.who; text = b.txt; }
        else if (b.type == 1) { mode = MODE_FIGHT; lastFight = b.count; fightRequest = b.count; }
        else { mode = MODE_WALK; host.shWalkTo(hx(b.q, b.r), hy(b.q, b.r)); }
    }

    public void update(float dt) {
        titleT += dt;
        if (mode == MODE_DIALOG) tw += dt * CPS;
    }

    public void tap() {
        if (mode != MODE_DIALOG) return;
        if (tw < text.length()) { tw = text.length(); return; }
        dialogUp = false; mode = MODE_RUN; next();
    }

    public void onWalkDone() { mode = MODE_RUN; next(); }
    public void fightWon() { fightRequest = 0; mode = MODE_RUN; next(); }
    public void fightLost() { fightRequest = lastFight; }

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

    public void drawDialog(Canvas cv, int W, int H, Paint paint, Typeface body) {
        if (!dialogUp) return;
        float boxH = Math.max(150, H * 0.24f), y0 = H - boxH;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xF20a0608);
        cv.drawRect(0, y0, W, H, paint);
        paint.setColor(C_MAGENTA);
        cv.drawRect(0, y0, W, y0 + 3, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(body);
        paint.setTextSize(Math.min(30, W * 0.03f));
        paint.setColor(colorFor(speaker));
        cv.drawText(speaker, 40, y0 + 52, paint);

        paint.setTextSize(Math.min(28, W * 0.028f));
        paint.setColor(C_BONE);
        String shown = text.substring(0, Math.min(text.length(), (int) tw));
        float maxW = W - 80, ly = y0 + 96;
        String[] words = shown.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            String test = sb.length() == 0 ? w : sb + " " + w;
            if (paint.measureText(test) > maxW && sb.length() > 0) {
                cv.drawText(sb.toString(), 40, ly, paint); ly += 40;
                sb = new StringBuilder(w);
            } else sb = new StringBuilder(test);
        }
        cv.drawText(sb.toString(), 40, ly, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    public void drawTitle(Canvas cv, int W, int H, Paint paint, Typeface logo) {
        if (titleT > 2.2f || title.length() == 0) return;
        int a = titleT < 1.6f ? 255 : (int) ((2.2f - titleT) / 0.6f * 255);
        paint.setAlpha(a);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(logo);
        paint.setTextSize(Math.min(64, W * 0.07f));
        paint.setColor(C_MAGENTA);
        cv.drawText(title, W / 2f, H * 0.2f, paint);
        paint.setAlpha(255);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    // ---------- walkable mask + themed battle ground ----------

    private float roadCenter(float x) {
        return 260f * (float) Math.sin(x * 0.0016f) + 180f * (float) Math.sin(x * 0.0007f + 1.7f);
    }
    private float roadHalf(float x) { return 150f + 40f * (float) Math.sin(x * 0.001f + 0.5f); }

    public boolean isWalkable(float x, float y) {
        if (bg.equals("road")) return Math.abs(y - roadCenter(x)) < roadHalf(x) - 12f;
        if (bg.equals("bridge")) return Math.abs(y) < 98f;
        if (bg.equals("camp")) return (x * x + y * y) < 440f * 440f;
        if (bg.equals("gate")) return Math.abs(x) < 500f && Math.abs(y) < 240f;
        return true;
    }

    private static int hash(int a, int b) { return (a * 40503) ^ (b * 66827); }
    private float sx(float wx, float c, float z, int W) { return (wx - c) * z + W / 2f; }
    private float sy(float wy, float c, float z, int H) { return (wy - c) * z + H / 2f; }

    public void drawBattleGround(Canvas cv, float camX, float camY, float zoom, int W, int H) {
        float hw = W / (2f * zoom), hh = H / (2f * zoom);
        float x0 = camX - hw, x1 = camX + hw, y0 = camY - hh, y1 = camY + hh;

        if (bg.equals("bridge")) {
            cv.drawColor(0xFF081018);
            float bt = sy(-110, camY, zoom, H), bb = sy(110, camY, zoom, H);
            cv.drawRect(0, bt, W, bb, paint(0xFF2a1c12, cv));
            for (float x = (float) Math.floor(x0 / 28) * 28; x < x1; x += 28)
                cv.drawRect(sx(x, camX, zoom, W), bt, sx(x, camX, zoom, W) + 2 * zoom, bb, paint(0xFF1c120a, cv));
            return;
        }
        if (bg.equals("camp")) {
            cv.drawColor(0xFF0d120a);
            float cx = sx(0, camX, zoom, W), cy = sy(0, camY, zoom, H);
            cv.drawCircle(cx, cy, 460 * zoom, paint(0xFF1a120c, cv));
            cv.drawCircle(cx, cy, 276 * zoom, paint(0xFF241812, cv));
            return;
        }
        if (bg.equals("gate")) {
            cv.drawColor(0xFF0a0709);
            float ct = sy(-240, camY, zoom, H), cb = sy(240, camY, zoom, H);
            cv.drawRect(0, ct, W, cb, paint(0xFF1c1620, cv));
            return;
        }
        // road
        cv.drawColor(0xFF0d120a);
        float strip = 24f;
        for (float x = ((float) Math.floor(x0 / strip) - 1) * strip; x < x1 + strip; x += strip) {
            float c = roadCenter(x), hf = roadHalf(x);
            float top = sy(c - hf, camY, zoom, H), bot = sy(c + hf, camY, zoom, H);
            float sxp = sx(x, camX, zoom, W), w = strip * zoom + 2f;
            cv.drawRect(sxp, top, sxp + w, bot, paint(0xFF241a12, cv));
            cv.drawRect(sxp, top, sxp + w, top + 3 * zoom, paint(0xFF3a2a1a, cv));
            cv.drawRect(sxp, bot - 3 * zoom, sxp + w, bot, paint(0xFF3a2a1a, cv));
        }
    }

    private final Paint gp = new Paint();
    private Paint paint(int col, Canvas cv) { gp.setColor(col); gp.setStyle(Paint.Style.FILL); return gp; }
}
