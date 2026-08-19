package com.down.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class StoryWorld {

    private static StoryWorld instance;
    private final Context ctx;
    private final Sound sound;
    private final SceneMap sceneMap = new SceneMap();
    private final StoryActors actors = new StoryActors();

    private boolean loaded;
    private String currentScene;
    private float fadeT;
    private float animClock;

    private final ArrayList<Beat> beats = new ArrayList<>();
    private int bi;
    private String lastSpeaker, lastText;
    private boolean encounterOpen, encounterWon;
    private int encounterN;
    private final int[][] encounterHex = new int[8][2];
    private boolean sceneEvent;
    public float snapX, snapY;

    public final float[] pt = new float[2];

    private StoryWorld(Context c, Sound s) {
        ctx = c.getApplicationContext();
        sound = s;
        Thread loader = new Thread(new Runnable() { public void run() {
            parse();
            loaded = true;
        } }, "NV-storyworld");
        loader.setPriority(Thread.NORM_PRIORITY - 1);
        loader.start();
    }

    public static StoryWorld get(Context c, Sound s) {
        if (instance == null) instance = new StoryWorld(c, s);
        return instance;
    }

    public static boolean sceneWalkable(float x, float y) {
        return instance == null || instance.sceneMap == null || !instance.loaded
                ? true : instance.sceneMap.isWalkable(x, y);
    }

    private static class Beat {
        int type; // 0=SAY, 1=ACTION/NPC, 2=SCENE
        String who, text, cmd, arg;
        int n;
    }

    private void parse() {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("story/act1.txt")));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split(" ", 2);
                String cmd = p[0], arg = p.length > 1 ? p[1] : "";
                Beat b = new Beat();
                if (cmd.equals("NAME")) { b.type = 2; b.cmd = "NAME"; b.arg = arg; }
                else if (cmd.equals("GROUND")) { b.type = 2; b.cmd = "GROUND"; b.arg = arg; }
                else if (cmd.equals("SAY")) {
                    String[] p2 = arg.split(" ", 2);
                    b.type = 0; b.who = p2[0]; b.text = p2.length > 1 ? p2[1] : "";
                }
                else if (cmd.equals("ACTION") || cmd.equals("SHOW") || cmd.equals("HIDE")
                        || cmd.equals("EXIT") || cmd.equals("ACTOR") || cmd.equals("PLACE")
                        || cmd.equals("CRACK") || cmd.equals("RESET")) {
                    b.type = 1; b.cmd = cmd; b.arg = arg;
                }
                else if (cmd.equals("FIGHT")) { b.type = 1; b.cmd = "FIGHT"; b.n = Integer.parseInt(arg); }
                else if (cmd.equals("WALK")) { b.type = 1; b.cmd = "WALK"; b.arg = arg; }
                beats.add(b);
            }
        } catch (Exception e) {}
    }

    public void tick(Story story) {
        if (!loaded || story == null) return;
        float dt = 1f / 60f;
        animClock += dt;
        if (fadeT > 0) fadeT = Math.max(0, fadeT - dt * 3f);

        if (story.titleT > 0 && story.titleT < 0.1f && !story.title.equals(currentScene)) {
            currentScene = story.title;
            sceneMap.begin(currentScene, story.groundColor);
            actors.reset();
            bi = 0;
            lastSpeaker = null;
            lastText = null;
            encounterOpen = false;
            encounterWon = false;
            fadeT = 1f;
            sceneEvent = true;
            snapX = story.hasObjective ? SceneMap.hexX(story.objectiveQ, story.objectiveR) : 0;
            snapY = story.hasObjective ? SceneMap.hexY(story.objectiveQ, story.objectiveR) : 0;
            if (sound != null) sound.play("ui");
        }

        while (bi < beats.size()) {
            Beat b = beats.get(bi);
            if (b.type == 2) {
                bi++;
                if (b.cmd.equals("NAME")) sceneMap.begin(b.arg, story.groundColor);
                continue;
            }
            if (b.type == 0) {
                if (story.dialogUp && b.who.equals(story.speaker) && story.text.startsWith(b.text)) {
                    bi++;
                    lastSpeaker = b.who;
                    lastText = b.text;
                } else break;
            } else {
                bi++;
                if (b.cmd.equals("PLACE")) {
                    String[] t = b.arg.split(" ");
                    if (t.length >= 3) sceneMap.prop(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]));
                } else if (b.cmd.equals("CRACK")) {
                    String[] t = b.arg.split(" ");
                    if (t.length >= 4) sceneMap.crack(Integer.parseInt(t[0]), Integer.parseInt(t[1]),
                            Integer.parseInt(t[2]), Integer.parseInt(t[3]));
                } else if (b.cmd.equals("ACTOR")) {
                    String[] t = b.arg.split(" ");
                    if (t.length >= 3) {
                        boolean hidden = t.length >= 4 && "hidden".equals(t[3]);
                        actors.add(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]), hidden);
                    }
                } else if (b.cmd.equals("FIGHT")) {
                    story.fightRequest = b.n;
                    break;
                }
            }
        }
        actors.update(dt);
        sceneMap.tick(dt);
    }

    public boolean filterAction(String act) {
        if (!encounterOpen) return false;
        if (act.startsWith("ACTION slash") || act.startsWith("ACTION blood") || act.startsWith("HIDE ")) {
            return encounterWon;
        }
        return false;
    }

    public void resolveActionPoint(String act, float fx, float fy) {
        pt[0] = fx; pt[1] = fy;
        for (int i = act.length() - 1, j = 0; i >= 0; i--) {
            char c = act.charAt(i);
            if (c == ' ') j++;
            if (j == 2) {
                String[] p = act.substring(i + 1).split(" ");
                if (p.length >= 2) {
                    try {
                        int q = Integer.parseInt(p[0]), r = Integer.parseInt(p[1]);
                        StoryActor a = actors.getAt(q, r);
                        if (a != null) { pt[0] = a.x; pt[1] = a.y; return; }
                        pt[0] = SceneMap.hexX(q, r);
                        pt[1] = SceneMap.hexY(q, r);
                    } catch (Exception e) {}
                }
                break;
            }
        }
    }

    public void npcCommand(String act) {
        if (act.startsWith("SHOW ")) actors.show(act.substring(5).trim());
        else if (act.startsWith("HIDE ")) actors.hide(act.substring(5).trim());
        else if (act.startsWith("WALK ")) {
            String[] t = act.substring(5).split(" ");
            if (t.length >= 3) actors.walkTo(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]));
        }
        else if (act.startsWith("EXIT ")) {
            String[] t = act.substring(5).split(" ");
            if (t.length >= 3) actors.exitTo(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]));
        }
        else if (act.startsWith("ACTOR ")) {
            String[] t = act.substring(6).split(" ");
            if (t.length >= 3) {
                boolean hidden = t.length >= 4 && "hidden".equals(t[3]);
                actors.add(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]), hidden);
            }
        }
    }

    public boolean openEncounter() {
        if (encounterOpen) return false;
        encounterOpen = true;
        encounterWon = false;
        encounterN = 0;
        for (int i = 0; i < actors.size(); i++) {
            StoryActor a = actors.get(i);
            if (!a.hidden && a.isEnemy()) {
                if (encounterN < encounterHex.length) {
                    encounterHex[encounterN][0] = a.q;
                    encounterHex[encounterN][1] = a.r;
                    encounterN++;
                }
                actors.hide(a.name);
            }
        }
        return encounterN > 0;
    }

    public void closeEncounter(boolean won) {
        encounterOpen = false;
        encounterWon = won;
    }

    public boolean encounterOpen() { return encounterOpen; }
    public boolean encounterLive() { return encounterOpen && !encounterWon; }
    public int encounterCount() { return encounterN; }
    public int[] encounterHex(int i) { return encounterHex[i]; }

    public boolean takeSceneEvent() {
        if (sceneEvent) { sceneEvent = false; return true; }
        return false;
    }

    public void drawWorld(Canvas cv, float camX, float camY, float zoom, int W, int H, int quality, float t) {
        if (!loaded || currentScene == null) return;
        sceneMap.draw(cv, camX, camY, zoom, W, H, quality, animClock);
        actors.draw(cv, camX, camY, zoom, W, H, animClock);
    }

    public void drawOver(Canvas cv, float camX, float camY, float zoom, int W, int H, int quality, float t) {
        if (!loaded || currentScene == null) return;
        if (fadeT > 0) {
            cv.drawColor(Color.argb((int)(fadeT * 255), 0, 0, 0));
        }
    }
}
