package com.down.game;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public final class StoryWorld {
    private static StoryWorld inst;
    public static StoryWorld get(Context ctx, Sound snd) {
        if (inst == null) inst = new StoryWorld(ctx, snd);
        return inst;
    }

    public static final String PLAYER_KEY = "nilou";

    public static class Prop {
        public int sheet;   // 0 = props_a, 1 = props_b, 2 = props_city, 3 = gate
        public int idx;
        public int q, r;
        public float x, y;
        public float scale;
        public boolean flip;
        public boolean flat;
    }

    private static final int MAX_ZONES = 32;
    private final String[] zoneName = new String[MAX_ZONES];
    private final int[] zoneQ = new int[MAX_ZONES];
    private final int[] zoneR = new int[MAX_ZONES];
    private final int[] zoneR2 = new int[MAX_ZONES];
    private final int[] zoneState = new int[MAX_ZONES];
    private final String[] zoneGate = new String[MAX_ZONES];
    private final String[] zoneWait = new String[MAX_ZONES];
    private final List<String>[] zoneScript = new ArrayList[MAX_ZONES];
    private int zoneCount = 0;

    private final List<String> bootScript = new ArrayList<>(8);
    private final List<String> evQueue = new ArrayList<>(64);
    private boolean evActive = false;
    private float waitT = 0;
    private String waitWalk = null;
    private int waitTurns = 0;

    private final HashMap<String, Boolean> flags = new HashMap<>(24);

    private boolean encounterLive = false;
    private int reinforceKills = -1;
    private int reinforceTarget = 0;
    private int pendingWave = 0;
    private final int[][] waveSpawnHexes = { {64,1},{68,1},{70,4},{68,7},{64,7},{63,4} };
    private final int[][] fodderHexes   = { {61,2},{61,6},{63,1},{63,7},{66,0},{68,8} };

    private int killNoteN = -1;
    private String killNoteTxt = null;
    private int storyKills = 0;

    private final Context ctx;
    private final Sound snd;
    public SceneMap map;
    public StoryActors actors;
    public GameView gv;
    private int lastPQ = Integer.MIN_VALUE, lastPR = Integer.MIN_VALUE;
    private boolean victory = false;

    public float snapX, snapY;
    private boolean sceneEvent = false;

    public final float[] pt = new float[2];
    private final int[] HW2 = new int[2];
    public final ArrayList<Prop> placedProps = new ArrayList<>();

    public String skyKey = "";
    public boolean moltenOn, towersOn, hordeOn, flyersOn;
    public boolean scatterSet;
    public int scatterQ, scatterR, scatterRad, scatterN;

    private StoryWorld(Context ctx, Sound snd) {
        this.ctx = ctx.getApplicationContext();
        this.snd = snd;
        parse("act1.txt");
        runBoot();
    }

    public void attach(SceneMap map, StoryActors actors, GameView gv) {
        this.map = map; this.actors = actors; this.gv = gv;
    }

    public void reload() {
        zoneCount = 0;
        flags.clear();
        placedProps.clear();
        bootScript.clear();
        evQueue.clear();
        evActive = false;
        waitT = 0;
        waitWalk = null;
        waitTurns = 0;
        victory = false;
        encounterLive = false;
        reinforceKills = -1;
        reinforceTarget = 0;
        pendingWave = 0;
        scatterSet = false;
        killNoteN = -1;
        killNoteTxt = null;
        storyKills = 0;
        lastPQ = Integer.MIN_VALUE;
        lastPR = Integer.MIN_VALUE;
        parse("act1.txt");
        runBoot();
    }

    // Act-level set dressing: PROP/SCAR lines above the first ZONE run at start.
    private void runBoot() {
        for (int i = 0; i < bootScript.size(); i++) exec(bootScript.get(i));
    }

    private void parse(String file) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("story/" + file)));
            String line; List<String> sinkList = bootScript;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("ZONE ")) {
                    String[] p = line.split(" ");
                    int i = zoneCount++;
                    zoneName[i] = p[1];
                    zoneQ[i] = Integer.parseInt(p[2]);
                    zoneR[i] = Integer.parseInt(p[3]);
                    int rad = Integer.parseInt(p[4]);
                    zoneR2[i] = rad * rad;
                    zoneState[i] = 0;
                    zoneScript[i] = new ArrayList<>(24);
                    sinkList = zoneScript[i];
                } else if (line.startsWith("GATE ")) {
                    String[] p = line.split(" ");
                    int idx = findZone(p[1]);
                    if (idx >= 0) { zoneState[idx] = 3; zoneGate[idx] = p[2]; }
                } else if (line.startsWith("WAIT_FLAG ")) {
                    String[] p = line.split(" ");
                    int idx = findZone(p[1]);
                    if (idx >= 0) zoneWait[idx] = p[2];
                } else if (line.startsWith("ON_ENTER ")) {
                    int idx = findZone(line.split(" ")[1]);
                    sinkList = idx >= 0 ? zoneScript[idx] : null;
                } else if (sinkList != null) {
                    sinkList.add(line);
                }
            }
            r.close();
        } catch (Exception e) {}
    }

    private int findZone(String n) {
        for (int i = 0; i < zoneCount; i++) if (zoneName[i].equals(n)) return i;
        return -1;
    }

    public void onPlayerHexChanged(int q, int r) {
        if (q == lastPQ && r == lastPR) return;
        lastPQ = q; lastPR = r;
        rescanCurrentHex();
    }

    private void rescanCurrentHex() {
        if (evActive || lastPQ == Integer.MIN_VALUE) return;
        for (int i = 0; i < zoneCount; i++) {
            if (zoneState[i] != 0) continue;
            int dq = lastPQ - zoneQ[i], dr = lastPR - zoneR[i];
            if (dq * dq + dr * dr <= zoneR2[i]) {
                if (zoneWait[i] != null && !flag(zoneWait[i])) continue;
                zoneState[i] = 1;
                evQueue.clear();
                evQueue.addAll(zoneScript[i]);
                evActive = true;
                return;
            }
        }
    }

    public void onPlayerTurnEnd() {
        if (waitTurns > 0) waitTurns--;
    }

    public void update() {
        if (victory || !evActive) return;
        if (encounterLive) return;
        if (gv != null && gv.isDialogBlocking()) return;
        if (waitT > 0) { waitT -= 1 / 60f; return; }
        if (waitTurns > 0) return;
        if (waitWalk != null) {
            boolean w = PLAYER_KEY.equals(waitWalk)
                    ? (gv != null && gv.isScriptWalking())
                    : (actors != null && actors.isWalking(waitWalk));
            if (w) return;
            waitWalk = null;
        }
        if (evQueue.isEmpty()) {
            evActive = false;
            rescanCurrentHex();
            return;
        }
        exec(evQueue.remove(0));
    }

    private static int pi(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
    private static float pf(String s) {
        try { return Float.parseFloat(s); } catch (Exception e) { return 0.8f; }
    }
    private static boolean isInt(String s) {
        try { Integer.parseInt(s); return true; } catch (Exception e) { return false; }
    }

    private void exec(String cmd) {
        if (cmd.startsWith("SAY ")) {
            sayLine(cmd.substring(4));
        } else if (cmd.startsWith("SPAWN ")) {
            String[] p = cmd.split(" ");
            if (actors != null && p.length > 3) {
                String tag = null, alias = null;
                if (p.length > 4) {
                    if ("npc".equals(p[4]) || "ambient".equals(p[4])) tag = p[4];
                    else alias = p[4];
                }
                if (p.length > 5) alias = p[5];
                actors.add(p[1], pi(p[2]), pi(p[3]), tag, alias);
            }
        } else if (cmd.startsWith("DESPAWN ")) {
            if (actors != null) actors.despawn(cmd.split(" ")[1]);
        } else if (cmd.startsWith("WALK ")) {
            String[] p = cmd.split(" ");
            float dur = p.length > 4 ? pf(p[4]) : 0.8f;
            if (PLAYER_KEY.equals(p[1])) {
                if (gv != null) gv.scriptWalk(pi(p[2]), pi(p[3]), dur);
                waitWalk = PLAYER_KEY;
            } else if (actors != null) {
                actors.walkTo(p[1], pi(p[2]), pi(p[3]), dur);
            }
        } else if (cmd.startsWith("GLIDE ")) {
            String[] p = cmd.split(" ");
            if (gv != null) gv.scriptGlide(pi(p[1]), pi(p[2]), p.length > 3 ? pf(p[3]) : 1.2f);
            waitWalk = PLAYER_KEY;
        } else if (cmd.startsWith("EXIT ")) {
            String[] p = cmd.split(" ");
            if (actors != null) actors.exitTo(p[1], pi(p[2]), pi(p[3]));
        } else if (cmd.startsWith("FACE ")) {
            String[] p = cmd.split(" ");
            if (actors != null) actors.setFacing(p[1], p.length > 2 && "left".equals(p[2]) ? -1f : 1f);
        } else if (cmd.startsWith("WAIT_WALK ")) {
            waitWalk = cmd.split(" ")[1];
        } else if (cmd.startsWith("WAIT_TURNS ")) {
            waitTurns = pi(cmd.split(" ")[1]);
        } else if (cmd.startsWith("WAIT ")) {
            waitT = pi(cmd.split(" ")[1]) / 1000f;
        } else if (cmd.startsWith("TITLE ")) {
            if (gv != null) gv.showTitle(cmd.substring(6));
        } else if (cmd.startsWith("TELEPORT ")) {
            String[] p = cmd.split(" ");
            int i = 1;
            if (p.length > 3 && PLAYER_KEY.equals(p[1])) i = 2;
            SceneMap.hexToWorld(pi(p[i]), pi(p[i + 1]), pt);
            if (gv != null) gv.shTeleport(pt[0], pt[1]);
        } else if (cmd.startsWith("ZOOM ")) {
            String[] p = cmd.split(" ");
            if (gv != null) gv.scriptZoom(pf(p[1]), p.length > 2 ? pi(p[2]) : 600);
        } else if (cmd.startsWith("SFX_LOOP ")) {
            String[] p = cmd.split(" ");
            try { snd.playLoopTimed(p[1], p.length > 2 ? pf(p[2]) : 3f); } catch (Exception e) {}
        } else if (cmd.startsWith("CAM_PAN ")) {
            String[] p = cmd.split(" ");
            SceneMap.hexToWorld(pi(p[1]), pi(p[2]), pt);
            if (gv != null) gv.scriptCamPan(pt[0], pt[1], p.length > 3 ? pi(p[3]) : 1500);
        } else if (cmd.startsWith("CAM_PUSH ")) {
            String[] p = cmd.split(" ");
            if (gv != null) gv.scriptCamPush(p.length > 2 ? pi(p[2]) : 700);
        } else if (cmd.startsWith("CAM_FOLLOW ")) {
            if (gv != null) gv.scriptCamFollow(cmd.split(" ")[1]);
        } else if (cmd.startsWith("CAM_RELEASE")) {
            if (gv != null) gv.scriptCamRelease();
        } else if (cmd.startsWith("CAM_LOOK ")) {
            String[] p = cmd.split(" ");
            if (gv != null) gv.scriptCamLook(pi(p[1]), pi(p[2]),
                    p.length > 3 ? Integer.parseInt(p[3]) : 3000);
        } else if (cmd.startsWith("SKY ")) {
            skyKey = cmd.split(" ")[1];
        } else if (cmd.startsWith("LAYER ")) {
            String[] p = cmd.split(" ");
            if (p.length > 2 && "molten".equals(p[1])) moltenOn = "on".equals(p[2]);
        } else if (cmd.startsWith("BACKDROP ")) {
            String[] p = cmd.split(" ");
            if (p.length > 2 && "towers_burning".equals(p[1])) towersOn = "on".equals(p[2]);
        } else if (cmd.startsWith("HORDE ")) {
            hordeOn = cmd.endsWith("on");
        } else if (cmd.startsWith("FLYERS ")) {
            flyersOn = cmd.endsWith("on");
        } else if (cmd.startsWith("SCATTER ")) {
            String[] p = cmd.split(" ");
            if (p.length > 5) {
                scatterSet = true;
                scatterQ = pi(p[2]); scatterR = pi(p[3]);
                scatterRad = pi(p[4]); scatterN = pi(p[5]);
            }
        } else if (cmd.startsWith("PROP ") || cmd.startsWith("SCAR ")) {
            String[] p = cmd.split(" ");
            if (p.length > 4) {
                Prop pr = new Prop();
                pr.sheet = "a".equals(p[1]) ? 0 : ("b".equals(p[1]) ? 1 : ("gate".equals(p[1]) ? 3 : 2));
                pr.idx = pi(p[2]);
                pr.q = pi(p[3]); pr.r = pi(p[4]);
                SceneMap.hexToWorld(pr.q, pr.r, pt);
                pr.x = pt[0]; pr.y = pt[1];
                pr.scale = p.length > 5 ? pf(p[5]) : 1f;
                pr.flip = p.length > 6 && "1".equals(p[6]);
                pr.flat = cmd.startsWith("SCAR");
                placedProps.add(pr);
            }
        } else if (cmd.startsWith("KILLNOTE ")) {
            String[] p = cmd.split(" ", 3);
            killNoteN = pi(p[1]);
            killNoteTxt = p.length > 2 ? p[2] : "";
            storyKills = 0;
        } else if (cmd.startsWith("SFX ") || cmd.startsWith("AMBIENT ")) {
            try { snd.play(cmd.split(" ")[1]); } catch (Exception e) {}
        } else if (cmd.startsWith("FX ")) {
            String[] p = cmd.split(" ");
            if (gv == null) return;
            if (p.length > 3 && isInt(p[2])) {
                SceneMap.hexToWorld(pi(p[2]), pi(p[3]), pt);
                gv.fxPoint(p[1], pt[0], pt[1]);
            } else if (p.length > 2) {
                gv.fxActor(p[1], p[2]);
            }
        } else if (cmd.startsWith("FIGHT ")) {
            int n = pi(cmd.split(" ")[1]);
            if (n > 8) pendingWave = n - 6;
            startEncounter();
        } else if (cmd.startsWith("REINFORCE ")) {
            reinforceKills = 0;
            reinforceTarget = Integer.parseInt(cmd.split(" ")[1]);
        } else if (cmd.startsWith("ACTION ")) {
            String[] p = cmd.split(" ");
            if (p[1].equals("decal")) {
                if (gv != null) gv.fxDecal(p.length > 2 ? p[2] : "blood");
            } else {
                runAction(p[1], p.length > 2 ? Integer.parseInt(p[2]) : 0);
            }
        } else if (cmd.startsWith("SETFLAG ")) {
            setFlag(cmd.split(" ")[1]);
        } else if (cmd.startsWith("VICTORY")) {
            victory = true;
            if (gv != null) gv.onActComplete();
        }
    }

    private void sayLine(String rest) {
        int sp = rest.indexOf(' ');
        String speaker = sp < 0 ? rest : rest.substring(0, sp);
        speaker = speaker.replace('_', ' ');
        String text = sp < 0 ? "" : rest.substring(sp + 1);
        if (gv != null) gv.showDialog(speaker, text);
    }

    private void runAction(String name, int ms) {
        if (gv == null) return;
        if (name.equals("shake")) gv.fxShake(ms);
        else if (name.equals("flash")) gv.fxFlash(ms);
    }

    private void startEncounter() {
        encounterLive = true;
        storyKills = 0;
        if (actors != null && gv != null) {
            for (int i = 0; i < actors.size(); i++) {
                StoryActor a = actors.get(i);
                if (a.isEnemy() && !a.hidden) {
                    a.hidden = true;
                    gv.spawnReinforcement(a.type, a.q, a.r);
                }
            }
        }
    }

    public void onEnemyDeath() {
        storyKills++;
        if (killNoteTxt != null && storyKills == killNoteN) {
            if (gv != null) gv.flashNote(killNoteTxt);
            killNoteTxt = null;
        }
        if (reinforceKills >= 0 && reinforceKills < reinforceTarget) {
            reinforceKills++;
            if (gv.enemiesAlive() < 7) {
                int[] h = fodderHexes[reinforceKills % fodderHexes.length];
                gv.spawnReinforcement("fodder", h[0], h[1]);
            }
        }
        if (reinforceKills >= reinforceTarget && reinforceTarget > 0) {
            setFlag("courtyard_cleared");
            reinforceKills = -1;
        }
    }

    public void onEnemyCountLow(int alive) {
        if (pendingWave > 0 && alive <= 2) {
            pendingWave = 0;
            if (gv != null) {
                gv.noteWave();
                for (int i = 0; i < 6; i++) {
                    int[] h = waveSpawnHexes[i];
                    gv.spawnReinforcement("skirmisher", h[0], h[1]);
                }
            }
        }
    }

    public void endEncounter() {
        encounterLive = false;
        if (gv != null) gv.refreshDock();
    }

    public boolean encounterLive() { return encounterLive; }
    public boolean isVictory() { return victory; }
    public boolean flag(String f) {
        Boolean b = flags.get(f); return b != null && b;
    }
    public void setFlag(String f) {
        flags.put(f, Boolean.TRUE);
        for (int i = 0; i < zoneCount; i++) {
            if (zoneState[i] == 3 && f.equals(zoneGate[i])) zoneState[i] = 0;
        }
        if (f.equals("ending_open") && map != null) map.setCraterVisible(true);
        if (gv != null) gv.onProgressFlag(f);
    }

    public int remainingReinforcements() {
        return reinforceKills >= 0 ? reinforceTarget - reinforceKills : -1;
    }

    public void saveState() {}
    public void restoreState() {}

    public static boolean sceneWalkable(float x, float y) {
        if (inst == null) return true;
        if (inst.map != null && !inst.map.walkWorld(x, y)) return false;
        return !inst.propBlockedWorld(x, y);
    }

    // Gate footprint: arch hex stays open, the wall zigzag north/south blocks.
    private boolean propBlockedWorld(float x, float y) {
        SceneMap.worldToHex(x, y, HW2);
        int q = HW2[0], r = HW2[1];
        for (int i = 0; i < placedProps.size(); i++) {
            Prop pr = placedProps.get(i);
            if (pr.sheet != 3) continue;
            int gq = pr.q, gr = pr.r;
            if (q == gq && r == gr) continue;
            if (q == gq && (r == gr - 1 || r == gr + 1)) return true;
            if (q == gq + 1 && (r == gr - 2 || r == gr + 2)) return true;
        }
        return false;
    }

    public boolean takeSceneEvent() {
        if (sceneEvent) { sceneEvent = false; return true; }
        return false;
    }

    public boolean filterAction(String act) { return false; }
    public void npcCommand(String act) {}
    public void resolveActionPoint(String act, float fx, float fy) { pt[0] = fx; pt[1] = fy; }
    public void drawWorld(android.graphics.Canvas cv, float camX, float camY, float zoom, int W, int H, int quality, float t) {
        if (map != null) map.draw(cv, camX, camY, zoom, W, H);
    }
    public void drawOver(android.graphics.Canvas cv, float camX, float camY, float zoom, int W, int H, int quality, float t) {}
}
