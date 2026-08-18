package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Story {

    private static final float SQUASH = 0.6f, HEX = 96f, TILE = 192f, TH = TILE * SQUASH;
    private static final float FOOT_DROP = 40f;
    private static final int C_BLOOD = 0xFFb3102a, C_BRIGHT = 0xFFff2747, C_EMBER = 0xFFff7a1a,
            C_BONE = 0xFFefe6dd, C_CYAN = 0xFF34e3d6, C_VIOLET = 0xFFb07cff, C_MAGENTA = 0xFFff2d7e;

    private final Context ctx;
    private final Paint paint = new Paint();
    private final RectF rf = new RectF();
    private final Rect frameSrc = new Rect();
    private Typeface fLogo, fBody;

    private float camX, camY, zoom = 1.2f;
    private int W, H;
    private boolean camSnap;
    private float shakeX, shakeY, shakeT = 9;
    private float flashT = 9, flashStr;
    private float nameT = 9;
    private String sectionName = "";
    private int groundCol = 0xFF140B16;
    private float fade = 1;
    public boolean quitRequested;

    private static class Crack { float x1, y1, x2, y2; }
    private final ArrayList<Crack> cracks = new ArrayList<>();
    private static class Prop { Bitmap bmp; float ax, ay, s; boolean decal; }
    private final ArrayList<Prop> props = new ArrayList<>();

    private static class Actor {
        String id;
        float x, y, tx, ty;
        int facing = 1;
        float animT = (float) (Math.random() * 10);
        boolean moving, hidden, hideOnArrive;
        List<Frame> idle, glide;
    }
    private final ArrayList<Actor> actors = new ArrayList<>();
    private Actor player;

    private static final int EV_SAY = 0, EV_ACTION = 1, EV_WALK = 2, EV_SHOW = 3,
            EV_HIDE = 4, EV_CONTROL = 5, EV_TRIGGER = 6, EV_RESET = 7;
    private static class Ev { int type; String a, b; int q, r; }
    private final ArrayList<Ev> evs = new ArrayList<>();
    private int idx;
    private boolean dialogUp, control, ended;

    private static class Fx { float x, y, rot, t; int kind; boolean active; }
    private final Fx[] fx = new Fx[40];
    private Bitmap bloodBmp, shadowBmp;

    private final HashMap<String, List<Frame>> heroFrames;
    private List<Frame> soldierBase;

    private static final String[] PROP_NAMES = {
            "gate", "spire", "wall", "bonepillar", "rubble", "street", "barricade", "bones" };
    private static final boolean[] PROP_DECAL = {
            false, false, false, false, false, true, false, true };
    private List<Bitmap> city;

    private static class SD { float y; Prop p; Actor a; }
    private final ArrayList<SD> sdList = new ArrayList<>();
    private final ArrayList<SD> sdPool = new ArrayList<>();

    private int cacheKey;
    private final ArrayList<String> cacheLines = new ArrayList<>();

    public Story(Context c, HashMap<String, List<Frame>> heroFrames) {
        ctx = c;
        this.heroFrames = heroFrames;
        paint.setFilterBitmap(true);
        try { fLogo = Typeface.createFromAsset(c.getAssets(), "fonts/MetalMania-Regular.ttf"); } catch (Exception e) { fLogo = Typeface.DEFAULT; }
        try { fBody = Typeface.createFromAsset(c.getAssets(), "fonts/SpaceGrotesk-Bold.ttf"); } catch (Exception e) { fBody = Typeface.DEFAULT_BOLD; }
        city = Sprites.cutSheet(c, "sprites/props_city.png", 2, 4, 4);
        soldierBase = Sprites.buildFrames(Sprites.cutSheet(c, "sprites/soldier.png", 2, 2, 12), false, true);
        try { bloodBmp = BitmapFactory.decodeStream(c.getAssets().open("art/blood.webp")); } catch (Exception e) { bloodBmp = null; }
        for (int i = 0; i < fx.length; i++) fx[i] = new Fx();
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
    private final float[] FW = new float[2];
    private final int[] IH = new int[2];

    // ---------- loading ----------
    public void load(String name) {
        evs.clear();
        idx = 0; dialogUp = false; control = false; ended = false;
        camSnap = false; nameT = 0; fade = 1;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open("story/" + name + ".txt")));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                String[] t = line.split("\\s+");
                String cmd = t[0];
                if (cmd.equals("NAME")) sectionName = line.substring(4).trim();
                else if (cmd.equals("GROUND")) groundCol = (int) (Long.parseLong(t[1].substring(2), 16) | 0xFF000000L);
                else if (cmd.equals("RESET")) { addEv(EV_RESET, null, null, 0, 0); }
                else if (cmd.equals("CRACK")) {
                    Ev ev = new Ev(); ev.type = EV_ACTION; ev.a = "crack";
                    ev.q = Integer.parseInt(t[1]); ev.r = Integer.parseInt(t[2]);
                    ev.b = t[3] + " " + t[4];
                    evs.add(ev);
                } else if (cmd.equals("PLACE")) { placeProp(t); }
                else if (cmd.equals("ACTOR")) { addActor(t); }
                else if (cmd.equals("SAY")) {
                    Ev ev = new Ev(); ev.type = EV_SAY; ev.a = t[1];
                    ev.b = line.substring(line.indexOf(t[1]) + t[1].length()).trim();
                    evs.add(ev);
                } else if (cmd.equals("ACTION")) {
                    Ev ev = new Ev(); ev.type = EV_ACTION; ev.a = t[1];
                    if (t.length > 3) { ev.q = Integer.parseInt(t[2]); ev.r = Integer.parseInt(t[3]); }
                    evs.add(ev);
                } else if (cmd.equals("WALK") || cmd.equals("EXIT")) {
                    Ev ev = new Ev(); ev.type = EV_WALK; ev.a = t[1];
                    ev.q = Integer.parseInt(t[2]); ev.r = Integer.parseInt(t[3]);
                    ev.b = cmd.equals("EXIT") ? "hide" : null;
                    evs.add(ev);
                } else if (cmd.equals("SHOW")) { addEv(EV_SHOW, t[1], null, 0, 0); }
                else if (cmd.equals("HIDE")) { addEv(EV_HIDE, t[1], null, 0, 0); }
                else if (cmd.equals("CONTROL")) { addEv(EV_CONTROL, null, null, 0, 0); }
                else if (cmd.equals("TRIGGER")) {
                    addEv(EV_TRIGGER, null, null, Integer.parseInt(t[1]), Integer.parseInt(t[2]));
                }
            }
            br.close();
        } catch (Exception e) { ended = true; }
        if (actors.isEmpty()) ended = true;
    }

    private void addEv(int type, String a, String b, int q, int r) {
        Ev ev = new Ev(); ev.type = type; ev.a = a; ev.b = b; ev.q = q; ev.r = r;
        evs.add(ev);
    }

    private void placeProp(String[] t) {
        int pi = -1;
        for (int i = 0; i < PROP_NAMES.length; i++)
            if (PROP_NAMES[i].equals(t[1])) { pi = i; break; }
        if (pi < 0 || city.isEmpty()) return;
        Bitmap bmp = city.get(pi);
        Prop p = new Prop();
        p.bmp = bmp;
        p.decal = PROP_DECAL[pi];
        hexToWorld(Integer.parseInt(t[2]), Integer.parseInt(t[3]), FW);
        p.ax = FW[0]; p.ay = FW[1] + TH * 0.3f;
        p.s = p.decal ? (TILE * 1.8f) / bmp.getWidth() : (TH * 2.3f) / bmp.getHeight();
        props.add(p);
    }

    private void addActor(String[] t) {
        Actor a = new Actor();
        a.id = t[1];
        hexToWorld(Integer.parseInt(t[2]), Integer.parseInt(t[3]), FW);
        a.x = a.tx = FW[0]; a.y = a.ty = FW[1];
        int tint = 0;
        for (int i = 4; i < t.length; i++) {
            if (t[i].equals("left")) a.facing = -1;
            else if (t[i].equals("hidden")) a.hidden = true;
            else if (t[i].charAt(0) == '#') tint = (int) (Long.parseLong(t[i].substring(1), 16) | 0xFF000000L);
        }
        if (a.id.equals("nilou")) {
            a.idle = heroFrames == null ? null : heroFrames.get("nilou:idle");
            a.glide = heroFrames == null ? null : heroFrames.get("nilou:glide");
        } else if (a.id.equals("vel")) {
            a.idle = tintedSoldier(tint != 0 ? tint : C_CYAN);
            a.glide = a.idle;
        } else {
            a.idle = tint != 0 ? tintedSoldier(tint) : soldierBase;
            a.glide = a.idle;
        }
        actors.add(a);
        if (player == null || a.id.equals("nilou")) player = a;
    }

    private List<Frame> tintedSoldier(int color) {
        ArrayList<Frame> out = new ArrayList<>();
        for (Frame f : soldierBase) {
            Frame c = new Frame();
            c.bmp = Sprites.tinted(f.bmp, color);
            c.top = f.top; c.ch = f.ch; c.left = f.left; c.cw = f.cw;
            c.rgt = f.rgt; c.ww = f.ww; c.ref = f.ref; c.vCrop = f.vCrop; c.cCenter = f.cCenter;
            out.add(c);
        }
        return out;
    }

    private Actor findActor(String id) {
        for (Actor a : actors) if (a.id.equals(id)) return a;
        return null;
    }

    // ---------- fx ----------
    private void spawnFx(int kind, int q, int r) {
        for (Fx f : fx) {
            if (!f.active) {
                hexToWorld(q, r, FW);
                f.x = FW[0]; f.y = FW[1];
                f.rot = (float) (Math.random() * 360);
                f.t = 0; f.kind = kind; f.active = true;
                return;
            }
        }
    }

    // ---------- update ----------
    public void update(float dt) {
        nameT += dt;
        flashT += dt;
        shakeT += dt;
        if (fade > 0) fade = Math.max(0, fade - dt * 2.5f);

        for (Fx f : fx) {
            if (!f.active) continue;
            f.t += dt;
            if ((f.kind == 0 && f.t > 0.25f) || (f.kind == 1 && f.t > 60f)) f.active = f.kind != 1;
            if (f.kind == 1 && f.t > 60f) f.active = false;
        }

        for (Actor a : actors) {
            a.animT += dt;
            float dx = a.tx - a.x, dy = a.ty - a.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
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

        if (!ended && !dialogUp && !control) {
            while (idx < evs.size()) {
                Ev ev = evs.get(idx);
                if (ev.type == EV_SAY) { dialogUp = true; break; }
                if (ev.type == EV_CONTROL) { control = true; idx++; break; }
                if (ev.type == EV_RESET) {
                    actors.clear(); props.clear(); cracks.clear();
                    for (Fx f : fx) f.active = false;
                    player = null;
                    idx++;
                } else if (ev.type == EV_ACTION) {
                    runAction(ev);
                    idx++;
                } else if (ev.type == EV_SHOW) {
                    Actor a = findActor(ev.a); if (a != null) a.hidden = false; idx++;
                } else if (ev.type == EV_HIDE) {
                    Actor a = findActor(ev.a); if (a != null) a.hidden = true; idx++;
                } else if (ev.type == EV_WALK) {
                    Actor a = findActor(ev.a);
                    if (a == null) { idx++; continue; }
                    hexToWorld(ev.q, ev.r, FW);
                    a.tx = FW[0]; a.ty = FW[1];
                    a.hideOnArrive = ev.b != null;
                    if (!a.moving && Math.sqrt((a.tx - a.x) * (a.tx - a.x)
                            + (a.ty - a.y) * (a.ty - a.y)) <= 4) {
                        if (a.hideOnArrive) { a.hidden = true; a.hideOnArrive = false; }
                        idx++;
                    } else break;
                } else idx++;
            }
            if (idx >= evs.size()) { dialogUp = false; control = false; ended = true; }
        }

        if (control && player != null) {
            // free roam until a TRIGGER (none in act1 — reserved for acts two+)
        }

        if (player != null && H > 0) {
            if (!camSnap) {
                camX = player.x;
                camY = player.y - (H * 0.25f) / zoom;
                camSnap = true;
            }
            float k = 1 - (float) Math.exp(-dt * 6);
            camX += (player.x - camX) * k;
            camY += ((player.y - (H * 0.25f) / zoom) - camY) * k;
        }
    }

    private void runAction(Ev ev) {
        String a = ev.a;
        if (a.equals("flash")) { flashT = 0; flashStr = 1; }
        else if (a.equals("flash2")) { flashT = 0; flashStr = 0.4f; }
        else if (a.equals("shake")) { shakeT = 0; }
        else if (a.equals("slash")) { spawnFx(0, ev.q, ev.r); }
        else if (a.equals("blood")) { spawnFx(1, ev.q, ev.r); }
        else if (a.equals("crack")) {
            Crack c = new Crack();
            hexToWorld(ev.q, ev.r, FW);
            c.x1 = FW[0]; c.y1 = FW[1];
            String[] b = ev.b.split("\\s+");
            hexToWorld(Integer.parseInt(b[0]), Integer.parseInt(b[1]), FW);
            c.x2 = FW[0]; c.y2 = FW[1];
            cracks.add(c);
        }
    }

    // ---------- touch ----------
    private float downX = -9999, downY, lastPX, lastPY;
    private boolean moved;

    public boolean touch(MotionEvent e) {
        int act = e.getActionMasked();
        if (ended) {
            if (act == MotionEvent.ACTION_UP) quitRequested = true;
            return true;
        }
        if (fade > 0.05f) return true;
        if (dialogUp && act == MotionEvent.ACTION_UP) { idx++; dialogUp = false; return true; }
        if (act == MotionEvent.ACTION_DOWN) {
            downX = e.getX(); downY = e.getY();
            lastPX = downX; lastPY = downY;
            moved = false;
            return true;
        }
        if (act == MotionEvent.ACTION_MOVE) {
            if (downX < -9000) return true;
            float x = e.getX(), y = e.getY();
            if (!moved && Math.sqrt((x - downX) * (x - downX) + (y - downY) * (y - downY)) > 26) moved = true;
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
        worldToHex(wx, wy, IH);
        worldToHex(player.x, player.y, new int[2]);
        player.tx = wx; player.ty = wy;
        return true;
    }

    // ---------- draw ----------
    private float sx(float wx) { return (wx - camX) * zoom + W / 2f + shakeX; }
    private float sy(float wy) { return (wy - camY) * zoom + H / 2f + shakeY; }

    public void draw(Canvas cv) {
        W = cv.getWidth(); H = cv.getHeight();
        shakeX = 0; shakeY = 0;
        if (shakeT < 0.4f) {
            float k = 1 - shakeT / 0.4f;
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
            paint.setColor(C_EMBER);
            cv.drawLine(sx(c.x1), sy(c.y1), sx(c.x2), sy(c.y2), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        for (Fx f : fx) if (f.active && f.kind == 1) drawBlood(cv, f);
        for (Prop p : props) if (p.decal) drawProp(cv, p);

        for (int i = 0; i < sdList.size(); i++) sdPool.add(sdList.get(i));
        sdList.clear();
        for (Prop p : props) if (!p.decal) sdList.add(obtainSD(p.ay, p, null));
        for (Actor a : actors) if (!a.hidden) sdList.add(obtainSD(a.y, null, a));
        java.util.Collections.sort(sdList, new java.util.Comparator<SD>() {
            public int compare(SD a, SD b) { return a.y < b.y ? -1 : (a.y > b.y ? 1 : 0); }
        });
        for (SD s : sdList) {
            if (s.p != null) drawProp(cv, s.p);
            else drawActor(cv, s.a);
        }

        for (Fx f : fx) if (f.active && f.kind == 0) drawSlash(cv, f);

        if (flashT < 0.5f) {
            paint.setColor(0xFFffddaa);
            paint.setAlpha((int) ((1 - flashT / 0.5f) * flashStr * 200));
            cv.drawRect(0, 0, W, H, paint);
            paint.setAlpha(255);
        }

        if (dialogUp && idx < evs.size()) {
            Ev ev = evs.get(idx);
            float ph = H * 0.24f;
            paint.setColor(0xDD0e0709);
            cv.drawRect(0, H - ph, W, H, paint);
            paint.setColor(C_MAGENTA);
            cv.drawRect(0, H - ph, W, H - ph + 3, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTypeface(fBody);
            paint.setTextSize(H * 0.032f);
            paint.setColor(speakerColor(ev.a));
            cv.drawText(ev.a, 40, H - ph + H * 0.065f, paint);
            paint.setColor(C_BONE);
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
            paint.setTypeface(fLogo);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(C_MAGENTA);
            cv.drawText(sectionName, W / 2f, H * 0.22f, paint);
            paint.setTypeface(fBody);
            paint.setAlpha(255);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        if (ended) {
            paint.setColor(0xCC000000);
            cv.drawRect(0, 0, W, H, paint);
            paint.setTextSize(64);
            paint.setTypeface(fLogo);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(C_MAGENTA);
            cv.drawText("TO BE CONTINUED", W / 2f, H / 2f, paint);
            paint.setTypeface(fBody);
            paint.setTextSize(26);
            paint.setColor(0xAAefe6dd);
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

    private SD obtainSD(float y, Prop p, Actor a) {
        SD s = sdPool.isEmpty() ? new SD() : sdPool.remove(sdPool.size() - 1);
        s.y = y; s.p = p; s.a = a;
        return s;
    }

    private void drawBlood(Canvas cv, Fx f) {
        if (bloodBmp == null) return;
        float s = 0.4f * zoom;
        float w = bloodBmp.getWidth() * s, h = bloodBmp.getHeight() * s;
        cv.save();
        cv.translate(sx(f.x), sy(f.y));
        cv.rotate(f.rot);
        paint.setAlpha(140);
        rf.set(-w / 2f, -h / 2f, w / 2f, h / 2f);
        cv.drawBitmap(bloodBmp, null, rf, paint);
        paint.setAlpha(255);
        cv.restore();
    }

    private void drawSlash(Canvas cv, Fx f) {
        float k = f.t / 0.25f;
        float r = (26 + k * 64) * zoom;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.5f * zoom * (1 - k));
        paint.setColor(C_MAGENTA);
        paint.setAlpha((int) (150 * (1 - k)));
        rf.set(sx(f.x) - r, sy(f.y) - r * SQUASH, sx(f.x) + r, sy(f.y) + r * SQUASH);
        cv.drawArc(rf, f.rot, 110, false, paint);
        paint.setColor(C_BONE);
        paint.setStrokeWidth(1.5f * zoom * (1 - k));
        paint.setAlpha((int) (200 * (1 - k)));
        cv.drawArc(rf, f.rot + 20, 70, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(0);
        paint.setAlpha(255);
    }

    private void drawProp(Canvas cv, Prop p) {
        rf.set(sx(p.ax) - p.bmp.getWidth() * p.s / 2f, sy(p.ay) - p.bmp.getHeight() * p.s,
               sx(p.ax) + p.bmp.getWidth() * p.s / 2f, sy(p.ay));
        paint.setAlpha(255);
        cv.drawBitmap(p.bmp, null, rf, paint);
    }

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
        float x = sx(a.x), y = sy(a.y) + FOOT_DROP * zoom;
        boolean idle = !a.moving;
        float br = idle ? (float) Math.sin(a.animT * 1.7f) : 0f;

        float sw = 45 * zoom * (1f - 0.045f * br);
        paint.setAlpha(200);
        rf.set(x - sw, y - sw * 0.36f, x + sw, y + sw * 0.36f);
        cv.drawBitmap(shadow(), null, rf, paint);
        paint.setAlpha(255);

        cv.save();
        cv.translate(x, y);
        if (a.facing < 0) cv.scale(-1, 1);
        if (br != 0f) cv.scale(1f - 0.018f * br, 1f + 0.03f * br);
        Frame fa = null, fb = null;
        float fk = 0;
        if (a.moving && a.glide != null && a.glide.size() >= 2) {
            float pos = a.animT * 6f;
            int i0 = ((int) pos) % a.glide.size();
            int i1 = (i0 + 1) % a.glide.size();
            fa = a.glide.get(i0);
            fb = a.glide.get(i1);
            fk = blend(pos - (int) pos);
        } else if (a.idle != null && !a.idle.isEmpty()) {
            fa = a.idle.get(((int) (a.animT * 3f)) % a.idle.size());
        }
        if (fa != null) drawFrame(cv, fa, 255);
        if (fb != null && fk > 0.02f) drawFrame(cv, fb, (int) (fk * 255));
        cv.restore();
    }

    private static float blend(float frac) {
        float k = (frac - 0.65f) / 0.35f;
        return k < 0 ? 0 : (k > 1 ? 1 : k);
    }

    private void drawFrame(Canvas cv, Frame f, int alpha) {
        if (f == null || f.bmp == null || f.bmp.isRecycled()) return;
        if (f.cw <= 0 || f.ch <= 0) return;
        float s = 200f * zoom / f.ref;
        if (s <= 0) return;
        paint.setAlpha(alpha);
        if (f.cCenter) {
            int wl = Math.max(0, f.rgt - f.ww);
            int wr = f.rgt;
            if (wl >= wr || f.top + f.ch > f.bmp.getHeight() || f.top < 0) {
                frameSrc.set(0, 0, f.bmp.getWidth(), f.bmp.getHeight());
                rf.set(-f.bmp.getWidth() * s / 2f, -f.bmp.getHeight() * s,
                        f.bmp.getWidth() * s / 2f, 0);
            } else {
                frameSrc.set(wl, f.top, wr, f.top + f.ch);
                float right = f.ww * s / 2f;
                rf.set(right - (wr - wl) * s, -f.ch * s, right, 0);
            }
        } else {
            frameSrc.set(0, 0, f.bmp.getWidth(), f.bmp.getHeight());
            rf.set(-f.bmp.getWidth() * s / 2f, -f.bmp.getHeight() * s,
                    f.bmp.getWidth() * s / 2f, 0);
        }
        if (f.dx != 0) rf.offset(f.dx * zoom, 0);
        if (rf.left >= rf.right || rf.top >= rf.bottom) { paint.setAlpha(255); return; }
        cv.drawBitmap(f.bmp, frameSrc, rf, paint);
        paint.setAlpha(255);
    }

    private void wrapText(Canvas cv, String text, float x, float y, float maxW, float lh) {
        int key = text.hashCode() ^ (int) maxW;
        if (key != cacheKey) {
            cacheKey = key;
            cacheLines.clear();
            String[] words = text.split("\\s+");
            StringBuilder line = new StringBuilder();
            for (String w : words) {
                String test = line.length() == 0 ? w : line + " " + w;
                if (paint.measureText(test) > maxW && line.length() > 0) {
                    cacheLines.add(line.toString());
                    line = new StringBuilder(w);
                } else line = new StringBuilder(test);
            }
            if (line.length() > 0) cacheLines.add(line.toString());
        }
        float cy = y;
        for (String l : cacheLines) { cv.drawText(l, x, cy, paint); cy += lh; }
    }

    private int speakerColor(String s) {
        if (s.equals("NilouZila")) return C_MAGENTA;
        if (s.equals("Velkarya")) return C_VIOLET;
        if (s.equals("ws")) return C_EMBER;
        if (s.startsWith("t")) return C_CYAN;
        if (s.startsWith("i")) return C_BRIGHT;
        if (s.startsWith("e")) return C_VIOLET;
        return C_BONE;
    }
}
