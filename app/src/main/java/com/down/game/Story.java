package com.down.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

public class Story {

    public static final int MODE_DIALOG = 0, MODE_FIGHT = 1, MODE_WAIT = 2, MODE_RUN = 3, MODE_DONE = 4;

    public interface Host {
        void shTeleport(float x, float y);
        boolean shPlayerArrived();
        void shClearEnemies();
        Context shGetContext();
    }

    private static final float SQUASH = 0.6f, HEX = 96f;
    private static final float CPS = 42f;

    private static final int C_BLOOD = 0xFFb3102a, C_BRIGHT = 0xFFff2747,
            C_EMBER = 0xFFff7a1a, C_BONE = 0xFFefe6dd, C_BONE_DIM = 0xFFb7a6ab,
            C_CYAN = 0xFF34e3d6, C_VIOLET = 0xFFb07cff, C_MAGENTA = 0xFFff2d7e;

    private final Host host;

    public int mode = MODE_RUN;
    public boolean dialogUp = false, ended = false, quitRequested = false;
    public int fightRequest = 0;
    public String speaker = "", text = "", title = "";
    public float tw = 0, titleT = 99;
    public int groundColor = 0xFF0d120a;
    public int objectiveQ = 0, objectiveR = 0;
    public boolean hasObjective = false;
    
    private final Queue<String> actionQueue = new LinkedList<>();

    private static class Beat {
        int type; // 0 dialog, 1 action, 2 scene, 3 walk, 4 fight
        String who, txt, cmd;
        int count, q, r;
    }
    private final ArrayList<Beat> beats = new ArrayList<>();
    private int bi = -1;

    public Story(Host h) { 
        host = h; 
        loadScript(h.shGetContext()); 
    }

    private void loadScript(Context ctx) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(ctx.getAssets().open("story/act1.txt")));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                parseLine(line);
            }
        } catch (Exception e) {}
    }

    private void parseLine(String line) {
        String[] parts = line.split(" ", 2);
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1] : "";
        
        if (cmd.equals("NAME")) {
            Beat b = new Beat(); b.type = 2; b.cmd = "NAME"; b.txt = arg; beats.add(b);
        } else if (cmd.equals("GROUND")) {
            Beat b = new Beat(); b.type = 2; b.cmd = "GROUND"; b.txt = arg; beats.add(b);
        } else if (cmd.equals("SAY")) {
            String[] p2 = arg.split(" ", 2);
            Beat b = new Beat(); b.type = 0; b.who = p2[0]; b.txt = p2.length > 1 ? p2[1] : ""; beats.add(b);
        } else if (cmd.equals("ACTION") || cmd.equals("SHOW") || cmd.equals("HIDE") || cmd.equals("EXIT") || cmd.equals("ACTOR") || cmd.equals("PLACE") || cmd.equals("CRACK") || cmd.equals("RESET")) {
            Beat b = new Beat(); b.type = 1; b.cmd = cmd; b.txt = arg; beats.add(b);
        } else if (cmd.equals("WALK")) {
            String[] p2 = arg.split(" ");
            if (p2.length >= 3) {
                Beat b = new Beat(); b.type = 3; b.who = p2[0]; 
                b.q = Integer.parseInt(p2[1]); b.r = Integer.parseInt(p2[2]); 
                beats.add(b);
            }
        } else if (cmd.equals("FIGHT")) {
            Beat b = new Beat(); b.type = 4; b.count = Integer.parseInt(arg); beats.add(b);
        }
    }

    private final float[] HW = new float[2];
    private static void hexToWorld(int q, int r, float[] o) {
        o[0] = HEX * (float) Math.sqrt(3) * (q + r / 2f);
        o[1] = HEX * 1.5f * r * SQUASH;
    }

    public void start() { bi = -1; mode = MODE_RUN; next(); }

    private void next() {
        bi++; tw = 0;
        if (bi >= beats.size()) { ended = true; mode = MODE_DONE; dialogUp = false; return; }
        Beat b = beats.get(bi);
        
        if (b.type == 0) { 
            mode = MODE_DIALOG; dialogUp = true; speaker = b.who; text = b.txt; 
        } else if (b.type == 1) { 
            actionQueue.add(b.cmd + " " + b.txt);
            mode = MODE_RUN; next(); 
        } else if (b.type == 2) { 
            if (b.cmd.equals("NAME")) { title = b.txt; titleT = 0; }
            else if (b.cmd.equals("GROUND")) {
                try { groundColor = 0xFF000000 | Integer.parseInt(b.txt, 16); } catch (Exception e) {}
            }
            mode = MODE_RUN; next();
        } else if (b.type == 3) { 
            if (b.who.equals("nilou") || b.who.equals("NilouZila")) {
                objectiveQ = b.q; objectiveR = b.r; hasObjective = true;
                mode = MODE_WAIT;
            } else {
                actionQueue.add("WALK " + b.who + " " + b.q + " " + b.r);
                mode = MODE_RUN; next();
            }
        } else if (b.type == 4) { 
            mode = MODE_FIGHT; fightRequest = b.count;
        }
    }

    public String pollAction() { return actionQueue.poll(); }

    public void onObjectiveReached() {
        hasObjective = false;
        mode = MODE_RUN;
        next();
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

    public void fightWon() { fightRequest = 0; mode = MODE_RUN; next(); }
    public void fightLost() { }

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

    public boolean isWalkable(float x, float y) { return true; }
}
