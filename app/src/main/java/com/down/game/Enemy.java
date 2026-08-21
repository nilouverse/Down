package com.down.game;

public class Enemy {

    public float x, y;
    public int hp = 30, maxHp = 30;
    public int gender = 0;
    public int weapon = 0;
    public boolean cried = false;
    public float speed = 220;
    public int facing = 1;
    public float animT = (float) (Math.random() * 10);
    public float hitFlash = 0;
    public boolean dead = false;
    public float deathT = 0;
    public float attackT = -1;
    public boolean struck = false;
    public int poisonTurns = 0;
    public final Floater floater = new Floater();

    // Heavy class — shield-bearing variant with mana + 2 attack forms
    public boolean heavy = false;
    public int mana = 100, maxMana = 100;
    public int atkForm = 0;     // 0 = default, 1 = heavy blade combo, 2 = heavy nova
    public int atkPos = 0;      // current position in the attack sequence
    public float attackDuration = ATK_DUR;

    // Heavy attack sequences (0-indexed frame indices within the heavy attack sheet)
    // attack 1 — blade combo (9 frames)
    // attack 2 — shield nova (6 frames)
    public static final int[][] HEAVY_ATK_SEQ = {
            { 0, 2, 3, 7, 6, 10, 4, 5, 0 },
            { 0, 4, 2, 3, 5, 8 }
    };
    public static final int[] HEAVY_ATK_STRIKE = { 5, 5 };
    public static final int[] HEAVY_ATK_DMG    = { 12, 15 };
    public static final int[] HEAVY_ATK_MANA   = {  0, 60 };
    public static final int[] HEAVY_ATK_RANGE  = {  1,  2 };
    public static final float[] HEAVY_ATK_DUR  = { 0.95f, 1.10f };

    public static final float MANA_REGEN = 30f;

    public int act = 0;
    public boolean planned = false;
    public boolean acted = false;
    public int intent = 0;
    public int attacksPlanned = 0, attacksDone = 0;
    public float[] pathX = new float[7];
    public float[] pathY = new float[7];
    public int pathLen = 0, pathI = 0;

    public Frame curF, prevF;
    public float fadeT = 1f;
    public int lastGroup = -1;

    public static final float GLOW_DUR = 0.5f;
    public static final float ATK_DUR = 0.9f;

    public boolean attacking() { return attackT >= 0; }

    public void resetTurn() {
        act = 0; planned = false; acted = false; intent = 0;
        attackT = -1; struck = false;
        attacksPlanned = 0; attacksDone = 0;
        pathLen = 0; pathI = 0;
        atkPos = 0;
        if (heavy && mana < maxMana) {
            mana = (int) Math.min(maxMana, mana + MANA_REGEN);
        }
    }

    public void present(Frame f, int group, float dt) {
        if (f == null) { curF = null; prevF = null; fadeT = 1f; lastGroup = -1; return; }
        if (group != lastGroup) {
            prevF = (curF != null && curF != f) ? curF : null;
            curF = f;
            fadeT = (prevF != null) ? 0f : 1f;
            lastGroup = group;
        } else {
            curF = f;
            if (fadeT < 1f) {
                fadeT += dt / 0.12f;
                if (fadeT >= 1f) { fadeT = 1f; prevF = null; }
            }
        }
    }

    public void turnUpdate(float dt, float px, float py, boolean adjacent, boolean inRange2) {
        switch (act) {
            case 0:
                if (pathI < pathLen) {
                    float dx = pathX[pathI] - x, dy = pathY[pathI] - y;
                    float d2 = dx * dx + dy * dy;
                    if (d2 > 36f) {
                        floater.moving = true;
                        float d = (float) Math.sqrt(d2);
                        float step = Math.min(d, speed * dt);
                        x += dx / d * step;
                        y += dy / d * step;
                        if (dx < -0.05f) facing = -1;
                        if (dx >  0.05f) facing =  1;
                    } else {
                        x = pathX[pathI]; y = pathY[pathI];
                        pathI++;
                        if (pathI >= pathLen) floater.moving = false;
                    }
                } else if (floater.state == 0) {
                    boolean canStrike = (heavy && atkForm == 2) ? inRange2 : adjacent;
                    if (attacksPlanned > 0 && canStrike) {
                        act = 1;
                        attackT = 0;
                        atkPos = 0;
                        struck = false;
                        attackDuration = heavy ? HEAVY_ATK_DUR[atkForm - 1] : ATK_DUR;
                        facing = px >= x ? 1 : -1;
                    } else {
                        act = 3;
                    }
                }
                break;

            case 1:
                floater.moving = false;
                if (attackT >= 0) {
                    attackT += dt;
                    if (heavy) {
                        int[] seq = HEAVY_ATK_SEQ[atkForm - 1];
                        float t = attackT / attackDuration;
                        int newPos = Math.min(seq.length - 1, (int) (t * seq.length));
                        if (newPos > atkPos) atkPos = newPos;
                    }
                    if (attackT > attackDuration) {
                        attacksDone++;
                        struck = false;
                        atkPos = 0;
                        boolean nextAdjacent = (Math.abs(px - x) + Math.abs(py - y)) < 200f;
                        boolean nextInRange2 = (Math.abs(px - x) + Math.abs(py - y)) < 400f;
                        boolean canAgain = (heavy && atkForm == 2) ? nextInRange2 : nextAdjacent;
                        if (attacksDone < attacksPlanned && canAgain) {
                            attackT = 0;
                            attackDuration = heavy ? HEAVY_ATK_DUR[atkForm - 1] : ATK_DUR;
                        } else {
                            attackT = -1;
                            act = 3;
                        }
                    }
                }
                break;
        }
        floater.update(dt);
    }
}
