package com.down.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public abstract class Hero {

    public static final int HIDDEN = -1;

    public static final int EV_NONE = 0, EV_STRIKE = 1, EV_BOLT = 2,
            EV_AOE = 3, EV_POISON = 4, EV_SHAKE = 5;

    public static class SheetSpec {
        public final String id, path;
        public final int rows, cols, margin;
        public final boolean vCrop, cCenter;
        public SheetSpec(String id, String path, int rows, int cols, int margin,
                         boolean vCrop, boolean cCenter) {
            this.id = id; this.path = path; this.rows = rows; this.cols = cols;
            this.margin = margin; this.vCrop = vCrop; this.cCenter = cCenter;
        }
    }

    public static class Step {
        public final String sheet; public final int frame;
        public final float dur; public final int event;
        public final float lungeX, lungeY;
        public Step(String sheet, int frame, float dur, int event) {
            this(sheet, frame, dur, event, 0, 0);
        }
        public Step(String sheet, int frame, float dur, int event, float lungeX, float lungeY) {
            this.sheet = sheet; this.frame = frame; this.dur = dur; this.event = event;
            this.lungeX = lungeX; this.lungeY = lungeY;
        }
    }

    public static class Attack {
        public final int range, dmg, mana, kind;
        public final Step[] steps;
        public Attack(int range, int dmg, int mana, int kind, Step[] steps) {
            this.range = range; this.dmg = dmg; this.mana = mana;
            this.kind = kind; this.steps = steps;
        }
    }

    public String name = "?", voice = "nilou", keyPrefix = "x:";
    public int moveMax = 3;
    public float hoverLift = -15f;
    public String[] atkSfx = new String[] { "swing", "bolt", "nova" };
    public SheetSpec[] sheets = new SheetSpec[0];
    public Attack[] attacks = new Attack[0];
    protected String[] idleSheets;
    protected String glideSheet;
    protected int[] glideLoop; protected float glideFps = 6f;
    protected int[] liftSeq; protected float[] liftDur;
    protected int[] landSeq; protected float[] landDur;

    protected List<Frame> idleFrames = new ArrayList<>();
    protected HashMap<String, List<Frame>> all;

    public int mode = 0;
    public float clock = 0, modeT = 0;
    public int seqI = 0;
    public boolean hidden;
    public float visualY = 0;
    public float visualX = 0; // Lunge offset
    public Frame frameA, frameB; public float frameK;
    public Attack cur; public Enemy target;

    private final int[] evq = new int[8]; private int evH, evT;

    public boolean attacking() { return mode == 4; }
    public boolean airborne() { return mode == 1 || mode == 2; }

    public void bind(HashMap<String, List<Frame>> frames) {
        all = frames;
        idleFrames.clear();
        for (String id : idleSheets) {
            List<Frame> l = frames.get(keyPrefix + id);
            if (l != null) idleFrames.addAll(l);
        }
        decorate();
    }

    protected void decorate() {}

    protected Frame fr(String sheet, int i) {
        List<Frame> l = all == null ? null : all.get(keyPrefix + sheet);
        if (l == null || i < 0 || i >= l.size()) return null;
        return l.get(i);
    }

    protected void fire(int e) { if (e != EV_NONE) evq[evT++ & 7] = e; }
    public int pollEvent() { if (evH == evT) return 0; return evq[evH++ & 7]; }

    public void startAttack(int idx, Enemy tgt) {
        if (attacking()) return;
        cur = attacks[idx];
        target = tgt;
        mode = 4; seqI = 0; modeT = 0;
        Step s = cur.steps[0];
        hidden = s.frame == HIDDEN;
        fire(s.event);
    }

    public void updateAnim(float dt, boolean isMoving) {
        float liftTarget = airborne() ? hoverLift : 0f;
        visualY += (liftTarget - visualY) * (1f - (float) Math.exp(-dt * 15f));

        // Lunge interpolation
        float lx = 0, ly = 0;
        if (mode == 4 && cur != null && seqI < cur.steps.length) {
            Step s = cur.steps[seqI];
            lx = s.lungeX;
            ly = s.lungeY;
        }
        visualX += (lx - visualX) * (1f - (float) Math.exp(-dt * 25f));

        if (mode == 4) {
            modeT += dt;
            Step s = cur.steps[seqI];
            while (modeT >= s.dur) {
                modeT -= s.dur;
                seqI++;
                if (seqI >= cur.steps.length) {
                    mode = 0; clock = 0; cur = null; hidden = false;
                    frameB = null; frameK = 0; frameA = idleFrame();
                    return;
                }
                s = cur.steps[seqI];
                hidden = s.frame == HIDDEN;
                fire(s.event);
            }
            Step nb = cur.steps[Math.min(seqI + 1, cur.steps.length - 1)];
            frameA = s.frame == HIDDEN ? null : fr(s.sheet, s.frame);
            frameB = nb.frame == HIDDEN ? null : fr(nb.sheet, nb.frame);
            frameK = blend(modeT / s.dur);
            return;
        }

        switch (mode) {
            case 0:
                clock += dt;
                if (isMoving) {
                    if (liftSeq != null && liftSeq.length > 0) { mode = 1; seqI = 0; modeT = 0; }
                    else { mode = 2; clock = 0; }
                }
                break;
            case 1:
                modeT += dt;
                while (modeT >= liftDur[seqI]) {
                    modeT -= liftDur[seqI];
                    seqI++;
                    if (seqI >= liftSeq.length) { mode = 2; clock = 0; break; }
                }
                if (mode == 1 && !isMoving) { mode = 3; seqI = 0; modeT = 0; }
                break;
            case 2:
                clock += dt;
                if (!isMoving) { mode = 3; seqI = 0; modeT = 0; }
                break;
            case 3:
                modeT += dt;
                while (modeT >= landDur[seqI]) {
                    modeT -= landDur[seqI];
                    seqI++;
                    if (seqI >= landSeq.length) { mode = 0; clock = 0; break; }
                }
                if (mode == 3 && isMoving) {
                    if (liftSeq != null && liftSeq.length > 0) { mode = 1; seqI = 0; modeT = 0; }
                    else { mode = 2; clock = 0; }
                }
                break;
        }

        frameB = null; frameK = 0; hidden = false;
        if (mode == 0) frameA = idleFrame();
        else if (mode == 1) frameA = fr(glideSheet, liftSeq[seqI]);
        else if (mode == 2) {
            float pos = clock * glideFps;
            int i0 = ((int) pos) % glideLoop.length;
            int i1 = (i0 + 1) % glideLoop.length;
            frameA = fr(glideSheet, glideLoop[i0]);
            frameB = fr(glideSheet, glideLoop[i1]);
            frameK = blend(pos - (int) pos);
        } else frameA = fr(glideSheet, landSeq[Math.min(seqI, landSeq.length - 1)]);
    }

    protected Frame idleFrame() {
        if (idleFrames.isEmpty()) return null;
        return idleFrames.get(((int) (clock * 3f)) % idleFrames.size());
    }

    protected static float blend(float frac) {
        float k = (frac - 0.65f) / 0.35f;
        return k < 0 ? 0 : (k > 1 ? 1 : k);
    }
}
