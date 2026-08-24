package com.down.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

public class Story {

    public static final int MODE_DIALOG = 0, MODE_FIGHT = 1, MODE_WAIT = 2, MODE_RUN = 3, MODE_DONE = 4;

    public interface Host {
        void shTeleport(float x, float y);
        boolean shPlayerArrived();
        void shClearEnemies();
        Context shGetContext();
    }

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
    
    // Old objective system (kept for backward compatibility, though unused by new zones)
    public int objectiveQ = 0, objectiveR = 0;
    public boolean hasObjective = false;

    // New HUD/Objective system
    public String objective = "";
    private String note; 
    private float noteT;
    private String actCard; 
    private float actCardT;

    public Story(Host h) {
        host = h;
        // Script is now parsed and driven by StoryWorld singleton.
    }

    public void start() { 
        mode = MODE_RUN; 
        ended = false; 
        quitRequested = false;
    }

    public void update(float dt) {
        titleT += dt;
        if (mode == MODE_DIALOG) tw += dt * CPS;
    }

    public void tap() {
        if (mode != MODE_DIALOG) return;
        if (tw < text.length()) { 
            tw = text.length(); 
            return; 
        }
        dialogUp = false; 
        mode = MODE_RUN; 
    }

    public void fightWon() { 
        fightRequest = 0; 
        mode = MODE_RUN; 
    }
    
    public void fightLost() { 
        // Retry logic handled by GameView/StoryWorld
    }

    public void onObjectiveReached() {
        hasObjective = false;
        mode = MODE_RUN;
    }

    // =====================================================================
    // NEW BRIDGE METHODS (Driven by StoryWorld)
    // =====================================================================
    
    public void say(String speaker, String text) {
        this.speaker = speaker; 
        this.text = text;
        this.dialogUp = true; 
        this.tw = 0; 
        this.mode = MODE_DIALOG;
    }
    
    public boolean isOpen() { 
        return dialogUp; 
    }

    public void setObjective(String text) { 
        this.objective = text; 
    }

    public void onProgressFlag(String flag) {
        if (flag.equals("descent_open"))        objective = "Descend to the city.";
        else if (flag.equals("city_open"))      objective = "Fight through the falling city.";
        else if (flag.equals("courtyard_open")) objective = "Reach the Courtyard of Bones.";
        else if (flag.equals("velkarya_final_open")) objective = "Return to Velkarya.";
        else if (flag.equals("run_open"))       objective = "Run. Do not look back.";
        else if (flag.equals("ending_open"))    objective = "";
    }

    public void flashNote(String text) { 
        note = text; 
        noteT = 2.5f; 
    }

    public void showActCard(String title) { 
        actCard = title; 
        actCardT = 0f; 
        dialogUp = false; 
    }

    public boolean isWalkable(float x, float y) {
        return StoryWorld.sceneWalkable(x, y);
    }

    // =====================================================================
    // RENDERING
    // =====================================================================

    private String prefixFor(String who) {
        if (who == null) return null;
        if (who.startsWith("Nilou")) return "nilou";
        if (who.startsWith("Vex")) return "vex";
        if (who.startsWith("Vel")) return "vel";
        return null;
    }
    
    private int colorFor(String who) {
        // SAYCOLOR consult: script-defined accents override prefixes
        try {
            StoryWorld sw = StoryWorld.get(host.shGetContext(), null);
            int scriptCol = sw.speakerColor(who);
            if (scriptCol != 0) return scriptCol;
        } catch (Exception e) {
            // fallback if context/sound isn't fully initialized yet
        }
        
        String p = prefixFor(who);
        if ("nilou".equals(p)) return C_MAGENTA;
        if ("vex".equals(p)) return C_CYAN;
        if ("vel".equals(p)) return 0xFF34e3d6; // Green/Cyan for Velkarya
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

    public void drawHud(Canvas cv, int W, int H, Paint paint, Typeface body, Typeface logo) {
        if (objective.length() > 0) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(body);
            paint.setTextSize(24);
            paint.setColor(0xAAefe6dd);
            cv.drawText(objective, W / 2f, 60, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        if (noteT > 0 && note != null) {
            noteT -= 1f / 60f;
            paint.setAlpha((int) Math.min(255, noteT * 340f));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(body);
            paint.setTextSize(30);
            paint.setColor(0xFFC9C2B4);
            cv.drawText(note, W / 2f, H - 96f, paint);
            paint.setAlpha(255);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        if (actCard != null) {
            actCardT += 1f / 60f;
            paint.setAlpha((int) Math.min(255, actCardT * 90f));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(logo);
            paint.setTextSize(54);
            paint.setColor(0xFFC9C2B4);
            cv.drawText(actCard, W / 2f, H * 0.42f, paint);
            paint.setAlpha(255);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }
}
