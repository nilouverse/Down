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

    private final List<String> evQueue = new ArrayList<>(64);
    private boolean evActive = false;

    private final HashMap<String, Boolean> flags = new HashMap<>(24);

    private boolean encounterLive = false;
    private int reinforceKills = -1;
    private int reinforceTarget = 0;
    private int pendingWave = 0;
    private final int[][] waveSpawnHexes = { {64,1},{68,1},{70,4},{68,7},{64,7},{63,4} };
    private final int[][] fodderHexes   = { {61,2},{61,6},{63,1},{63,7},{66,0},{68,8} };

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

    private static final String[] SPEAKERS = {
            "Wounded Soldier", "Thornborn Scout", "Carrion Infantry Leader",
            "NilouZila", "Velkarya"
    };

    private StoryWorld(Context ctx, Sound snd) {
        this.ctx = ctx.getApplicationContext();
        this.snd = snd;
        parse("act1.txt");
    }

    public void attach(SceneMap map, StoryActors actors, GameView gv) {
        this.map = map; this.actors = actors; this.gv = gv;
    }

    private void parse(String file) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("story/" + file)));
            String line; String curZone = null;
            List<String> sink = null;
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
                    curZone = p[1]; sink = zoneScript[i];
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
                    sink = idx >= 0 ? zoneScript[idx] : null;
                } else if (sink != null) {
                    sink.add(line);
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

    public void update() {
        if (victory || !evActive) return;
        if (encounterLive) return;
        if (gv != null && gv.isDialogBlocking()) return;
        if (evQueue.isEmpty()) {
            evActive = false;
            rescanCurrentHex();
            return;
        }
        exec(evQueue.remove(0));
    }

    private void exec(String cmd) {
        if (cmd.startsWith("SAY ")) {
            sayLine(cmd.substring(4));
        } else if (cmd.startsWith("SPAWN ")) {
            String[] p = cmd.split(" ");
            actors.spawn(p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } else if (cmd.startsWith("DESPAWN ")) {
            actors.despawn(cmd.split(" ")[1]);
        } else if (cmd.startsWith("FIGHT ")) {
            int n = Integer.parseInt(cmd.split(" ")[1]);
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
        } else if (cmd.startsWith("CAM_LOOK ")) {
            String[] p = cmd.split(" ");
            gv.scriptCamLook(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                    p.length > 3 ? Integer.parseInt(p[3]) : 3000);
        } else if (cmd.startsWith("VICTORY")) {
            victory = true;
            gv.onActComplete();
        }
    }

    private void sayLine(String rest) {
        String speaker = null, text = null;
        for (String s : SPEAKERS) {
            if (rest.startsWith(s + " ")) { speaker = s; text = rest.substring(s.length() + 1); break; }
        }
        if (speaker == null) {
            int sp = rest.indexOf(' ');
            if (sp < 0) { speaker = rest; text = ""; }
            else { speaker = rest.substring(0, sp); text = rest.substring(sp + 1); }
        }
        gv.showDialog(speaker, text);
    }

    private void runAction(String name, int ms) {
        if (gv == null) return;
        if (name.equals("shake")) gv.fxShake(ms);
        else if (name.equals("flash")) gv.fxFlash(ms);
    }

    private void startEncounter() {
        encounterLive = true;
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
        return inst == null || inst.map == null ? true : inst.map.walkWorld(x, y);
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
