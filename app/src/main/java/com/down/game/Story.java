package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public class Story {

    private static final float SQUASH = 0.6f;
    private static final float HEX = 96f;
    private static final float TILE = 192f;
    private static final float TH = TILE * SQUASH;

    private final Context ctx;
    private final Paint paint = new Paint();
    private final RectF rf = new RectF();
    private final Rect frameSrc = new Rect();

    private float camX, camY, zoom = 1.2f;
    private int W, H;
    private boolean camSnap;
    private float shakeX, shakeY;

    private String sectionName = "";
    private int groundCol = 0xFF1c1320;
    private float nameT = 9;

    private static class Crack { float x1, y1, x2, y2; }
    private final ArrayList<Crack> cracks = new ArrayList<>();

    private static class Prop { Bitmap bmp; float ax, ay, s; boolean decal; }
    private final ArrayList<Prop> props = new ArrayList<>();
    private final HashSet<Long> blocked = new HashSet<>();

    private static class Actor {
        String id;
        float x, y, tx, ty;
        int tq = 99999, tr;
        int facing = 1;
        float animT = (float) (Math.random() * 10);
        float h = 200;
        boolean moving, hidden, hideOnArrive;
        ArrayList<Frame> idle, glide;
    }
    private final ArrayList<Actor> actors = new ArrayList<>();
    private Actor player;

    private static class Trigger { int q, r, rad; String sec; }
    private final ArrayList<Trigger> triggers = new ArrayList<>();

    private static final int EV_SAY = 0, EV_ACTION = 1, EV_WALK = 2,
            EV_SHOW = 3, EV_HIDE = 4, EV_CONTROL = 5;
    private static class Ev { int type; String a, b; int q, r; }
    private final ArrayList<Ev> evs = new ArrayList<>();
    private int idx;
    private boolean dialogUp, control, ended;
    public boolean quitRequested;
    private float flashT = 9, flashStr;
    private float pushT = 9;
    private float fade = 1, fadeDir = -1;
    private String pendingSection;

    private float downX = -9999, downY, lastPX, lastPY;
    private boolean moved;

    private static final String[] PROP_NAMES = {
            "gate", "spire", "wall", "bonepillar", "rubble", "street", "barricade", "bones" };
    private static final boolean[] PROP_DECAL = {
            false, false, false, false, false, true, false, true };
    private static final boolean[] PROP_BLOCK = {
            true, true, true, true, false, false, true, false };
    private List<Bitmap> city;

    private static class SD { float y; Prop p; Actor a; }
    private final ArrayList<SD> sdList = new ArrayList<>();
    private static final Comparator<SD> BY_Y = new Comparator<SD>() {
        public int compare(SD a, SD b) { return a.y < b.y ? -1 : (a.y > b.y ? 1 : 0); }
    };

    private static final float[] FW_A = new float[2];
    private static final int[] IH_A = new int[2];
    private static final int[] IH_B = new int[2];

    public Story(Context c) {
        ctx = c;
        paint.setFilterBitmap(true);
        city = Sprites.cutSheet(c, "sprites/props_city.png", 2, 4, 4);
    }

    // ---------- hex math ----------
    private static void hexToWorld(int q, int r, float[] out) {
        out[0] = HEX * (float) Math.sqrt(3) * (q + r / 2f);
        out[1] = HEX * 1.5f * r * SQUASH;
    }

    private static void worldToHex(float x, float y, int[] out) {
        float hy = y / SQUASH;
        float qf = ((float) Math.sqrt(3) / 3f * x - 1f / 3f * hy) / HEX;
        float rf2 = (2f / 3f * hy) / HEX;
        float sf = -qf - rf2;
        int rq = Math.round(qf), rr = Math.round(rf2), rs = Math.round(sf);
        float dq = Math.abs(rq - qf), dr = Math.abs(rr - rf2), ds = Math.abs(rs - sf);
        if (dq > dr && dq > ds) rq = -rr - rs;
        else if (dr > ds) rr = -rq - rs;
        out[0] = rq; out[1] = rr;
    }

    private static int hexDist(int q1, int r1, int q2, int r2) {
        int dq = q1 - q2, dr = r1 - r2;
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
    }

    private static long hexKey(int q, int r) {
        return ((long) q << 32) | (r & 0xFFFFFFFFL);
    }

    // ---------- loading ----------
    public void load(String name) {
        evs.clear(); actors.clear(); props.clear(); cracks.clear();
        blocked.clear(); triggers.clear();
        idx = 0; dialogUp = false; control = false; ended = false;
        camSnap = false; nameT = 0; fade = 1; fadeDir = -1;
        pushT = 9; flashT = 9;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open("story/" + name + ".txt")));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                String[] t = line.split("\\s+");
                String cmd = t[0];
                if (cmd.equals("NAME")) {
                    sectionName = line.substring(4).trim();
                } else if (cmd.equals("GROUND")) {
                    groundCol = (int) (Long.parseLong(t[1].substring(2), 16) | 0xFF000000L);
                } else if (cmd.equals("CRACK")) {
                    Crack c = new Crack();
                    hexToWorld(Integer.parseInt(t[1]), Integer.parseInt(t[2]), FW_A);
                    c.x1 = FW_A[0]; c.y1 = FW_A[1];
                    hexToWorld(Integer.parseInt(t[3]), Integer.parseInt(t[4]), FW_A);
                    c.x2 = FW_A[0]; c.y2 = FW_A[1];
                    cracks.add(c);
                } else if (cmd.equals("PLACE")) {
                    placeProp(t);
                } else if (cmd.equals("ACTOR")) {
                    addActor(t);
                } else if (cmd.equals("TRIGGER")) {
                    Trigger tr = new Trigger();
                    tr.q = Integer.parseInt(t[1]);
                    tr.r = Integer.parseInt(t[2]);
                    tr.rad = Integer.parseInt(t[3]);
                    tr.sec = t[4];
                    triggers.add(tr);
                } else {
                    Ev ev = new Ev();
                    if (cmd.equals("SAY")) {
                        ev.type = EV_SAY;
                        ev.a = t[1];
                        ev.b = line.substring(line.indexOf(t[1]) + t[1].length()).trim();
                    } else if (cmd.equals("ACTION")) {
                        ev.type = EV_ACTION; ev.a = t[1];
                    } else if (cmd.equals("WALK")) {
                        ev.type = EV_WALK; ev.a = t[1];
                        ev.q = Integer.parseInt(t[2]); ev.r = Integer.parseInt(t[3]);
                    } else if (cmd.equals("EXIT")) {
                        ev.type = EV_WALK; ev.a = t[1];
                        ev.q = Integer.parseInt(t[2]); ev.r = Integer.parseInt(t[3]);
                        ev.b = "hide";
                    } else if (cmd.equals("SHOW")) {
                        ev.type = EV_SHOW; ev.a = t[1];
                    } else if (cmd.equals("HIDE")) {
                        ev.type = EV_HIDE; ev.a = t[1];
                    } else if (cmd.equals("CONTROL")) {
                        ev.type = EV_CONTROL;
                    } else continue;
                    evs.add(ev);
                }
            }
            br.close();
        } catch (Exception e) {
            ended = true;
        }
        player = findActor("nilou");
        if (player == null && !actors.isEmpty()) player = actors.get(0);
        if (actors.isEmpty()) ended = true;
    }

    private void placeProp(String[] t) {
        int pi = -1;
        for (int i = 0; i < PROP_NAMES.length; i++) {
            if (PROP_NAMES[i].equals(t[1])) { pi = i; break; }
        }
        if (pi < 0 || city.isEmpty()) return;
        int q = Integer.parseInt(t[2]), r = Integer.parseInt(t[3]);
        boolean block = PROP_BLOCK[pi];
        if (t.length > 4) block = t[4].equals("block");
        Bitmap bmp = city.get(pi);
        Prop p = new Prop();
        p.bmp = bmp;
        p.decal = PROP_DECAL[pi];
        hexToWorld(q, r, FW_A);
        p.ax = FW_A[0];
        p.ay = FW_A[1] + TH * 0.3f;
        p.s = p.decal ? (TILE * 1.8f) / bmp.getWidth() : (TH * 2.3f) / bmp.getHeight();
        props.add(p);
        if (block) {
            blocked.add(hexKey(q, r));
            if (!p.decal) blocked.add(hexKey(q, r - 1));
        }
    }

    private void addActor(String[] t) {
        Actor a = new Actor();
        a.id = t[1];
        int q = Integer.parseInt(t[2]), r = Integer.parseInt(t[3]);
        hexToWorld(q, r, FW_A);
        a.x = a.tx = FW_A[0];
        a.y = a.ty = FW_A[1];
        if (t.length > 4 && t[4].equals("left")) a.facing = -1;
        if (t.length > 5 && t[5].equals("hidden")) a.hidden = true;
        if (a.id.equals("nilou")) {
            a.idle = Sprites.buildFrames(Sprites.cutSheet(ctx, "sprites/idle.png", 2, 2, 4), false, true);
            a.glide = Sprites.buildFrames(Sprites.cutSheet(ctx, "sprites/glide.png", 2, 2, 2), true, false);
            if (a.idle.size() >= 4) { a.idle.get(1).dx = -8f; a.idle.get(2).dx = -8f; }
            a.h = 220;
        } else {
            List<Frame> w = Sprites.buildFrames(Sprites.cutSheet(ctx, "sprites/soldier.png", 2, 2, 12), false, true);
            a.glide = new ArrayList<>(w);
            a.idle = new ArrayList<>();
            if (!w.isEmpty()) a.idle.add(w.get(0));
            a.h = 200;
        }
        actors.add(a);
    }

    private Actor findActor(String id) {
        for (Actor a : actors) if (a.id.equals(id)) return a;
        return null;
    }

    private boolean hexFree(int q, int r) {
        if (blocked.contains(hexKey(q, r))) return false;
        for (Actor a : actors) {
            if (a.hidden) continue;
            worldToHex(a.x, a.y, IH_A);
            if (IH_A[0] == q && IH_A[1] == r) return false;
        }
        return true;
    }

    // ---------- update ----------
    public void update(float dt) {
        nameT += dt;
        flashT += dt;
        pushT += dt;

        if (fadeDir == -1) {
            fade -= dt * 2.5f;
            if (fade <= 0) { fade = 0; fadeDir = 0; }
        } else if (fadeDir == 1) {
            fade += dt * 2.5f;
            if (fade >= 1) {
                fade = 1;
                if (pendingSection != null) {
                    load(pendingSection);
                    pendingSection = null;
                }
                fadeDir = -1;
            }
        }

        for (Actor a : actors) {
            a.animT += dt;
            float dx = a.tx - a.x, dy = a.ty - a.y;
            float d = (float) Math.hypot(dx, dy);
            a.moving = d > 4;
            if (a.moving) {
                float step = Math.min(d, 260 * dt);
                a.x += dx / d * step;
                a.y += dy / d * step;
                if (dx < -0.05f) a.facing = -1;
                if (dx > 0.05f) a.facing = 1;
            } else if (a.hideOnArrive) {
                a.hidden = true;
                a.hideOnArrive = false;
            }
        }

        if (!ended && fadeDir == 0) {
            while (idx < evs.size()) {
                Ev ev = evs.get(idx);
                if (ev.type == EV_ACTION) {
                    if (ev.a.equals("flash")) { flashT = 0; flashStr = 1; }
                    else if (ev.a.equals("flash2")) { flashT = 0; flashStr = 0.4f; }
                    else if (ev.a.equals("push")) { pushT = 0; }
                    idx++;
                } else if (ev.type == EV_SHOW) {
                    Actor a = findActor(ev.a);
                    if (a != null) a.hidden = false;
                    idx++;
                } else if (ev.type == EV_HIDE) {
                    Actor a = findActor(ev.a);
                    if (a != null) a.hidden = true;
                    idx++;
                } else if (ev.type == EV_WALK) {
                    Actor a = findActor(ev.a);
                    if (a == null) { idx++; continue; }
                    if (a.tq != ev.q || a.tr != ev.r) {
                        a.tq = ev.q; a.tr = ev.r;
                        hexToWorld(ev.q, ev.r, FW_A);
                        a.tx = FW_A[0]; a.ty = FW_A[1];
                        a.hideOnArrive = ev.b != null;
                    }
                    if (!a.moving && Math.hypot(a.tx - a.x, a.ty - a.y) <= 4) {
                        if (a.hideOnArrive) { a.hidden = true; a.hideOnArrive = false; }
                        a.tq = 99999;
                        idx++;
                    } else break;
                } else if (ev.type == EV_CONTROL) {
                    control = true;
                    dialogUp = false;
                    idx++;
                    break;
                } else {
                    dialogUp = true;
                    break;
                }
            }
            if (idx >= evs.size()) { dialogUp = false; control = true; }
        }

        if (control && !ended && player != null) {
            worldToHex(player.x, player.y, IH_A);
            for (Trigger tr : triggers) {
                if (hexDist(IH_A[0], IH_A[1], tr.q, tr.r) <= tr.rad) {
                    pendingSection = tr.sec;
                    fadeDir = 1;
                    control = false;
                    break;
                }
            }
        }

        if (player != null && H > 0) {
            if (!camSnap) {
                camX = player.x;
                camY = player.y - (H * 0.25f) / zoom;
                camSnap = true;
            }
            float k = 1 - (float) Math.exp(-dt * 6);
            float push = (pushT < 1.5f)
                    ? 0.35f * (float) Math.sin(Math.min(pushT, 1.5f) / 1.5f * (float) Math.PI) : 0f;
            zoom += ((1.2f + push) - zoom) * k;
            camX += (player.x - camX) * k;
            camY += ((player.y - (H * 0.25f) / zoom) - camY) * k;
        }
    }

    // ---------- touch ----------
    public boolean touch(MotionEvent e) {
        int act = e.getActionMasked();
        if (ended) {
            if (act == MotionEvent.ACTION_UP) quitRequested = true;
            return true;
        }
        if (fade > 0.05f) return true;
        if (dialogUp && act == MotionEvent.ACTION_UP) { idx++; return true; }
        if (act == MotionEvent.ACTION_DOWN) {
            downX = e.getX(); downY = e.getY();
            lastPX = downX; lastPY = downY;
            moved = false;
            return true;
        }
        if (act == MotionEvent.ACTION_MOVE) {
            if (downX < -9000) return true;
            float x = e.getX(), y = e.getY();
            if (!moved && Math.hypot(x - downX, y - downY) > 26) moved = true;
            if (moved) {
                camX -= (x - lastPX) / zoom;
                camY -= (y - lastPY) / zoom;
                lastPX = x; lastPY = y;
            }
            return true;
        }
        if (act != MotionEvent.ACTION_UP) return true;
        downX = -9999;
        if (moved || !control) return true;
        float x = e.getX(), y = e.getY();
        float wx = camX + (x - W / 2f) / zoom;
        float wy = camY + (y - H / 2f) / zoom;
        worldToHex(wx, wy, IH_A);
        worldToHex(player.x, player.y, IH_B);
        if (hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= 4 && hexFree(IH_A[0], IH_A[1])) {
            hexToWorld(IH_A[0], IH_A[1], FW_A);
            player.tx = FW_A[0]; player.ty = FW_A[1];
            player.tq = IH_A[0]; player.tr = IH_A[1];
        }
        return true;
    }

    // ---------- draw ----------
    private float sx(float wx) { return (wx - camX) * zoom + W / 2f + shakeX; }
    private float sy(float wy) { return (wy - camY) * zoom + H / 2f + shakeY; }

    public void draw(Canvas cv) {
        W = cv.getWidth(); H = cv.getHeight();
        shakeX = 0; shakeY = 0;
        if (flashT < 0.4f) {
            float k = 1 - flashT / 0.4f;
            shakeX = ((float) Math.random() * 8 - 4) * k * zoom;
            shakeY = ((float) Math.random() * 6 - 3) * k * zoom;
        }

        cv.drawColor(groundCol);

        for (Crack c : cracks) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(10 * zoom);
            paint.setColor(0x55ff3300);
            cv.drawLine(sx(c.x1), sy(c.y1), sx(c.x2), sy(c.y2), paint);
            paint.setStrokeWidth(3 * zoom);
            paint.setColor(0xFFff7a20);
            cv.drawLine(sx(c.x1), sy(c.y1), sx(c.x2), sy(c.y2), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        for (Prop p : props) if (p.decal) drawProp(cv, p);

        sdList.clear();
        for (Prop p : props) if (!p.decal) { SD s = new SD(); s.y = p.ay; s.p = p; sdList.add(s); }
        for (Actor a : actors) if (!a.hidden) { SD s = new SD(); s.y = a.y; s.a = a; sdList.add(s); }
        Collections.sort(sdList, BY_Y);
        for (SD s : sdList) {
            if (s.p != null) drawProp(cv, s.p);
            else drawActor(cv, s.a);
        }

        if (flashT < 0.5f) {
            paint.setColor(0xFFffddaa);
            paint.setAlpha((int) ((1 - flashT / 0.5f) * flashStr * 200));
            cv.drawRect(0, 0, W, H, paint);
            paint.setAlpha(255);
        }

        if (dialogUp && idx < evs.size()) {
            Ev ev = evs.get(idx);
            float ph = H * 0.24f;
            paint.setColor(0xDD120a18);
            cv.drawRect(0, H - ph, W, H, paint);
            paint.setColor(0xFFff2bd6);
            cv.drawRect(0, H - ph, W, H - ph + 3, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(H * 0.032f);
            paint.setColor(speakerColor(ev.a));
            cv.drawText(ev.a, 40, H - ph + H * 0.065f, paint);
            paint.setColor(0xFFeee6f2);
            paint.setTextSize(H * 0.028f);
            wrapText(cv, ev.b, 40, H - ph + H * 0.12f, W - 80, H * 0.045f);
            if (((int) (System.currentTimeMillis() / 400)) % 2 == 0) {
                paint.setColor(0x88ffffff);
                cv.drawText("▼", W - 60, H - 20, paint);
            }
        }

        if (nameT < 2.5f && sectionName.length() > 0) {
            int a = nameT < 2f ? 220 : (int) ((2.5f - nameT) / 0.5f * 220);
            paint.setAlpha(a);
            paint.setTextSize(54);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFFff2bd6);
            cv.drawText(sectionName, W / 2f, H * 0.22f, paint);
            paint.setAlpha(255);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        if (ended) {
            paint.setColor(0xCC000000);
            cv.drawRect(0, 0, W, H, paint);
            paint.setTextSize(64);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFFff2bd6);
            cv.drawText("TO BE CONTINUED", W / 2f, H / 2f, paint);
            paint.setTextSize(26);
            paint.setColor(0xAAffffff);
            cv.drawText("tap to return", W / 2f, H / 2f + 60, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        if (fade > 0) {
            paint.setColor(0xFF000000);
            paint.setAlpha((int) (fade * 255));
            cv.drawRect(0, 0, W, H, paint);
            paint.setAlpha(255);
        }
    }

    private void wrapText(Canvas cv, String text, float x, float y, float maxW, float lh) {
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        float cy = y;
        for (String w : words) {
            String test = line.length() == 0 ? w : line + " " + w;
            if (paint.measureText(test) > maxW && line.length() > 0) {
                cv.drawText(line.toString(), x, cy, paint);
                line = new StringBuilder(w);
                cy += lh;
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) cv.drawText(line.toString(), x, cy, paint);
    }

    private void drawProp(Canvas cv, Prop p) {
        rf.set(sx(p.ax) - p.bmp.getWidth() * p.s / 2f, sy(p.ay) - p.bmp.getHeight() * p.s,
               sx(p.ax) + p.bmp.getWidth() * p.s / 2f, sy(p.ay));
        paint.setAlpha(255);
        cv.drawBitmap(p.bmp, null, rf, paint);
    }

    private Bitmap shadowBmp;
    private Bitmap shadow() {
        if (shadowBmp == null) {
            shadowBmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(shadowBmp);
            Paint shp = new Paint();
            shp.setShader(new RadialGradient(64, 64, 62,
                    0xB4000000, 0x00000000, Shader.TileMode.CLAMP));
            sc.drawRect(0, 0, 128, 128, shp);
        }
        return shadowBmp;
    }

    private void drawActor(Canvas cv, Actor a) {
        float x = sx(a.x), y = sy(a.y);
        boolean idle = !a.moving;
        float br = idle ? (float) Math.sin(a.animT * 2.1f) : 0f;
        float sw = 45 * zoom * (1f - 0.03f * br);
        paint.setAlpha(200);
        rf.set(x - sw, y - sw * 0.36f, x + sw, y + sw * 0.36f);
        cv.drawBitmap(shadow(), null, rf, paint);
        paint.setAlpha(255);

        cv.save();
        cv.translate(x, y);
        if (a.facing < 0) cv.scale(-1, 1);
        if (br != 0f) cv.scale(1f - 0.012f * br, 1f + 0.02f * br);
        Frame fa = null, fb = null;
        float fk = 0;
        if (a.moving && a.glide != null && a.glide.size() >= 4) {
            float pos = a.animT * 6f;
            int i0 = 1 + ((int) pos) % 2;
            int i1 = 1 + (((int) pos) + 1) % 2;
            fa = a.glide.get(i0);
            fb = a.glide.get(i1);
            float fr = (pos - (int) pos);
            fk = (fr - 0.65f) / 0.35f;
            if (fk < 0) fk = 0;
            if (fk > 1) fk = 1;
        } else if (a.idle != null && !a.idle.isEmpty()) {
            fa = a.idle.get(((int) (a.animT * 3f)) % a.idle.size());
        }
        if (fa != null) drawFrame(cv, fa, 255, a.h);
        if (fb != null && fk > 0.02f) drawFrame(cv, fb, (int) (fk * 255), a.h);
        cv.restore();
    }

    private void drawFrame(Canvas cv, Frame f, int alpha, float hWorld) {
        float s = hWorld * zoom / f.ref;
        paint.setAlpha(alpha);
        if (f.vCrop) {
            frameSrc.set(0, f.top, f.bmp.getWidth(), f.top + f.ch);
            rf.set(-f.bmp.getWidth() * s / 2f, -f.ch * s, f.bmp.getWidth() * s / 2f, 0);
        } else if (f.cCenter) {
            int wl = Math.max(0, f.rgt - f.ww);
            int wr = f.rgt;
            frameSrc.set(wl, 0, wr, f.bmp.getHeight());
            float right = f.ww * s / 2f;
            rf.set(right - (wr - wl) * s, -f.bmp.getHeight() * s, right, 0);
        } else {
            frameSrc.set(0, 0, f.bmp.getWidth(), f.bmp.getHeight());
            rf.set(-f.bmp.getWidth() * s / 2f, -f.bmp.getHeight() * s,
                    f.bmp.getWidth() * s / 2f, 0);
        }
        if (f.dx != 0) rf.offset(f.dx * zoom, 0);
        cv.drawBitmap(f.bmp, frameSrc, rf, paint);
        paint.setAlpha(255);
    }

    private int speakerColor(String s) {
        if (s.equals("NilouZila")) return 0xFFff2bd6;
        if (s.equals("Velkarya")) return 0xFF7dff8a;
        if (s.equals("Soldier")) return 0xFFffaa55;
        return 0xFFffffff;
    }
}
